package com.lightningds.app.ui.library

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.data.TextAlign as AlignPref
import com.lightningds.app.ui.components.AlbumCard
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadingBox
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.TrackRow
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.pluralAlbums
import com.lightningds.app.ui.components.pluralSongs
import com.lightningds.app.ui.components.queueActions
import com.lightningds.core.library.Album
import com.lightningds.core.library.Library
import com.lightningds.core.library.LibraryCategory
import com.lightningds.core.library.LibraryIndex
import com.lightningds.core.library.SyncProgress
import com.lightningds.core.renderer.InsertMode

/** Extra library views beyond the core grouping categories. */
enum class LibraryTab { ALBUMS, ARTISTS, COMPOSERS, GENRES, DATE, FILE_TYPE, SAMPLING_RATE, SONGS, FOLDERS, COLLECTION, PLAYLISTS }

@Composable
fun LibraryTab.label(): String = stringResource(
    when (this) {
        LibraryTab.ALBUMS -> R.string.cat_albums
        LibraryTab.ARTISTS -> R.string.cat_artists
        LibraryTab.COMPOSERS -> R.string.cat_composers
        LibraryTab.GENRES -> R.string.cat_genres
        LibraryTab.DATE -> R.string.cat_date
        LibraryTab.FILE_TYPE -> R.string.cat_file_type
        LibraryTab.SAMPLING_RATE -> R.string.cat_sampling_rate
        LibraryTab.SONGS -> R.string.cat_songs
        LibraryTab.FOLDERS -> R.string.cat_folders
        LibraryTab.COLLECTION -> R.string.cat_collection
        LibraryTab.PLAYLISTS -> R.string.cat_playlists
    }
)

fun LibraryTab.category(): LibraryCategory? = runCatching { LibraryCategory.valueOf(name) }.getOrNull()

object Routes {
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun group(tab: LibraryTab, key: String) = "group/${tab.name}/${Uri.encode(key)}"
    fun folder(id: String, title: String) = "folder/${Uri.encode(id)}/${Uri.encode(title)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
}

@Composable
fun LibraryScreen() {
    val c = LocalController.current
    val settings by c.settings.settings.collectAsStateCompat()
    val tab = runCatching { LibraryTab.valueOf(settings.libraryCategory) }.getOrDefault(LibraryTab.ALBUMS)
    val state by c.library.collectAsStateCompat()

    Column(Modifier.fillMaxSize()) {
        ServerBar()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryTab.entries.forEach { t ->
                FilterChip(selected = t == tab, onClick = { c.settings.setLibraryCategory(t.name) }, label = { Text(t.label()) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                LibraryTab.COLLECTION -> CollectionView()
                LibraryTab.PLAYLISTS -> PlaylistsView()
                LibraryTab.FOLDERS -> if (c.contentDirectory() != null) FolderBrowser("0", null) else NoLibrary(state)
                else -> when (val s = state) {
                    is LibraryState.Ready -> LibraryContent(s.library, tab, settings.sortDescending, settings.textAlign == AlignPref.CENTER, s.updating)
                    else -> NoLibrary(s)
                }
            }
        }
    }
}

@Composable
private fun ServerBar() {
    val c = LocalController.current
    val server by c.selectedServer.collectAsStateCompat()
    val servers by c.servers.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) {
                Icon(Icons.Default.Dns, null)
                Spacer(Modifier.width(8.dp))
                Text(server?.name ?: stringResource(R.string.choose_library), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (servers.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.searching_devices)) }, onClick = { c.refreshDevices() })
                servers.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        leadingIcon = { Icon(if (s.isLightning) Icons.Default.Star else Icons.Default.Dns, null) },
                        onClick = { open = false; c.selectServer(s.udn) },
                    )
                }
            }
        }
        IconButton(onClick = { c.settings.setSortDescending(!settings.sortDescending) }) {
            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(if (settings.sortDescending) R.string.sort_za else R.string.sort_az))
        }
        IconButton(onClick = { c.syncLibrary() }, enabled = server != null) { Icon(Icons.Default.Sync, stringResource(R.string.refresh_cache)) }
    }
}

@Composable
private fun NoLibrary(state: LibraryState) {
    val c = LocalController.current
    when (state) {
        LibraryState.NoServer -> EmptyState(Icons.Default.LibraryMusic, stringResource(R.string.no_server_selected)) {
            val servers by c.servers.collectAsStateCompat()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (servers.isEmpty()) Text(stringResource(R.string.searching_devices))
                servers.forEach { s -> OutlinedButton(onClick = { c.selectServer(s.udn) }) { Text(s.name) } }
            }
        }
        LibraryState.ServerOffline -> EmptyState(Icons.Default.Dns, stringResource(R.string.server_offline)) {
            Button(onClick = { c.refreshDevices(); c.syncLibrary() }) { Text(stringResource(R.string.retry)) }
        }
        is LibraryState.Failed -> EmptyState(Icons.Default.LibraryMusic, stringResource(R.string.sync_data_fail) + "\n" + state.message) {
            Button(onClick = { c.syncLibrary() }) { Text(stringResource(R.string.retry)) }
        }
        is LibraryState.Syncing -> SyncProgressView(state.progress)
        is LibraryState.Ready -> Unit
    }
}

@Composable
fun SyncProgressView(p: SyncProgress?) {
    val c = LocalController.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sync_data_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(stringResource(R.string.sync_data_prompt), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val (fraction, label) = when (p) {
            is SyncProgress.Albums -> (if (p.total > 0) p.done.toFloat() / p.total else null) to stringResource(R.string.sync_albums, p.done, p.total)
            is SyncProgress.Tracks -> (if (p.total > 0) p.done.toFloat() / p.total else null) to stringResource(R.string.sync_tracks, p.done, p.total)
            else -> null to stringResource(R.string.sync_searching)
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { c.cancelSync() }) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun LibraryContent(library: Library, tab: LibraryTab, descending: Boolean, centered: Boolean, updating: Boolean) {
    val nav = LocalNavigator.current
    Column {
        if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (library.albums.isEmpty()) {
            EmptyState(Icons.Default.LibraryMusic, stringResource(R.string.music_library_empty_hint))
            return
        }
        when (tab) {
            LibraryTab.ALBUMS -> AlbumGrid(remember(library, descending) { LibraryIndex.sortedAlbums(library, descending) }, centered)
            LibraryTab.SONGS -> {
                val tracks = remember(library, descending) { LibraryIndex.sortedTracks(library, descending) }
                SongList(tracks)
            }
            else -> {
                val cat = tab.category() ?: return
                val groups = remember(library, cat, descending) { LibraryIndex.group(library, cat, descending) }
                LazyColumn {
                    items(groups, key = { it.key }) { g ->
                        ListItem(
                            modifier = Modifier.clickable { nav.navigate(Routes.group(tab, g.key)) },
                            leadingContent = {
                                if (tab == LibraryTab.ARTISTS || tab == LibraryTab.COMPOSERS) Artwork(g.artUri, size = 48.dp, shape = androidx.compose.foundation.shape.CircleShape, placeholder = Icons.Default.Person)
                                else Artwork(g.artUri, size = 48.dp)
                            },
                            headlineContent = { Text(g.key, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(pluralAlbums(g.albums.size)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumGrid(albums: List<Album>, centered: Boolean = false, header: (@Composable () -> Unit)? = null) {
    val nav = LocalNavigator.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.id + "\u0000" + it.title }) { a ->
            AlbumCard(a.title, a.displayArtist, a.artUri, onClick = { nav.navigate(Routes.album(a.id)) }, centered = centered)
        }
    }
}

@Composable
private fun SongList(tracks: List<com.lightningds.core.media.Track>) {
    val c = LocalController.current
    LazyColumn {
        itemsIndexed(tracks, key = { i, t -> t.id + i }) { i, t ->
            TrackRow(
                t, onClick = { c.play(listOf(t), InsertMode.PLAY_NOW) }, showArt = true,
                menu = queueActions({ listOf(t) }),
            )
        }
    }
}

@Composable
fun GroupScreen(tab: LibraryTab, key: String) {
    val c = LocalController.current
    val state by c.library.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    val lib = (state as? LibraryState.Ready)?.library ?: return LoadingBox()
    val cat = tab.category() ?: return
    val group = remember(lib, cat, key) { LibraryIndex.group(lib, cat).firstOrNull { it.key == key } }
    val albums = group?.albums.orEmpty()
    AlbumGrid(albums, settings.textAlign == AlignPref.CENTER, header = {
        Column(Modifier.padding(6.dp)) {
            Text(key, style = MaterialTheme.typography.headlineSmall)
            Text(pluralAlbums(albums.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            com.lightningds.app.ui.components.PlayButtons(albums.flatMap { it.tracks })
        }
    })
}

@Composable
private fun CollectionView() {
    val c = LocalController.current
    val albums by c.collection.albums.collectAsStateCompat()
    if (albums.isEmpty()) EmptyState(Icons.Default.Star, stringResource(R.string.collection_hint_empty))
    else AlbumGrid(albums.sortedBy { it.title.lowercase() })
}

@Composable
private fun PlaylistsView() {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val lists by c.playlists.playlists.collectAsStateCompat()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(stringResource(R.string.new_playlist), label = stringResource(R.string.playlist_name), onDismiss = { creating = false }, onConfirm = {
            c.playlists.create(it)
            creating = false
        })
    }
    LazyColumn {
        item {
            ListItem(
                modifier = Modifier.clickable { creating = true },
                leadingContent = { Icon(Icons.Default.Add, null) },
                headlineContent = { Text(stringResource(R.string.new_playlist)) },
            )
        }
        if (lists.isEmpty()) item { Text(stringResource(R.string.playlist_hint_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(lists, key = { it.id }) { p ->
            ListItem(
                modifier = Modifier.clickable { nav.navigate(Routes.playlist(p.id)) },
                leadingContent = { Artwork(p.tracks.firstNotNullOfOrNull { it.albumArtUri }, size = 48.dp, placeholder = Icons.AutoMirrored.Filled.PlaylistPlay) },
                headlineContent = { Text(p.name) },
                supportingContent = { Text(pluralSongs(p.tracks.size)) },
            )
        }
    }
}

