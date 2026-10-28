package com.lightningds.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.TrackRow
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.pluralAlbums
import com.lightningds.app.ui.components.queueActions
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.app.ui.library.LibraryTab
import com.lightningds.app.ui.library.Routes
import com.lightningds.app.ui.radio.StationRow
import com.lightningds.app.ui.streaming.StreamRoutes
import com.lightningds.core.library.LibraryIndex
import com.lightningds.core.radio.RadioStation
import com.lightningds.core.renderer.InsertMode
import com.lightningds.core.streaming.StreamSearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private enum class Scope { ALL, LIBRARY, STREAMING, RADIO }

private data class Results(
    val library: LibraryIndex.SearchHits?,
    val streaming: StreamSearchResult?,
    val streamingService: String?,
    val radio: List<RadioStation>,
)

@Composable
fun SearchScreen() {
    val c = LocalController.current
    val history by c.searchHistory.history.collectAsStateCompat()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(Scope.ALL) }
    val submit = { q: String ->
        query = q
        submitted = q.trim()
        c.searchHistory.add(q)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = "" }) { Icon(Icons.Default.Close, null) } },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submit(query) }),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scope.entries.forEach { s ->
                FilterChip(selected = s == scope, onClick = { scope = s }, label = {
                    Text(stringResource(when (s) {
                        Scope.ALL -> R.string.search_all
                        Scope.LIBRARY -> R.string.search_library
                        Scope.STREAMING -> R.string.search_streaming
                        Scope.RADIO -> R.string.search_radio
                    }))
                })
            }
        }
        if (submitted.isBlank()) {
            History(history, onPick = submit, onClear = { c.searchHistory.clear() })
        } else {
            Results(submitted, scope)
        }
    }
}

@Composable
private fun History(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) {
        EmptyState(Icons.Default.Search, stringResource(R.string.search_hint))
        return
    }
    LazyColumn {
        item {
            SectionHeader(stringResource(R.string.search_history)) { TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear)) } }
        }
        items(history) { h ->
            ListItem(modifier = Modifier.clickable { onPick(h) }, leadingContent = { Icon(Icons.Default.History, null) }, headlineContent = { Text(h) })
        }
    }
}

@Composable
private fun Results(query: String, scope: Scope) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val libState by c.library.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    val service = c.streamingService(settings.streamingService).takeIf { it.isLoggedIn } ?: c.streamingServices.firstOrNull { it.isLoggedIn }
    val loader = rememberLoad(query, scope, libState is LibraryState.Ready, service?.name) {
        coroutineScope {
            val lib = (libState as? LibraryState.Ready)?.library
            val libHits = if (scope == Scope.ALL || scope == Scope.LIBRARY) lib?.let { LibraryIndex.search(it, query) } else null
            val stream = if ((scope == Scope.ALL || scope == Scope.STREAMING) && service != null) async { runCatching { service.search(query) }.getOrNull() } else null
            val radio = if (scope == Scope.ALL || scope == Scope.RADIO) async { runCatching { c.radio.search(query, 30) }.getOrDefault(emptyList()) } else null
            Results(libHits, stream?.await(), service?.name, radio?.await().orEmpty())
        }
    }
    LoadContent(loader) { r ->
        val limit = if (scope == Scope.ALL) 5 else Int.MAX_VALUE
        val empty = (r.library?.let { it.albums.isEmpty() && it.tracks.isEmpty() && it.artists.isEmpty() } ?: true) &&
            (r.streaming?.let { it.albums.isEmpty() && it.tracks.isEmpty() && it.artists.isEmpty() && it.playlists.isEmpty() } ?: true) &&
            r.radio.isEmpty()
        if (empty) {
            EmptyState(Icons.Default.Search, stringResource(R.string.search_no_results) + "\n" + stringResource(R.string.search_no_results_prompt))
            return@LoadContent
        }
        LazyColumn(Modifier.fillMaxSize()) {
            r.library?.let { hits ->
                section(stringResource(R.string.artists) + " · " + stringResource(R.string.search_library), hits.artists.take(limit)) { g ->
                    ListItem(
                        modifier = Modifier.clickable { nav.navigate(Routes.group(LibraryTab.ARTISTS, g.key)) },
                        leadingContent = { Artwork(g.artUri, size = 48.dp, shape = CircleShape, placeholder = Icons.Default.Person) },
                        headlineContent = { Text(g.key) },
                        supportingContent = { Text(pluralAlbums(g.albums.size)) },
                    )
                }
                section(stringResource(R.string.albums) + " · " + stringResource(R.string.search_library), hits.albums.take(limit)) { a ->
                    ListItem(
                        modifier = Modifier.clickable { nav.navigate(Routes.album(a.id)) },
                        leadingContent = { Artwork(a.artUri, size = 48.dp) },
                        headlineContent = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(a.displayArtist, maxLines = 1) },
                    )
                }
                section(stringResource(R.string.tracks) + " · " + stringResource(R.string.search_library), hits.tracks.take(limit)) { t ->
                    TrackRow(t, onClick = { c.play(listOf(t), InsertMode.PLAY_NOW) }, showArt = true, menu = queueActions({ listOf(t) }))
                }
            }
            r.streaming?.let { s ->
                val svc = r.streamingService ?: ""
                section(stringResource(R.string.artists) + " · " + svc, s.artists.take(limit)) { a ->
                    ListItem(
                        modifier = Modifier.clickable { nav.navigate(StreamRoutes.artist(svc, a.id)) },
                        leadingContent = { Artwork(a.pictureUrl, size = 48.dp, shape = CircleShape, placeholder = Icons.Default.Person) },
                        headlineContent = { Text(a.name) },
                    )
                }
                section(stringResource(R.string.albums) + " · " + svc, s.albums.take(limit)) { a ->
                    ListItem(
                        modifier = Modifier.clickable { nav.navigate(StreamRoutes.album(svc, a.id)) },
                        leadingContent = { Artwork(a.artUrl, size = 48.dp) },
                        headlineContent = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(a.artist, maxLines = 1) },
                    )
                }
                section(stringResource(R.string.tracks) + " · " + svc, s.tracks.take(limit)) { t ->
                    TrackRow(t, onClick = { c.play(listOf(t), InsertMode.PLAY_NOW) }, showArt = true, menu = queueActions({ listOf(t) }))
                }
                section(stringResource(R.string.playlists) + " · " + svc, s.playlists.take(limit)) { p ->
                    ListItem(
                        modifier = Modifier.clickable { nav.navigate(StreamRoutes.playlist(svc, p.id)) },
                        leadingContent = { Artwork(p.artUrl, size = 48.dp, placeholder = Icons.AutoMirrored.Filled.PlaylistPlay) },
                        headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            section(stringResource(R.string.stations), r.radio.take(if (scope == Scope.ALL) 5 else 100)) { StationRow(it) }
        }
    }
}

private fun <T> LazyListScope.section(title: String, list: List<T>, row: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    item { SectionHeader(title) }
    items(list) { row(it) }
}

