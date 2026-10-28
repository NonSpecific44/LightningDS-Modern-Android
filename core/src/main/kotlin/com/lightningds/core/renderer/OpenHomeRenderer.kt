package com.lightningds.core.renderer

import com.lightningds.core.media.Didl
import com.lightningds.core.media.Track
import com.lightningds.core.media.TrackSource
import com.lightningds.core.upnp.EventServer
import com.lightningds.core.upnp.ServiceTypes
import com.lightningds.core.upnp.UpnpDevice
import com.lightningds.core.upnp.UpnpService
import com.lightningds.core.upnp.Xml
import com.lightningds.core.upnp.childText
import com.lightningds.core.upnp.children
import com.lightningds.core.upnp.toUpnpBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Base64

/** Renderer speaking the OpenHome (av.openhome.org) service family, as Lightning devices do. */
class OpenHomeRenderer(
    private val device: UpnpDevice,
    private val events: EventServer,
    private val scope: CoroutineScope,
) : Renderer {
    override val udn: String get() = device.udn

    private val product = device.service("Product", ServiceTypes.OH)
    private val playlist = device.service("Playlist", ServiceTypes.OH)
    private val info = device.service("Info", ServiceTypes.OH)
    private val time = device.service("Time", ServiceTypes.OH)
    private val volumeSvc = device.service("Volume", ServiceTypes.OH)
    private val renderingControl = device.service("RenderingControl", ServiceTypes.UPNP)
    private val internetRadio = device.service("InternetRadio")
    private val radio = device.service("Radio", ServiceTypes.OH)
    private val receiver = device.service("Receiver", ServiceTypes.OH)

    private val _state = MutableStateFlow(
        RendererState(name = device.friendlyName, volumeAvailable = volumeSvc != null || renderingControl != null)
    )
    override val state: StateFlow<RendererState> = _state.asStateFlow()

    private val subs = mutableListOf<EventServer.EventSubscription>()
    private var ticker: Job? = null
    private var lastTimeEvent = 0L

    override fun start() {
        product?.let { s -> subs += events.subscribe(s) { onProduct(it) } }
        playlist?.let { s -> subs += events.subscribe(s) { onPlaylist(it) } }
        info?.let { s -> subs += events.subscribe(s) { onInfo(it) } }
        time?.let { s -> subs += events.subscribe(s) { onTime(it) } }
        volumeSvc?.let { s -> subs += events.subscribe(s) { onVolume(it) } }
        (internetRadio ?: radio)?.let { s -> subs += events.subscribe(s) { onRadio(it) } }
        receiver?.let { s -> subs += events.subscribe(s) { onReceiver(it) } }
        scope.launch { initialFetch() }
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                val st = _state.value
                if (st.isPlaying && System.currentTimeMillis() - lastTimeEvent > 1500) {
                    _state.update { it.copy(position = (it.position + 1).let { p -> if (it.duration > 0) p.coerceAtMost(it.duration) else p }) }
                }
            }
        }
        _state.update { it.copy(connected = true) }
    }

    override fun close() {
        subs.forEach { it.cancel() }
        subs.clear()
        ticker?.cancel()
    }

    private suspend fun initialFetch() {
        safe {
            product?.let { p ->
                val room = p.call("Product")
                val name = room.get("Room", 0).ifBlank { device.friendlyName }
                val xml = p.call("SourceXml").get("Value", 0)
                val idx = p.call("SourceIndex").int("Value")
                val standby = p.call("Standby").bool("Value")
                _state.update { it.copy(name = name, sources = parseSources(xml), sourceIndex = idx, standby = standby) }
            }
        }
        safe {
            playlist?.let { p ->
                val ts = TransportState.from(p.call("TransportState").get("Value", 0))
                val id = p.call("Id").int("Value")
                val ids = decodeIdArray(p.call("IdArray").get("Array", 1))
                val sh = p.call("Shuffle").bool("Value")
                val rp = p.call("Repeat").bool("Value")
                _state.update { it.copy(transport = ts, currentId = id, queueIds = ids, shuffle = sh, repeat = rp) }
            }
        }
        safe {
            info?.let { i ->
                val tr = i.call("Track")
                val d = i.call("Details")
                val mt = i.call("Metatext").get("Value", 0)
                _state.update {
                    it.copy(
                        trackUri = tr.get("Uri", 0),
                        track = Didl.parseFirstItem(tr.get("Metadata", 1)),
                        details = StreamDetails(d.int("Duration", 0), d.int("BitRate", 1), d.int("BitDepth", 2), d.int("SampleRate", 3), d.bool("Lossless", 4), d.get("CodecName", 5)),
                        metatext = mt.ifBlank { null },
                    )
                }
            }
        }
        safe {
            time?.let { t ->
                val r = t.call("Time")
                _state.update { it.copy(duration = r.int("Duration", 1), position = r.int("Seconds", 2)) }
            }
        }
        safe {
            if (volumeSvc != null) {
                val v = volumeSvc.call("Volume").int("Value")
                val m = volumeSvc.call("Mute").bool("Value")
                val max = runCatching { volumeSvc!!.call("Characteristics").int("VolumeMax", 0) }.getOrDefault(100)
                _state.update { it.copy(volume = v, mute = m, volumeMax = if (max > 0) max else 100) }
            } else if (renderingControl != null) {
                val v = renderingControl.call("GetVolume", "InstanceID" to 0, "Channel" to "Master").int("CurrentVolume")
                val m = renderingControl.call("GetMute", "InstanceID" to 0, "Channel" to "Master").bool("CurrentMute")
                _state.update { it.copy(volume = v, mute = m) }
            }
        }
        safe {
            (internetRadio ?: radio)?.let { r ->
                val ts = TransportState.from(r.call("TransportState").get("Value", 0))
                val ch = runCatching { r.call(if (internetRadio != null) "Sender" else "Channel") }.getOrNull()
                _state.update {
                    it.copy(
                        radioTransport = ts,
                        radioUri = ch?.get("Uri", 0),
                        radioTrack = Didl.parseFirstItem(ch?.get("Metadata", 1), TrackSource.RADIO),
                    )
                }
            }
        }
    }

    // ---- event handlers -------------------------------------------------------------

    private fun onProduct(p: Map<String, String>) = _state.update { s ->
        var n = s
        p["ProductRoom"]?.let { if (it.isNotBlank()) n = n.copy(name = it) }
        p["Standby"]?.let { n = n.copy(standby = it.toUpnpBool()) }
        p["SourceXml"]?.let { n = n.copy(sources = parseSources(it)) }
        p["SourceIndex"]?.toIntOrNull()?.let { n = n.copy(sourceIndex = it) }
        n
    }

    private fun onPlaylist(p: Map<String, String>) = _state.update { s ->
        var n = s
        p["TransportState"]?.let { n = n.copy(transport = TransportState.from(it)) }
        p["Id"]?.toIntOrNull()?.let { n = n.copy(currentId = it) }
        p["IdArray"]?.let { n = n.copy(queueIds = decodeIdArray(it)) }
        p["Shuffle"]?.let { n = n.copy(shuffle = it.toUpnpBool()) }
        p["Repeat"]?.let { n = n.copy(repeat = it.toUpnpBool()) }
        n
    }

    private fun onInfo(p: Map<String, String>) = _state.update { s ->
        var n = s
        p["Uri"]?.let { n = n.copy(trackUri = it) }
        p["Metadata"]?.let { n = n.copy(track = Didl.parseFirstItem(it)) }
        p["Metatext"]?.let { n = n.copy(metatext = it.ifBlank { null }) }
        val d = n.details
        n = n.copy(
            details = d.copy(
                duration = p["Duration"]?.toIntOrNull() ?: d.duration,
                bitRate = p["BitRate"]?.toIntOrNull() ?: d.bitRate,
                bitDepth = p["BitDepth"]?.toIntOrNull() ?: d.bitDepth,
                sampleRate = p["SampleRate"]?.toIntOrNull() ?: d.sampleRate,
                lossless = p["Lossless"]?.toUpnpBool() ?: d.lossless,
                codecName = p["CodecName"] ?: d.codecName,
            )
        )
        n
    }

    private fun onTime(p: Map<String, String>) {
        lastTimeEvent = System.currentTimeMillis()
        _state.update { s ->
            s.copy(
                duration = p["Duration"]?.toIntOrNull() ?: s.duration,
                position = p["Seconds"]?.toIntOrNull() ?: s.position,
            )
        }
    }

    private fun onVolume(p: Map<String, String>) = _state.update { s ->
        s.copy(
            volume = p["Volume"]?.toIntOrNull() ?: s.volume,
            mute = p["Mute"]?.toUpnpBool() ?: s.mute,
            volumeMax = p["VolumeMax"]?.toIntOrNull()?.takeIf { it > 0 } ?: s.volumeMax,
        )
    }

    private fun onRadio(p: Map<String, String>) = _state.update { s ->
        s.copy(
            radioTransport = p["TransportState"]?.let { TransportState.from(it) } ?: s.radioTransport,
            radioUri = p["Uri"] ?: s.radioUri,
            radioTrack = p["Metadata"]?.let { Didl.parseFirstItem(it, TrackSource.RADIO) } ?: s.radioTrack,
        )
    }

    private fun onReceiver(p: Map<String, String>) = _state.update { s ->
        s.copy(
            receiverTransport = p["TransportState"]?.let { TransportState.from(it) } ?: s.receiverTransport,
            receiverSender = p["Metadata"]?.let { Didl.parseFirstItem(it) } ?: s.receiverSender,
        )
    }

    // ---- commands ---------------------------------------------------------------------

    private fun activeTransportService(): UpnpService? {
        val src = _state.value.currentSource
        return when {
            src?.isRadio == true -> internetRadio ?: radio
            src?.isReceiver == true -> receiver
            else -> playlist
        }
    }

    override suspend fun play() { activeTransportService()?.call("Play") }
    override suspend fun pause() {
        val svc = activeTransportService()
        if (svc == playlist) svc?.call("Pause") else svc?.call("Stop")
    }
    override suspend fun stop() { activeTransportService()?.call("Stop") }
    override suspend fun next() { playlist?.call("Next") }

    override suspend fun previous() {
        // Same behaviour as the original app: restart the track if we're more than 3s in.
        if (_state.value.position > 3) playlist?.call("SeekSecondAbsolute", "Value" to 0) else playlist?.call("Previous")
    }

    override suspend fun seek(seconds: Int) {
        playlist?.call("SeekSecondAbsolute", "Value" to seconds.coerceAtLeast(0))
        _state.update { it.copy(position = seconds) }
    }

    override suspend fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, _state.value.volumeMax)
        _state.update { it.copy(volume = v) }
        if (volumeSvc != null) volumeSvc.call("SetVolume", "Value" to v)
        else renderingControl?.call("SetVolume", "InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to v)
    }

    override suspend fun volumeStep(up: Boolean) {
        if (volumeSvc != null) volumeSvc.call(if (up) "VolumeInc" else "VolumeDec")
        else setVolume(_state.value.volume + if (up) 1 else -1)
    }

    override suspend fun setMute(mute: Boolean) {
        _state.update { it.copy(mute = mute) }
        if (volumeSvc != null) volumeSvc.call("SetMute", "Value" to mute)
        else renderingControl?.call("SetMute", "InstanceID" to 0, "Channel" to "Master", "DesiredMute" to mute)
    }

    override suspend fun setShuffle(shuffle: Boolean) { playlist?.call("SetShuffle", "Value" to shuffle) }
    override suspend fun setRepeat(repeat: Boolean) { playlist?.call("SetRepeat", "Value" to repeat) }
    override suspend fun setStandby(standby: Boolean) { product?.call("SetStandby", "Value" to standby) }

    override suspend fun selectSource(index: Int) {
        product?.call("SetSourceIndex", "Value" to index)
        _state.update { it.copy(sourceIndex = index) }
    }

    private suspend fun ensureSource(predicate: (RendererSource) -> Boolean) {
        val s = _state.value
        if (s.standby) setStandby(false)
        if (s.currentSource?.let(predicate) == true) return
        s.sources.firstOrNull(predicate)?.let { selectSource(it.index) }
    }

    override suspend fun enqueue(tracks: List<Track>, mode: InsertMode, startAt: Int) {
        val pl = playlist ?: return
        ensureSource { it.type == RendererSource.PLAYLIST }
        val st = _state.value
        var afterId = when (mode) {
            InsertMode.REPLACE -> {
                pl.call("DeleteAll")
                0
            }
            InsertMode.PLAY_NOW, InsertMode.ADD_NEXT -> st.currentId.takeIf { it in st.queueIds } ?: st.queueIds.lastOrNull() ?: 0
            InsertMode.ADD_LATER -> st.queueIds.lastOrNull() ?: 0
        }
        var firstId = -1
        tracks.forEachIndexed { i, t ->
            val uri = t.uri ?: return@forEachIndexed
            val r = pl.call("Insert", "AfterId" to afterId, "Uri" to uri, "Metadata" to Didl.build(t))
            val newId = r.int("NewId", 0)
            if (newId > 0) afterId = newId
            if (i == startAt) firstId = newId
        }
        val shouldPlay = mode == InsertMode.PLAY_NOW || mode == InsertMode.REPLACE
        if (shouldPlay && firstId > 0) pl.call("SeekId", "Value" to firstId)
        else if (shouldPlay && startAt >= 0) pl.call("Play")
    }

    override suspend fun readQueue(ids: List<Int>): List<QueueEntry> {
        val pl = playlist ?: return emptyList()
        val out = ArrayList<QueueEntry>(ids.size)
        for (chunk in ids.chunked(50)) {
            val r = pl.call("ReadList", "IdList" to chunk.joinToString(" "))
            out += parseTrackList(r.get("TrackList", 0))
        }
        val order = ids.withIndex().associate { it.value to it.index }
        return out.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    override suspend fun playQueueId(id: Int) {
        ensureSource { it.type == RendererSource.PLAYLIST }
        playlist?.call("SeekId", "Value" to id)
    }

    override suspend fun removeQueueId(id: Int) { playlist?.call("DeleteId", "Value" to id) }
    override suspend fun clearQueue() { playlist?.call("DeleteAll") }

    override suspend fun moveQueueEntry(entry: QueueEntry, afterId: Int): Int {
        val pl = playlist ?: return entry.id
        val uri = entry.track.uri ?: return entry.id
        pl.call("DeleteId", "Value" to entry.id)
        return pl.call("Insert", "AfterId" to afterId, "Uri" to uri, "Metadata" to Didl.build(entry.track)).int("NewId", 0)
    }

    override suspend fun playRadio(uri: String, metadata: Track) {
        ensureSource { it.isRadio }
        val meta = Didl.build(metadata.copy(uri = uri, upnpClass = "object.item.audioItem.audioBroadcast"))
        if (internetRadio != null) {
            internetRadio.call("SetSender", "Uri" to uri, "Metadata" to meta)
            internetRadio.call("Play")
        } else if (radio != null) {
            radio.call("SetChannel", "Uri" to uri, "Metadata" to meta)
            radio.call("Play")
        }
    }

    override suspend fun playReceiver(uri: String, metadata: Track) {
        val rc = receiver ?: return
        ensureSource { it.isReceiver }
        rc.call("SetSender", "Uri" to uri, "Metadata" to Didl.build(metadata))
        rc.call("Play")
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    companion object {
        fun decodeIdArray(base64: String?): List<Int> {
            if (base64.isNullOrBlank()) return emptyList()
            return try {
                val bytes = Base64.getMimeDecoder().decode(base64.trim())
                val buf = ByteBuffer.wrap(bytes)
                List(bytes.size / 4) { buf.int }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseSources(xml: String?): List<RendererSource> {
            val doc = Xml.parseOrNull(xml) ?: return emptyList()
            return doc.documentElement.children("Source").mapIndexed { i, s ->
                RendererSource(
                    index = i,
                    name = s.childText("Name").orEmpty(),
                    type = s.childText("Type").orEmpty(),
                    visible = s.childText("Visible")?.toUpnpBool() ?: true,
                )
            }
        }

        fun parseTrackList(xml: String?): List<QueueEntry> {
            val doc = Xml.parseOrNull(xml) ?: return emptyList()
            return doc.documentElement.children("Entry").mapNotNull { e ->
                val id = e.childText("Id")?.toIntOrNull() ?: return@mapNotNull null
                val uri = e.childText("Uri")
                val track = Didl.parseFirstItem(e.childText("Metadata"))?.copy(uri = uri)
                    ?: Track(id = id.toString(), title = uri?.substringAfterLast('/') ?: "", uri = uri)
                QueueEntry(id, track.copy(uri = uri ?: track.uri))
            }
        }
    }
}
