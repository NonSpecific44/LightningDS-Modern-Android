package com.lightningds.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lightningds.core.renderer.Renderer
import com.lightningds.core.renderer.RendererState
import com.lightningds.core.renderer.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Publishes the network player's state as an Android media session, so Now Playing shows on the
 * lock screen, in the notification drawer and on connected devices, with working controls.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val controller = (application as LightningApp).controller
        val player = RemotePlayer(mainLooper, controller)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val built = MediaSession.Builder(this, player).setSessionActivity(open).build()
        session = built
        // Register the session straight away so its notification is shown even before any
        // controller connects (on some phones, e.g. Pixels, nothing else binds to the service).
        addSession(built)
        // Only while the player can actually be reached: leaving its network (or it going
        // offline) removes the lock screen / notification player straight away.
        scope.launch {
            kotlinx.coroutines.flow.combine(controller.rendererState, controller.connection) { s, conn ->
                s?.takeIf { conn == ConnectionStatus.CONNECTED && it.connected }
            }.collect { player.update(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while nothing plays removes the notification too.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /** Started while the app is on screen (starting a service from the background isn't allowed). */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }
    }
}

/**
 * A media3 [Player] that mirrors the selected network renderer and forwards commands to it. The
 * playlist is [previous, current, next] placeholders so the system shows working skip buttons.
 */
@OptIn(UnstableApi::class)
private class RemotePlayer(looper: Looper, private val controller: AppController) : SimpleBasePlayer(looper) {
    private var current: RendererState? = null

    fun update(state: RendererState?) {
        current = state
        invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State {
        val idle = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(Player.Commands.EMPTY)
            .setPlaybackState(Player.STATE_IDLE)
            .build()
        val s = current ?: return idle
        if (s.standby) return idle
        val src = s.currentSource
        val streamOnly = src != null && (src.isRadio || src.isReceiver || src.isAirPlay)
        val radio = src != null && src.isRadio
        val track = if (radio) s.radioTrack ?: s.track else s.track
        val title: String = when {
            radio -> s.radioTrack?.title ?: s.track?.title ?: src?.name
            else -> track?.title?.ifBlank { null } ?: (if (streamOnly) src?.name else null)
        } ?: return idle
        val artist = if (radio) s.metatext ?: s.radioTrack?.artist else track?.displayArtist?.ifBlank { null }
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(if (radio) null else track?.album)
            .setStation(if (radio) src?.name else null)
            .setArtworkUri(track?.albumArtUri?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { Uri.parse(it) })
            .setIsPlayable(true)
            .build()
        val seekable = !streamOnly && s.duration > 0
        val key = "${s.trackUri ?: track?.uri}|${track?.id}|$title"
        val item = SimpleBasePlayer.MediaItemData.Builder("cur:$key")
            .setMediaItem(MediaItem.Builder().setMediaId(key).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .setDurationUs(if (seekable) s.duration * 1_000_000L else C.TIME_UNSET)
            .setIsSeekable(seekable)
            .setIsDynamic(streamOnly)
            .build()
        val queue = hasQueue(s)
        val playlist = if (queue) listOf(placeholder("prev"), item, placeholder("next")) else listOf(item)

        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
        )
        if (seekable) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (queue) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }

        val transport = s.effectiveTransport
        val playing = transport == TransportState.PLAYING || transport == TransportState.BUFFERING
        val positionMs = s.position * 1000L
        val builder = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (queue) 1 else 0)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (transport == TransportState.BUFFERING) Player.STATE_BUFFERING else Player.STATE_READY)
            .setIsLoading(transport == TransportState.BUFFERING)
        if (seekable && transport == TransportState.PLAYING) {
            builder.setContentPositionMs(SimpleBasePlayer.PositionSupplier.getExtrapolating(positionMs, 1f))
        } else {
            builder.setContentPositionMs(if (seekable) positionMs else 0L)
        }
        return builder.build()
    }

    private fun hasQueue(s: RendererState): Boolean {
        val src = s.currentSource
        val streamOnly = src != null && (src.isRadio || src.isReceiver || src.isAirPlay)
        return !streamOnly && s.queueSupported
    }

    private fun placeholder(id: String) = SimpleBasePlayer.MediaItemData.Builder(id)
        .setMediaItem(MediaItem.Builder().setMediaId(id).build())
        .build()

    private fun send(block: suspend Renderer.() -> Unit): ListenableFuture<*> {
        val done = SettableFuture.create<Any?>()
        controller.command(onDone = { done.set(null) }, block = block)
        return done
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        send { if (playWhenReady) play() else pause() }

    override fun handleStop(): ListenableFuture<*> = send { stop() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val queue = current?.let { hasQueue(it) } == true
        return when {
            queue && mediaItemIndex >= 2 -> send { next() }
            queue && mediaItemIndex <= 0 -> send { previous() }
            // "Previous" late in a track restarts it, as on other players.
            else -> send { seek(if (positionMs == C.TIME_UNSET) 0 else (positionMs / 1000).toInt()) }
        }
    }
}
