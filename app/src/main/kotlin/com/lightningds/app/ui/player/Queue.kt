package com.lightningds.app.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.MenuAction
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.TrackRow
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.core.renderer.QueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The renderer's play queue: tap to play, edit order, remove, clear or save as playlist. */
@Composable
fun QueueContent(modifier: Modifier = Modifier, showHeader: Boolean = true) {
    val c = LocalController.current
    val state by c.rendererState.collectAsStateCompat()
    val renderer by c.renderer.collectAsStateCompat()
    val s = state
    var entries by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val ids = s?.queueIds.orEmpty()
    LaunchedEffect(renderer, ids) {
        val r = renderer ?: return@LaunchedEffect
        // Reuse already-read entries; only fetch the ids we haven't seen.
        val known = entries.associateBy { it.id }
        val missing = ids.filter { it !in known }
        val fetched = if (missing.isEmpty()) emptyList() else runCatching { withContext(Dispatchers.IO) { r.readQueue(missing) } }.getOrDefault(emptyList())
        val all = known + fetched.associateBy { it.id }
        entries = ids.mapNotNull { all[it] }
    }

    if (confirmClear) {
        ConfirmDialog(stringResource(R.string.clear_queue), stringResource(R.string.clear_queue_confirm), onDismiss = { confirmClear = false }, onConfirm = { c.command { clearQueue() } })
    }
    if (saving) {
        TextInputDialog(stringResource(R.string.save_queue_as_playlist), label = stringResource(R.string.playlist_name), onDismiss = { saving = false }, onConfirm = { name ->
            c.playlists.create(name, entries.map { it.track })
            saving = false
        })
    }

    Column(modifier) {
        if (showHeader) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleLarge)
                    if (entries.isNotEmpty()) {
                        Text(pluralStringResource(R.plurals.songs_count, entries.size, entries.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { editing = !editing }) { Text(stringResource(if (editing) R.string.done else R.string.edit)) }
                    IconButton(onClick = { saving = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.save_queue_as_playlist)) }
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.ClearAll, stringResource(R.string.clear_queue)) }
                }
            }
        }
        if (s == null) {
            EmptyState(Icons.Default.QueueMusic, stringResource(R.string.no_device_selected))
            return@Column
        }
        if (ids.isEmpty()) {
            EmptyState(Icons.Default.QueueMusic, stringResource(R.string.queue_empty_hint))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                val playing = e.id == s.currentId
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackRow(e.track, onClick = {}, modifier = Modifier.weight(1f), index = i + 1, playing = playing)
                        IconButton(enabled = i > 0, onClick = {
                            val after = if (i >= 2) entries[i - 2].id else 0
                            c.command { moveQueueEntry(e, after) }
                        }) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up)) }
                        IconButton(enabled = i < entries.lastIndex, onClick = {
                            val after = entries[i + 1].id
                            c.command { moveQueueEntry(e, after) }
                        }) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down)) }
                        IconButton(onClick = { c.command { removeQueueId(e.id) } }) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
                    }
                } else {
                    TrackRow(
                        e.track,
                        onClick = { c.command { playQueueId(e.id) } },
                        index = i + 1,
                        playing = playing,
                        menu = listOf(
                            MenuAction(stringResource(R.string.play), null) { c.command { playQueueId(e.id) } },
                            MenuAction(stringResource(R.string.remove), Icons.Default.Delete) { c.command { removeQueueId(e.id) } },
                        ),
                    )
                }
            }
        }
    }
}
