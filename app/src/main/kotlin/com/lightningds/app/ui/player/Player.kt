package com.lightningds.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Input
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.core.media.formatDuration
import com.lightningds.core.media.formatRate
import com.lightningds.core.renderer.RendererState
import com.lightningds.core.renderer.TransportState

/** What to show for the current item, whatever the active source is. */
data class NowPlayingInfo(
    val title: String,
    val subtitle: String,
    val art: String?,
    val icon: ImageVector,
    val seekable: Boolean,
    val sourceLabel: String?,
)

@Composable
fun RendererState.info(): NowPlayingInfo {
    val src = currentSource
    val track = track
    return when {
        src?.isRadio == true -> NowPlayingInfo(
            title = radioTrack?.title ?: track?.title ?: stringResource(R.string.playing_radio),
            subtitle = metatext ?: radioTrack?.artist ?: "",
            art = radioTrack?.albumArtUri ?: track?.albumArtUri,
            icon = Icons.Default.Radio,
            seekable = false,
            sourceLabel = src.name,
        )
        src?.isReceiver == true -> NowPlayingInfo(
            title = track?.title ?: receiverSender?.title ?: stringResource(R.string.playing_receiver),
            subtitle = track?.displayArtist ?: "",
            art = track?.albumArtUri ?: receiverSender?.albumArtUri,
            icon = Icons.Default.Input,
            seekable = false,
            sourceLabel = src.name,
        )
        src?.isAirPlay == true -> NowPlayingInfo(
            title = track?.title?.ifBlank { null } ?: stringResource(R.string.playing_airplay),
            subtitle = track?.displayArtist ?: "",
            art = track?.albumArtUri,
            icon = Icons.Default.Airplay,
            seekable = false,
            sourceLabel = src.name,
        )
        track != null -> NowPlayingInfo(
            title = track.title,
            subtitle = listOfNotNull(track.displayArtist.ifBlank { null }, track.album).joinToString(" — "),
            art = track.albumArtUri,
            icon = Icons.Default.MusicNote,
            seekable = duration > 0,
            sourceLabel = src?.name,
        )
        else -> NowPlayingInfo(stringResource(R.string.nothing_playing), "", null, Icons.Default.MusicNote, false, src?.name)
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalController.current
    val state by c.rendererState.collectAsStateCompat()
    val s = state ?: return
    val info = s.info()
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            if (s.duration > 0 && info.seekable) {
                LinearProgressIndicator(
                    progress = { (s.position.toFloat() / s.duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(info.art, size = 44.dp, placeholder = info.icon)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(info.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.subtitle.isNotBlank()) {
                        Text(info.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlayPauseButton(s, small = true)
                if (s.queueSupported && s.currentSource?.isRadio != true) {
                    IconButton(onClick = { c.command { next() } }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next_track)) }
                }
            }
        }
    }
}

@Composable
fun PlayPauseButton(s: RendererState, small: Boolean = false) {
    val c = LocalController.current
    val playing = s.effectiveTransport == TransportState.PLAYING || s.effectiveTransport == TransportState.BUFFERING
    val streamOnly = s.currentSource?.let { it.isRadio || it.isReceiver } == true
    val icon = when {
        playing && streamOnly -> Icons.Default.Stop
        playing -> Icons.Default.Pause
        else -> Icons.Default.PlayArrow
    }
    val label = stringResource(if (playing) R.string.pause else R.string.play)
    val action = { c.command { if (playing) pause() else play() } }
    if (small) {
        IconButton(onClick = action) { Icon(icon, label) }
    } else {
        FilledIconButton(onClick = action, modifier = Modifier.size(72.dp)) { Icon(icon, label, Modifier.size(40.dp)) }
    }
}

/** Full player. [compactQueueButton] shows a queue shortcut (phones); tablets show the queue alongside. */
@Composable
fun NowPlayingContent(onOpenQueue: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = LocalController.current
    val state by c.rendererState.collectAsStateCompat()
    val device by c.selectedRenderer.collectAsStateCompat()
    val s = state
    if (s == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_device_selected), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val info = s.info()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            info.art,
            Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(20.dp),
            placeholder = info.icon,
        )
        Spacer(Modifier.height(20.dp))
        Text(info.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (info.subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(info.subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        QualityChips(s)

        if (info.seekable) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val pos = if (dragging) dragValue else s.position.toFloat()
            Slider(
                value = pos.coerceIn(0f, s.duration.toFloat().coerceAtLeast(1f)),
                valueRange = 0f..s.duration.toFloat().coerceAtLeast(1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    dragging = false
                    val target = dragValue.toInt()
                    c.command { seek(target) }
                },
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            )
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(formatDuration(pos.toInt()), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(s.duration), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }

        val queueControls = s.currentSource?.let { it.isRadio || it.isReceiver || it.isAirPlay } != true
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queueControls) {
                IconToggleButton(checked = s.shuffle, onCheckedChange = { v -> c.command { setShuffle(v) } }) {
                    Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle), tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { c.command { previous() } }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous_track), Modifier.size(36.dp))
                }
            }
            PlayPauseButton(s)
            if (queueControls) {
                IconButton(onClick = { c.command { next() } }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.next_track), Modifier.size(36.dp))
                }
                IconToggleButton(checked = s.repeat, onCheckedChange = { v -> c.command { setRepeat(v) } }) {
                    Icon(Icons.Default.Repeat, stringResource(R.string.repeat), tint = if (s.repeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        VolumeControl(s, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceSelector(s)
            if (device?.isOpenHome == true) {
                AssistChip(
                    onClick = { c.command { setStandby(!s.standby) } },
                    label = { Text(stringResource(if (s.standby) R.string.wake_up else R.string.standby)) },
                    leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp)) },
                )
            }
            if (onOpenQueue != null) {
                AssistChip(
                    onClick = onOpenQueue,
                    label = { Text(stringResource(R.string.queue)) },
                    leadingIcon = { Icon(Icons.Default.QueueMusic, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun QualityChips(s: RendererState) {
    val d = s.details
    val chips = buildList {
        d.codecName.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        if (d.bitDepth > 0 && d.sampleRate > 0) add("${d.bitDepth}bit / ${formatRate(d.sampleRate)}")
        else if (d.sampleRate > 0) add(formatRate(d.sampleRate))
        if (d.bitRate > 0) add("${d.bitRate / 1000} kbps")
    }
    if (chips.isEmpty() && !d.lossless) return
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { SuggestionChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
        if (d.lossless) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.lossless), style = MaterialTheme.typography.labelSmall) })
    }
}

@Composable
fun VolumeControl(s: RendererState, modifier: Modifier = Modifier) {
    val c = LocalController.current
    if (!s.volumeAvailable) {
        Text(stringResource(R.string.fixed_volume), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, textAlign = TextAlign.Center)
        return
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var lastSent by remember { mutableStateOf(-1) }
    val shown = if (dragging) dragValue else s.volume.toFloat()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.command { setMute(!s.mute) } }) {
            Icon(if (s.mute) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.mute))
        }
        Slider(
            value = shown.coerceIn(0f, s.volumeMax.toFloat()),
            valueRange = 0f..s.volumeMax.toFloat().coerceAtLeast(1f),
            onValueChange = { v ->
                dragging = true
                dragValue = v
                val target = v.toInt()
                if (target != lastSent) {
                    lastSent = target
                    c.command { setVolume(target) }
                }
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f),
        )
        Text(shown.toInt().toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 32.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun SourceSelector(s: RendererState) {
    val c = LocalController.current
    if (s.sources.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(s.currentSource?.name ?: stringResource(R.string.source)) },
            leadingIcon = { Icon(Icons.Default.Input, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.sources.filter { it.visible }.forEach { src ->
                DropdownMenuItem(
                    text = { Text(src.name) },
                    onClick = { open = false; c.command { selectSource(src.index) } },
                    leadingIcon = if (src.index == s.sourceIndex) ({ Icon(Icons.Default.PlayArrow, null) }) else null,
                )
            }
        }
    }
}
