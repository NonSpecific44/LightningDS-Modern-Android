package com.lightningds.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadingBox
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.core.library.Album
import com.lightningds.core.library.AlbumEdit
import com.lightningds.core.library.LibraryIndex
import com.lightningds.core.library.MetadataEditor
import com.lightningds.core.library.TrackEdit
import com.lightningds.core.metadata.ReleaseCandidate
import com.lightningds.core.metadata.ReleaseMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EditRoutes {
    fun album(id: String) = "edit/album/${Uri.encode(id)}"
    const val MISSING = "library/missing"
}

private class TrackForm(title: String, artist: String, number: String) {
    var title by mutableStateOf(title)
    var artist by mutableStateOf(artist)
    var number by mutableStateOf(number)
}

/** "" means "same as the server"; otherwise the value differs and becomes an edit. */
private fun diff(value: String, original: String?): String? = value.trim().takeIf { it.isNotEmpty() && it != original.orEmpty() }

@Composable
fun EditAlbumScreen(albumId: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val state by c.library.collectAsStateCompat()
    val lib = (state as? LibraryState.Ready)?.library
    val original = c.originalAlbum(albumId)
    val current = lib?.albums?.firstOrNull { it.id == albumId }
    if (lib == null) return LoadingBox()
    if (original == null || current == null) return EmptyState(Icons.Default.AutoFixHigh, stringResource(R.string.unknown))

    val scope = rememberCoroutineScope()
    var title by remember(albumId) { mutableStateOf(current.title.takeUnless { it.equals(LibraryIndex.UNKNOWN, true) }.orEmpty()) }
    var artist by remember(albumId) { mutableStateOf(current.artist.orEmpty()) }
    var date by remember(albumId) { mutableStateOf(current.date.orEmpty()) }
    var genre by remember(albumId) { mutableStateOf(current.genre.orEmpty()) }
    var art by remember(albumId) { mutableStateOf(current.artUri) }
    var mbid by remember(albumId) { mutableStateOf(c.metadata.forServer(lib.serverUdn).albums[albumId]?.musicBrainzId) }
    val forms = remember(albumId) {
        mutableStateMapOf<String, TrackForm>().apply {
            current.tracks.forEach { t -> put(t.id, TrackForm(t.title, t.artist.orEmpty(), if (t.trackNumber > 0) t.trackNumber.toString() else "")) }
        }
    }
    var searching by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val filledMsg = stringResource(R.string.metadata_filled)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { c.importCover(uri) }.onSuccess { art = it }.onFailure { c.toast(it.message ?: "") }
        }
    }

    fun save() {
        val albumEdit = AlbumEdit(
            title = diff(title, original.title),
            artist = diff(artist, original.artist),
            date = diff(date, original.date),
            genre = diff(genre, original.genre),
            artUri = art?.takeIf { it != original.artUri },
            musicBrainzId = mbid,
        )
        val trackEdits = original.tracks.associate { t ->
            val f = forms[t.id]
            t.id to f?.let {
                TrackEdit(
                    title = diff(it.title, t.title),
                    artist = diff(it.artist, t.artist),
                    trackNumber = it.number.trim().toIntOrNull()?.takeIf { n -> n != t.trackNumber },
                )
            }
        }
        // An edit with only the MusicBrainz id and no changes is dropped.
        c.saveAlbumEdits(albumId, albumEdit.takeIf { it.copy(musicBrainzId = null) != AlbumEdit() }, trackEdits)
        nav.back()
    }

    if (searching) {
        FindOnlineDialog(
            initialAlbum = title.ifBlank { null },
            initialArtist = artist.ifBlank { current.tracks.firstNotNullOfOrNull { it.artist } },
            initialTrack = current.tracks.firstOrNull()?.title,
            onDismiss = { searching = false },
            onPick = { candidate ->
                searching = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val rel = c.musicBrainz.release(candidate.id)
                            val cover = c.musicBrainz.findCover(rel.id, rel.releaseGroupId)
                            ReleaseMatcher.match(original, rel, cover)
                        }
                    }.onSuccess { m ->
                        m.album.title?.let { title = it }
                        m.album.artist?.let { artist = it }
                        m.album.date?.let { date = it }
                        m.album.genre?.let { genre = it }
                        m.album.artUri?.let { art = it }
                        mbid = m.album.musicBrainzId
                        for ((id, e) in m.tracks) {
                            val f = forms[id] ?: continue
                            e.title?.let { f.title = it }
                            e.artist?.let { f.artist = it }
                            e.trackNumber?.let { f.number = it.toString() }
                        }
                        c.toast(filledMsg)
                    }.onFailure { c.toast(it.message ?: c.string(R.string.operation_failed)) }
                }
            },
        )
    }
    if (urlDialog) {
        TextInputDialog(stringResource(R.string.cover_image_url), initial = art?.takeIf { it.startsWith("http") }.orEmpty(), label = "https://…",
            onDismiss = { urlDialog = false }, onConfirm = { art = it.trim(); urlDialog = false })
    }
    if (confirmReset) {
        ConfirmDialog(stringResource(R.string.metadata_reset), stringResource(R.string.metadata_reset_confirm), { confirmReset = false }, {
            c.saveAlbumEdits(albumId, null, original.tracks.associate { it.id to null })
            nav.back()
        })
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Artwork(art, Modifier.width(140.dp).aspectRatio(1f), shape = RoundedCornerShape(12.dp))
                    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.metadata_find_online))
                        }
                        FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cover_choose_image))
                        }
                        OutlinedButton(onClick = { urlDialog = true }) {
                            Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cover_image_url))
                        }
                        if (art != original.artUri) TextButton(onClick = { art = original.artUri }) { Text(stringResource(R.string.cover_use_server)) }
                    }
                }
                if (art?.startsWith("file:") == true) {
                    Text(stringResource(R.string.cover_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Field(stringResource(R.string.album_title), title, original.title) { title = it }
                Field(stringResource(R.string.album_artist), artist, original.artist) { artist = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.cat_date), date, original.date, Modifier.weight(1f)) { date = it }
                    Field(stringResource(R.string.cat_genres), genre, original.genre, Modifier.weight(1f)) { genre = it }
                }
                Text(stringResource(R.string.metadata_stored_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = { nav.back() }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { confirmReset = true }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.metadata_reset))
                    }
                }
                SectionHeader(stringResource(R.string.tracks), Modifier.padding(start = 0.dp))
            }
        }
        items(current.tracks, key = { it.id }) { t ->
            val f = forms[t.id] ?: return@items
            val orig = original.tracks.firstOrNull { it.id == t.id }
            Row(Modifier.widthIn(max = 720.dp).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    f.number, { v -> if (v.length <= 3 && v.all(Char::isDigit)) f.number = v },
                    label = { Text("#") }, singleLine = true, modifier = Modifier.width(64.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(f.title, { f.title = it }, label = { Text(stringResource(R.string.track_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = orig?.title?.let { { Text(it) } })
                    OutlinedTextField(f.artist, { f.artist = it }, label = { Text(stringResource(R.string.track_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = orig?.artist?.let { { Text(it) } })
                }
            }
            HorizontalDivider(Modifier.widthIn(max = 720.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, original: String?, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier.padding(top = 4.dp),
        placeholder = original?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        supportingText = if (original != null && value.trim() != original && value.isNotBlank()) ({ Text(stringResource(R.string.metadata_server_value, original.ifBlank { "—" }), maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
    )
}

@Composable
private fun FindOnlineDialog(
    initialAlbum: String?,
    initialArtist: String?,
    initialTrack: String?,
    onDismiss: () -> Unit,
    onPick: (ReleaseCandidate) -> Unit,
) {
    val c = LocalController.current
    val scope = rememberCoroutineScope()
    var byTrack by remember { mutableStateOf(initialAlbum == null && initialTrack != null) }
    var album by remember { mutableStateOf(initialAlbum.orEmpty()) }
    var track by remember { mutableStateOf(initialTrack.orEmpty()) }
    var artist by remember { mutableStateOf(initialArtist.orEmpty()) }
    var results by remember { mutableStateOf<List<ReleaseCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        busy = true; error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (byTrack) c.musicBrainz.searchByTrack(track, artist.ifBlank { null })
                    else c.musicBrainz.searchReleases(album, artist.ifBlank { null })
                }
            }.onSuccess { results = it }.onFailure { error = it.message }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.metadata_find_online)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byTrack, onClick = { byTrack = false }, label = { Text(stringResource(R.string.search_by_album)) })
                    FilterChip(selected = byTrack, onClick = { byTrack = true }, label = { Text(stringResource(R.string.search_by_track)) })
                }
                if (byTrack) OutlinedTextField(track, { track = it }, label = { Text(stringResource(R.string.track_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.album_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.album_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy && (if (byTrack) track.isNotBlank() else album.isNotBlank() || artist.isNotBlank()), onClick = { search() }) {
                        Text(stringResource(R.string.nav_search))
                    }
                    if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val r = results
                if (r != null && r.isEmpty()) Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.bodyMedium)
                if (!r.isNullOrEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(r) { cand ->
                            ListItem(
                                modifier = Modifier.clickable { onPick(cand) },
                                leadingContent = { Artwork(cand.thumbnailUrl, Modifier.width(52.dp).aspectRatio(1f)) },
                                headlineContent = { Text(cand.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(cand.artist, cand.year, cand.country, cand.format, if (cand.trackCount > 0) "${cand.trackCount} ♪" else null).joinToString(" · "),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.metadata_source_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Albums missing a cover, title or artist, with a bulk "find covers" action. */
@Composable
fun MissingInfoScreen() {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val state by c.library.collectAsStateCompat()
    val progress by c.coverFill.collectAsStateCompat()
    val lib = (state as? LibraryState.Ready)?.library ?: return EmptyState(Icons.Default.AutoFixHigh, stringResource(R.string.no_server_selected))
    val albums = remember(lib) { lib.albums.filter { MetadataEditor.needsInfo(it) }.sortedBy { it.title.lowercase() } }
    val noCover = albums.count { it.artUri.isNullOrBlank() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.missing_info_summary, albums.size), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                val p = progress
                if (p != null && !p.finished) {
                    Text(stringResource(R.string.cover_fill_progress, p.done, p.total, p.found))
                    LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    TextButton(onClick = { c.cancelCoverFill() }) { Text(stringResource(R.string.cancel)) }
                } else {
                    if (p != null) Text(stringResource(R.string.cover_fill_done, p.found, p.total), style = MaterialTheme.typography.bodyMedium)
                    Button(enabled = noCover > 0, onClick = { c.autoFillCovers() }) {
                        Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cover_fill_all, noCover))
                    }
                    Text(stringResource(R.string.cover_fill_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (albums.isEmpty()) item { Text(stringResource(R.string.missing_info_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(albums, key = { it.id }) { a -> MissingRow(a) { nav.navigate(EditRoutes.album(a.id)) } }
    }
}

@Composable
private fun MissingRow(a: Album, onClick: () -> Unit) {
    val reasons = buildList {
        if (a.artUri.isNullOrBlank()) add(stringResource(R.string.missing_cover))
        if (a.title.isBlank() || a.title.equals(LibraryIndex.UNKNOWN, true)) add(stringResource(R.string.missing_title))
        if (a.displayArtist.isBlank() || a.displayArtist.equals(LibraryIndex.UNKNOWN, true)) add(stringResource(R.string.missing_artist))
        if (a.tracks.any { it.title.isBlank() }) add(stringResource(R.string.missing_track_titles))
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Artwork(a.artUri, Modifier.width(52.dp).aspectRatio(1f)) },
        headlineContent = { Text(a.title.ifBlank { stringResource(R.string.unknown) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOf(a.displayArtist.ifBlank { "—" }, reasons.joinToString(", ")).joinToString(" · "), maxLines = 2) },
    )
}
