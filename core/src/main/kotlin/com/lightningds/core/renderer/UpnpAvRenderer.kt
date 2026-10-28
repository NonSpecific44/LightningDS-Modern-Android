package com.lightningds.core.renderer

import com.lightningds.core.media.Didl
import com.lightningds.core.media.Track
import com.lightningds.core.media.formatDuration
import com.lightningds.core.media.parseDuration
import com.lightningds.core.upnp.EventServer
import com.lightningds.core.upnp.ServiceTypes
import com.lightningds.core.upnp.UpnpDevice
import com.lightningds.core.upnp.parseLastChange
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plain UPnP AV (DLNA) renderer. These have no device-side playlist, so the queue lives in the
 * app and tracks are fed one at a time with SetAVTransportURI.
 */
class UpnpAvRenderer(
    private val device: UpnpDevice,
    private val events: EventServer,
    private val scope: CoroutineScope,
) : Renderer {
    override val udn: String get() = device.udn

    private val avt = device.service("AVTransport", ServiceTypes.UPNP)!!
    private val rc = device.service("RenderingControl", ServiceTypes.UPNP)

    private val queue = ArrayList<QueueEntry>()
    private var nextId = 1
    private val lock = Mutex()

    private val _state = MutableStateFlow(RendererState(name = device.friendlyName, volumeAvailable = rc != null, queueSupported = true))
    override val state: StateFlow<RendererState> = _state.asStateFlow()

    private val subs = mutableListOf<EventServer.EventSubscription>()
    private var poller: Job? = null
    private var wasPlaying = false
    private var radioMode = false

    override fun start() {
        subs += events.subscribe(avt) { p -> p["LastChange"]?.let { onLastChange(parseLastChange(it)) } }
        rc?.let { s -> subs += events.subscribe(s) { p -> p["LastChange"]?.let { onLastChange(parseLastChange(it)) } } }
        poller = scope.launch {
            while (isActive) {
                poll()
                delay(1000)
            }
        }
        _state.update { it.copy(connected = true) }
    }

    override fun close() {
        subs.forEach { it.cancel() }
        poller?.cancel()
    }

    private fun onLastChange(m: Map<String, String>) {
        _state.update { s ->
            s.copy(
                transport = m["TransportState"]?.let { TransportState.from(it) } ?: s.transport,
                volume = m["Volume"]?.toIntOrNull() ?: s.volume,
                mute = m["Mute"]?.toUpnpBool() ?: s.mute,
            )
        }
    }

    private suspend fun poll() {
        try {
            val ti = avt.call("GetTransportInfo", "InstanceID" to 0)
            val ts = TransportState.from(ti.get("CurrentTransportState", 0))
            val pi = avt.call("GetPositionInfo", "InstanceID" to 0)
            val dur = parseDuration(pi.get("TrackDuration", 1))
            val pos = parseDuration(pi.get("RelTime", 4))
            val meta = pi.get("TrackMetaData", 2)
            _state.update { s ->
                s.copy(
                    transport = ts,
                    duration = dur,
                    position = pos,
                    track = Didl.parseFirstItem(meta) ?: queue.firstOrNull { it.id == s.currentId }?.track ?: s.track,
                )
            }
            if (rc != null && _state.value.volumeAvailable) {
                val v = rc.call("GetVolume", "InstanceID" to 0, "Channel" to "Master").int("CurrentVolume")
                _state.update { it.copy(volume = v) }
            }
            // Auto-advance through the app-side queue.
            if (wasPlaying && !radioMode && ts == TransportState.STOPPED) {
                wasPlaying = false
                advance(1, auto = true)
            } else if (ts == TransportState.PLAYING) {
                wasPlaying = true
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun advance(delta: Int, auto: Boolean) {
        val s = _state.value
        val idx = queue.indexOfFirst { it.id == s.currentId }
        if (idx < 0 && queue.isEmpty()) return
        var target = idx + delta
        if (s.shuffle && delta > 0 && queue.size > 1) target = (queue.indices - idx).random()
        if (target >= queue.size) {
            if (s.repeat) target = 0 else return
        }
        if (target < 0) target = 0
        playEntry(queue[target])
        if (auto) wasPlaying = true
    }

    private suspend fun playEntry(e: QueueEntry) {
        val uri = e.track.uri ?: return
        radioMode = false
        try {
            avt.call("Stop", "InstanceID" to 0)
        } catch (_: Exception) {
        }
        avt.call("SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to uri, "CurrentURIMetaData" to Didl.build(e.track))
        avt.call("Play", "InstanceID" to 0, "Speed" to "1")
        _state.update { it.copy(currentId = e.id, track = e.track) }
    }

    private fun publishQueue() = _state.update { it.copy(queueIds = queue.map(QueueEntry::id)) }

    override suspend fun play() {
        if (_state.value.transport == TransportState.STOPPED && queue.isNotEmpty() && _state.value.currentId == 0) {
            playEntry(queue.first())
        } else {
            avt.call("Play", "InstanceID" to 0, "Speed" to "1")
        }
    }

    override suspend fun pause() { avt.call("Pause", "InstanceID" to 0) }
    override suspend fun stop() { wasPlaying = false; avt.call("Stop", "InstanceID" to 0) }
    override suspend fun next() = lock.withLock { advance(1, false) }
    override suspend fun previous() = lock.withLock {
        if (_state.value.position > 3) seek(0) else advance(-1, false)
    }

    override suspend fun seek(seconds: Int) {
        avt.call("Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to formatDuration(seconds, true))
    }

    override suspend fun setVolume(volume: Int) {
        _state.update { it.copy(volume = volume) }
        rc?.call("SetVolume", "InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100))
    }

    override suspend fun volumeStep(up: Boolean) = setVolume(_state.value.volume + if (up) 2 else -2)

    override suspend fun setMute(mute: Boolean) {
        _state.update { it.copy(mute = mute) }
        rc?.call("SetMute", "InstanceID" to 0, "Channel" to "Master", "DesiredMute" to mute)
    }

    override suspend fun setShuffle(shuffle: Boolean) = _state.update { it.copy(shuffle = shuffle) }
    override suspend fun setRepeat(repeat: Boolean) = _state.update { it.copy(repeat = repeat) }
    override suspend fun setStandby(standby: Boolean) = Unit
    override suspend fun selectSource(index: Int) = Unit

    override suspend fun enqueue(tracks: List<Track>, mode: InsertMode, startAt: Int) = lock.withLock {
        val entries = tracks.filter { it.uri != null }.map { QueueEntry(nextId++, it) }
        if (entries.isEmpty()) return@withLock
        val curIdx = queue.indexOfFirst { it.id == _state.value.currentId }
        when (mode) {
            InsertMode.REPLACE -> { queue.clear(); queue.addAll(entries) }
            InsertMode.PLAY_NOW, InsertMode.ADD_NEXT -> queue.addAll((curIdx + 1).coerceIn(0, queue.size), entries)
            InsertMode.ADD_LATER -> queue.addAll(entries)
        }
        publishQueue()
        if (mode == InsertMode.PLAY_NOW || mode == InsertMode.REPLACE) {
            entries.getOrNull(startAt.coerceAtLeast(0))?.let { playEntry(it) }
        }
    }

    override suspend fun readQueue(ids: List<Int>): List<QueueEntry> = lock.withLock {
        val set = ids.toSet()
        queue.filter { it.id in set }
    }

    override suspend fun playQueueId(id: Int) = lock.withLock { queue.firstOrNull { it.id == id }?.let { playEntry(it) } ?: Unit }

    override suspend fun removeQueueId(id: Int) = lock.withLock {
        queue.removeAll { it.id == id }
        publishQueue()
    }

    override suspend fun clearQueue() = lock.withLock {
        queue.clear()
        publishQueue()
        _state.update { it.copy(currentId = 0) }
    }

    override suspend fun moveQueueEntry(entry: QueueEntry, afterId: Int): Int = lock.withLock {
        queue.removeAll { it.id == entry.id }
        val idx = if (afterId == 0) 0 else queue.indexOfFirst { it.id == afterId } + 1
        queue.add(idx.coerceIn(0, queue.size), entry)
        publishQueue()
        entry.id
    }

    override suspend fun playRadio(uri: String, metadata: Track) {
        avt.call("SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to uri, "CurrentURIMetaData" to Didl.build(metadata.copy(uri = uri, upnpClass = "object.item.audioItem.audioBroadcast")))
        avt.call("Play", "InstanceID" to 0, "Speed" to "1")
        radioMode = true
    }

    override suspend fun playReceiver(uri: String, metadata: Track) = Unit
}
