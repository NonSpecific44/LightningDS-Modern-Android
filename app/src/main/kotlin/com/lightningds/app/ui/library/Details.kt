package com.lightningds.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LoadingBox
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.MenuAction
import com.lightningds.app.ui.components.PlayButtons
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.TrackRow
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.queueActions
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.core.library.BrowsePage
import com.lightningds.core.media.Track
import com.lightningds.core.renderer.InsertMode

/**
 * Generic "header + track list" layout. On wide screens the artwork and header sit in a column
 * to the left of the track list (tablet layout); on phones the header scrolls with the list.
 */
@Composable
fun DetailLayout(
    art: String?,
    title: String,
    subtitle: String?,
    info: String?,
    tracks: List<Track>,
    headerActions: @Composable () -> Unit = {},
    placeholder: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.MusicNote,
    list: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val header: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(16.dp), horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                Artwork(art, Modifier.widthIn(max = if (wide) 360.dp else 260.dp).fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(16.dp), placeholder = placeholder)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (!info.isNullOrBlank()) Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PlayButtons(tracks)
                Row(verticalAlignment = Alignment.CenterVertically) { headerActions() }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(maxWidth * 0.4f).verticalScroll(rememberScrollState())) { header(Modifier) }
                LazyColumn(Modifier.weight(1f)) { list() }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item { header(Modifier.fillMaxWidth()) }
                list()
            }
        }
    }
}

@Composable
fun minutes(tracks: List<Track>): String = ((tracks.sumOf { it.durationSeconds } + 59) / 60).toString()

@Composable
fun AlbumScreen(albumId: String) {
    val c = LocalController.current
    val state by c.library.collectAsStateCompat()
    val collection by c.collection.albums.collectAsStateCompat()
    val lib = (state as? LibraryState.Ready)?.library
    val album = lib?.albums?.firstOrNull { it.id == albumId } ?: collection.firstOrNull { it.id == albumId }
    if (album == null) {
        if (state is LibraryState.Ready) EmptyState(Icons.Default.MusicNote, stringResource(R.string.unknown)) else LoadingBox()
        return
    }
    val starred = collection.any { it.id == album.id && it.title == album.title }
    val tracks = album.tracks
    val multiDisc = tracks.map { it.discNumber }.distinct().size > 1
    DetailLayout(
        art = album.artUri,
        title = album.title,
        subtitle = album.displayArtist,
        info = listOfNotNull(album.year, album.genre, stringResource(R.string.album_info, com.lightningds.app.ui.components.pluralSongs(tracks.size), minutes(tracks))).joinToString(" · "),
        tracks = tracks,
        headerActions = {
            IconButton(onClick = { c.collection.toggle(album) }) {
                Icon(
                    if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                    stringResource(if (starred) R.string.remove_from_collection else R.string.add_to_collection),
                    tint = if (starred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.lightningds.app.ui.components.OverflowMenu(queueActions({ tracks }))
        },
    ) {
        itemsIndexed(tracks, key = { i, t -> t.id + i }) { i, t ->
            if (multiDisc && (i == 0 || tracks[i - 1].discNumber != t.discNumber)) {
                Text("CD ${t.discNumber}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            }
            val artistsDiffer = t.artist != null && t.artist != album.artist
            TrackRow(
                t,
                onClick = { c.play(tracks, InsertMode.REPLACE, i) },
                index = if (t.trackNumber > 0) t.trackNumber else i + 1,
                subtitle = listOfNotNull(t.artist.takeIf { artistsDiffer }, t.qualityLabel).joinToString(" · "),
                menu = queueActions({ listOf(t) }),
            )
        }
    }
}

/** Raw ContentDirectory browsing ("Folders" view), like the original FolderViewActivity. */
@Composable
fun FolderBrowser(objectId: String, title: String?) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val loader = rememberLoad(objectId, c.settings.current.serverUdn) {
        val cd = c.contentDirectory() ?: error(c.string(R.string.server_offline))
        cd.browseAll(objectId)
    }
    LoadContent(loader) { page: BrowsePage ->
        if (page.containers.isEmpty() && page.items.isEmpty()) {
            EmptyState(Icons.Default.Folder, stringResource(R.string.folder_empty))
            return@LoadContent
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (title != null || page.items.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (page.items.isNotEmpty()) Button(onClick = { c.play(page.items, InsertMode.REPLACE) }) { Text(stringResource(R.string.play_folder)) }
                    }
                }
            }
            items(page.containers, key = { "c" + it.id }) { f ->
                ListItem(
                    modifier = Modifier.clickable { nav.navigate(Routes.folder(f.id, f.title)) },
                    leadingContent = { Artwork(f.albumArtUri, size = 48.dp, placeholder = Icons.Default.Folder) },
                    headlineContent = { Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = f.artist?.let { { Text(it, maxLines = 1) } },
                )
            }
            itemsIndexed(page.items, key = { i, t -> "i" + t.id + i }) { i, t ->
                TrackRow(t, onClick = { c.play(page.items, InsertMode.REPLACE, i) }, showArt = true, menu = queueActions({ listOf(t) }))
            }
        }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val lists by c.playlists.playlists.collectAsStateCompat()
    val p = lists.firstOrNull { it.id == id } ?: return EmptyState(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.playlist_hint_empty))
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (renaming) TextInputDialog(stringResource(R.string.rename), initial = p.name, onDismiss = { renaming = false }, onConfirm = { c.playlists.rename(p.id, it); renaming = false })
    if (deleting) ConfirmDialog(stringResource(R.string.delete_playlist), stringResource(R.string.delete_playlist_confirm, p.name), onDismiss = { deleting = false }, onConfirm = {
        c.playlists.delete(p.id)
        nav.back()
    }, confirmLabel = stringResource(R.string.delete))
    DetailLayout(
        art = p.tracks.firstNotNullOfOrNull { it.albumArtUri },
        title = p.name,
        subtitle = null,
        info = stringResource(R.string.tracks_duration, p.tracks.size, minutes(p.tracks)),
        tracks = p.tracks,
        placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
        headerActions = {
            IconButton(onClick = { editing = !editing }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, stringResource(R.string.rename), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete_playlist)) }
        },
    ) {
        itemsIndexed(p.tracks, key = { i, t -> t.id + i }) { i, t ->
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(t, onClick = {}, modifier = Modifier.weight(1f), index = i + 1)
                    IconButton(enabled = i > 0, onClick = { c.playlists.moveTrack(p.id, i, i - 1) }) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up)) }
                    IconButton(enabled = i < p.tracks.lastIndex, onClick = { c.playlists.moveTrack(p.id, i, i + 1) }) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down)) }
                    IconButton(onClick = { c.playlists.removeTrack(p.id, i) }) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
                }
            } else {
                TrackRow(
                    t, onClick = { c.play(p.tracks, InsertMode.REPLACE, i) }, showArt = true,
                    menu = queueActions({ listOf(t) }, extra = listOf(MenuAction(stringResource(R.string.remove), Icons.Default.Delete) { c.playlists.removeTrack(p.id, i) })),
                )
            }
        }
    }
}
