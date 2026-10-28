package com.lightningds.core.renderer

import com.lightningds.core.media.Track
import kotlinx.coroutines.flow.StateFlow

enum class TransportState { PLAYING, PAUSED, STOPPED, BUFFERING, UNKNOWN;

    companion object {
        fun from(s: String?): TransportState = when (s?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED", "PAUSED_PLAYBACK" -> PAUSED
            "STOPPED", "NO_MEDIA_PRESENT" -> STOPPED
            "BUFFERING", "TRANSITIONING", "WAITING" -> BUFFERING
            else -> UNKNOWN
        }
    }
}

/** A renderer input as reported by the OpenHome Product service. */
data class RendererSource(val index: Int, val name: String, val type: String, val visible: Boolean) {
    companion object {
        const val PLAYLIST = "Playlist"
        const val RADIO = "Radio"
        const val INTERNET_RADIO = "InternetRadio"
        const val RECEIVER = "Receiver"
        const val AIRPLAY = "AirPlay"
        const val UPNP = "UpnpAv"
        const val STREAMING = "Streaming"
    }

    val isRadio: Boolean get() = type == RADIO || type == INTERNET_RADIO
    val isPlaylist: Boolean get() = type == PLAYLIST || type == STREAMING
    val isReceiver: Boolean get() = type.equals(RECEIVER, true) || type.equals("Songcast", true)
    val isAirPlay: Boolean get() = type.equals(AIRPLAY, true) || type.equals("Net Aux", true) || name.contains("AirPlay", true)
}

data class StreamDetails(
    val duration: Int = 0,
    val bitRate: Int = 0,
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val lossless: Boolean = false,
    val codecName: String = "",
)

data class QueueEntry(val id: Int, val track: Track)

data class RendererState(
    val connected: Boolean = false,
    val name: String = "",
    val standby: Boolean = false,
    val sources: List<RendererSource> = emptyList(),
    val sourceIndex: Int = -1,
    val transport: TransportState = TransportState.UNKNOWN,
    val track: Track? = null,
    val trackUri: String? = null,
    val details: StreamDetails = StreamDetails(),
    val metatext: String? = null,
    val position: Int = 0,
    val duration: Int = 0,
    val volume: Int = 0,
    val volumeMax: Int = 100,
    val mute: Boolean = false,
    val volumeAvailable: Boolean = true,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val queueIds: List<Int> = emptyList(),
    val currentId: Int = 0,
    val queueSupported: Boolean = true,
    val radioUri: String? = null,
    val radioTrack: Track? = null,
    val radioTransport: TransportState = TransportState.UNKNOWN,
    val receiverTransport: TransportState = TransportState.UNKNOWN,
    val receiverSender: Track? = null,
    val error: String? = null,
) {
    val currentSource: RendererSource? get() = sources.firstOrNull { it.index == sourceIndex }
    val isPlaying: Boolean get() = effectiveTransport == TransportState.PLAYING
    val effectiveTransport: TransportState
        get() = when {
            currentSource?.isRadio == true -> radioTransport
            currentSource?.isReceiver == true -> receiverTransport
            else -> transport
        }
    val currentIndex: Int get() = queueIds.indexOf(currentId)
}

enum class InsertMode { PLAY_NOW, ADD_NEXT, ADD_LATER, REPLACE }

interface Renderer {
    val udn: String
    val state: StateFlow<RendererState>

    fun start()
    fun close()

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun next()
    suspend fun previous()
    suspend fun seek(seconds: Int)
    suspend fun setVolume(volume: Int)
    suspend fun volumeStep(up: Boolean)
    suspend fun setMute(mute: Boolean)
    suspend fun setShuffle(shuffle: Boolean)
    suspend fun setRepeat(repeat: Boolean)
    suspend fun setStandby(standby: Boolean)
    suspend fun selectSource(index: Int)

    /** Adds [tracks] to the queue. [startAt] = index in [tracks] to start playing (or -1). */
    suspend fun enqueue(tracks: List<Track>, mode: InsertMode, startAt: Int = 0)
    suspend fun readQueue(ids: List<Int>): List<QueueEntry>
    suspend fun playQueueId(id: Int)
    suspend fun removeQueueId(id: Int)
    suspend fun clearQueue()
    /** Moves entry [id] to be after [afterId] (0 = first). Returns the new id. */
    suspend fun moveQueueEntry(entry: QueueEntry, afterId: Int): Int

    suspend fun playRadio(uri: String, metadata: Track)
    suspend fun playReceiver(uri: String, metadata: Track)
}
