package com.lightningds.app.ui.streaming

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightningds.app.R
import com.lightningds.app.ui.components.AlbumCard
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.MenuAction
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.TrackRow
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.queueActions
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.app.ui.library.DetailLayout
import com.lightningds.app.ui.library.minutes
import com.lightningds.core.media.Track
import com.lightningds.core.renderer.InsertMode
import com.lightningds.core.streaming.FavoriteKind
import com.lightningds.core.streaming.QobuzService
import com.lightningds.core.streaming.StreamAlbum
import com.lightningds.core.streaming.StreamArtist
import com.lightningds.core.streaming.StreamPlaylist
import com.lightningds.core.streaming.StreamingService
import com.lightningds.core.streaming.TidalDeviceCode
import com.lightningds.core.streaming.TidalService
import kotlinx.coroutines.launch

object StreamRoutes {
    fun album(service: String, id: String) = "stream/album/$service/${Uri.encode(id)}"
    fun artist(service: String, id: String) = "stream/artist/$service/${Uri.encode(id)}"
    fun playlist(service: String, id: String) = "stream/playlist/$service/${Uri.encode(id)}"
    fun genre(service: String, id: String, name: String) = "stream/genre/$service/${Uri.encode(id)}/${Uri.encode(name)}"
}

private enum class StreamTab { FEATURED, GENRES, FAVORITES, PLAYLISTS, PURCHASES }

@Composable
fun StreamingScreen() {
    val c = LocalController.current
    val settings by c.settings.settings.collectAsStateCompat()
    val version by c.streamingVersion.collectAsStateCompat()
    val service = c.streamingService(settings.streamingService)

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            c.streamingServices.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = s.name == service.name,
                    onClick = { c.settings.setStreamingService(s.name) },
                    shape = SegmentedButtonDefaults.itemShape(i, c.streamingServices.size),
                ) { Text(s.name) }
            }
        }
        // `version` is read so the screen recomposes after sign-in/out.
        if (version >= 0 && !service.isLoggedIn) {
            LoginView(service)
        } else {
            LoggedInView(service)
        }
    }
}

@Composable
private fun LoginView(service: StreamingService) {
    val c = LocalController.current
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deviceCode by remember(service.name) { mutableStateOf<TidalDeviceCode?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Cloud, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
        Text(service.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (service is TidalService) R.string.tidal_desc else R.string.qobuz_desc),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (service) {
                is QobuzService -> {
                    var appId by remember { mutableStateOf(settings.qobuzAppId) }
                    var user by rememberSaveable { mutableStateOf("") }
                    var pass by rememberSaveable { mutableStateOf("") }
                    if (settings.qobuzAppId.isBlank()) {
                        Text(stringResource(R.string.streaming_credentials_needed), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(appId, { appId = it }, label = { Text(stringResource(R.string.qobuz_app_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Button(
                        enabled = !busy && user.isNotBlank() && pass.isNotBlank() && (appId.isNotBlank() || settings.qobuzAppId.isNotBlank()),
                        onClick = {
                            if (appId != settings.qobuzAppId) c.settings.setQobuzAppId(appId)
                            busy = true; error = null
                            scope.launch {
                                // Give DataStore a moment to persist the app id the client reads.
                                kotlinx.coroutines.delay(150)
                                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { service.login(user.trim(), pass) } }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sign_in)) }
                }
                is TidalService -> {
                    var id by remember { mutableStateOf(settings.tidalClientId) }
                    var secret by remember { mutableStateOf(settings.tidalClientSecret) }
                    if (settings.tidalClientId.isBlank()) {
                        Text(stringResource(R.string.streaming_credentials_needed), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.tidal_client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.tidal_client_secret)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    val code = deviceCode
                    if (code != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.tidal_link_prompt), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(code.userCode, fontFamily = FontFamily.Monospace, fontSize = 32.sp, style = MaterialTheme.typography.headlineLarge)
                                Text(code.verificationUri, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))) }) {
                                    Text(stringResource(R.string.open_link))
                                }
                                Text(stringResource(R.string.waiting_for_approval), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Button(
                        enabled = !busy && (id.isNotBlank() || settings.tidalClientId.isNotBlank()),
                        onClick = {
                            if (id != settings.tidalClientId || secret != settings.tidalClientSecret) c.settings.setTidalClient(id, secret)
                            busy = true; error = null
                            scope.launch {
                                kotlinx.coroutines.delay(150)
                                runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val dc = service.startDeviceLogin()
                                        deviceCode = dc
                                        service.completeDeviceLogin(dc)
                                    }
                                }.onFailure { error = it.message }
                                deviceCode = null
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sign_in)) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.streaming_playback_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoggedInView(service: StreamingService) {
    var tab by rememberSaveable(service.name) { mutableStateOf(StreamTab.FEATURED) }
    val tabs = StreamTab.entries.filter { it != StreamTab.PURCHASES || service.supportsPurchases }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), modifier = Modifier.weight(1f), edgePadding = 8.dp) {
                tabs.forEach { t ->
                    Tab(selected = t == tab, onClick = { tab = t }, text = {
                        Text(stringResource(when (t) {
                            StreamTab.FEATURED -> R.string.featured
                            StreamTab.GENRES -> R.string.genres
                            StreamTab.FAVORITES -> R.string.favorites
                            StreamTab.PLAYLISTS -> R.string.playlists
                            StreamTab.PURCHASES -> R.string.purchases
                        }))
                    })
                }
            }
            IconButton(onClick = { service.logout() }) { Icon(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.sign_out)) }
        }
        service.userName?.let {
            Text(stringResource(R.string.signed_in_as, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        when (tab) {
            StreamTab.FEATURED -> FeaturedView(service)
            StreamTab.GENRES -> GenresView(service)
            StreamTab.FAVORITES -> FavoritesView(service, purchases = false)
            StreamTab.PLAYLISTS -> {
                val loader = rememberLoad(service.name, "playlists") { service.userPlaylists() + runCatching { service.featuredPlaylists(0, 30) }.getOrDefault(emptyList()) }
                LoadContent(loader) { PlaylistList(service, it) }
            }
            StreamTab.PURCHASES -> FavoritesView(service, purchases = true)
        }
    }
}

@Composable
private fun FeaturedView(service: StreamingService) {
    var section by rememberSaveable(service.name) { mutableStateOf(service.featuredSections.first().key) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            service.featuredSections.forEach { s ->
                FilterChip(selected = s.key == section, onClick = { section = s.key }, label = { Text(s.title) })
            }
        }
        val loader = rememberLoad(service.name, section) { service.featuredAlbums(section, 0, 60) }
        LoadContent(loader) { StreamAlbumGrid(service, it) }
    }
}

@Composable
private fun GenresView(service: StreamingService) {
    val nav = LocalNavigator.current
    val loader = rememberLoad(service.name, "genres") { service.genres() }
    LoadContent(loader) { genres ->
        LazyColumn {
            items(genres, key = { it.id }) { g ->
                ListItem(
                    modifier = Modifier.clickable { nav.navigate(StreamRoutes.genre(service.name, g.id, g.name)) },
                    leadingContent = { Artwork(g.artUrl, size = 48.dp, placeholder = Icons.Default.Category) },
                    headlineContent = { Text(g.name) },
                )
            }
        }
    }
}

@Composable
fun StreamGenreScreen(serviceName: String, id: String, name: String) {
    val c = LocalController.current
    val service = c.streamingService(serviceName)
    val loader = rememberLoad(serviceName, id) { service.genreAlbums(com.lightningds.core.streaming.StreamGenre(id, name), 0, 100) }
    LoadContent(loader) { StreamAlbumGrid(service, it, header = { Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(6.dp)) }) }
}

@Composable
private fun FavoritesView(service: StreamingService, purchases: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val loader = rememberLoad(service.name, purchases) { if (purchases) service.purchases() else service.favorites() }
    LoadContent(loader) { fav ->
        val tabs = listOf(R.string.tab_albums, R.string.tab_artists, R.string.tab_tracks, R.string.tab_playlists)
        Column {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, r -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(r)) }) }
            }
            when (tab) {
                0 -> StreamAlbumGrid(service, fav.albums)
                1 -> ArtistList(service, fav.artists)
                2 -> StreamTrackList(service, fav.tracks)
                else -> PlaylistList(service, fav.playlists)
            }
        }
    }
}

@Composable
fun StreamAlbumGrid(service: StreamingService, albums: List<StreamAlbum>, header: (@Composable () -> Unit)? = null) {
    val nav = LocalNavigator.current
    if (albums.isEmpty() && header == null) {
        EmptyState(Icons.Default.Cloud, stringResource(R.string.search_no_results))
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxSize()) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.id }) { a ->
            AlbumCard(a.title, listOfNotNull(a.artist.ifBlank { null }, a.year).joinToString(" · "), a.artUrl, onClick = { nav.navigate(StreamRoutes.album(service.name, a.id)) })
        }
    }
}

@Composable
fun ArtistList(service: StreamingService, artists: List<StreamArtist>) {
    val nav = LocalNavigator.current
    LazyColumn {
        items(artists, key = { it.id }) { a ->
            ListItem(
                modifier = Modifier.clickable { nav.navigate(StreamRoutes.artist(service.name, a.id)) },
                leadingContent = { Artwork(a.pictureUrl, size = 48.dp, shape = CircleShape, placeholder = Icons.Default.Person) },
                headlineContent = { Text(a.name) },
            )
        }
    }
}

@Composable
fun PlaylistList(service: StreamingService, playlists: List<StreamPlaylist>) {
    val nav = LocalNavigator.current
    LazyColumn {
        items(playlists, key = { it.id }) { p ->
            ListItem(
                modifier = Modifier.clickable { nav.navigate(StreamRoutes.playlist(service.name, p.id)) },
                leadingContent = { Artwork(p.artUrl, size = 48.dp, placeholder = Icons.AutoMirrored.Filled.PlaylistPlay) },
                headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(listOfNotNull(p.owner, if (p.trackCount > 0) com.lightningds.app.ui.components.pluralSongs(p.trackCount) else null).joinToString(" · ")) },
            )
        }
    }
}

@Composable
fun StreamTrackList(service: StreamingService, tracks: List<Track>) {
    val c = LocalController.current
    LazyColumn {
        itemsIndexed(tracks, key = { i, t -> t.id + i }) { i, t ->
            TrackRow(t, onClick = { c.play(tracks, InsertMode.REPLACE, i) }, showArt = true, menu = streamTrackMenu(service, t))
        }
    }
}

@Composable
private fun streamTrackMenu(service: StreamingService, t: Track): List<MenuAction> {
    val c = LocalController.current
    val nav = LocalNavigator.current
    return queueActions({ listOf(t) }, extra = listOfNotNull(
        MenuAction(stringResource(R.string.add_to_favorites), Icons.Default.FavoriteBorder) {
            c.launchIo { service.setFavorite(FavoriteKind.TRACKS, t.id, true) }
        },
        t.parentId.takeIf { it.isNotBlank() && it != "0" }?.let { MenuAction(stringResource(R.string.go_to_album), null) { nav.navigate(StreamRoutes.album(service.name, it)) } },
    ))
}

@Composable
private fun FavoriteToggle(service: StreamingService, kind: FavoriteKind, id: String) {
    val c = LocalController.current
    var fav by remember(id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(id) {
        fav = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val f = service.favorites()
                when (kind) {
                    FavoriteKind.ALBUMS -> f.albums.any { it.id == id }
                    FavoriteKind.ARTISTS -> f.artists.any { it.id == id }
                    FavoriteKind.PLAYLISTS -> f.playlists.any { it.id == id }
                    FavoriteKind.TRACKS -> f.tracks.any { it.id == id }
                }
            }
        }.getOrDefault(false)
    }
    val isFav = fav == true
    IconButton(onClick = {
        val target = !isFav
        fav = target
        c.launchIo { service.setFavorite(kind, id, target) }
    }) {
        Icon(
            if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            stringResource(if (isFav) R.string.remove_from_favorites else R.string.add_to_favorites),
            tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StreamAlbumScreen(serviceName: String, id: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val service = c.streamingService(serviceName)
    val loader = rememberLoad(serviceName, id) { service.album(id) }
    LoadContent(loader) { d ->
        DetailLayout(
            art = d.album.artUrl,
            title = d.album.title,
            subtitle = d.album.artist,
            info = listOfNotNull(d.album.year, d.album.quality, stringResource(R.string.album_info, com.lightningds.app.ui.components.pluralSongs(d.tracks.size), minutes(d.tracks))).joinToString(" · "),
            tracks = d.tracks,
            headerActions = {
                FavoriteToggle(service, FavoriteKind.ALBUMS, d.album.id)
                d.album.artistId?.takeIf { it.isNotBlank() && it != "0" }?.let { aid ->
                    TextButton(onClick = { nav.navigate(StreamRoutes.artist(serviceName, aid)) }) { Text(stringResource(R.string.go_to_artist)) }
                }
            },
        ) {
            itemsIndexed(d.tracks, key = { i, t -> t.id + i }) { i, t ->
                TrackRow(t, onClick = { c.play(d.tracks, InsertMode.REPLACE, i) }, index = if (t.trackNumber > 0) t.trackNumber else i + 1, menu = streamTrackMenu(service, t))
            }
        }
    }
}

@Composable
fun StreamPlaylistScreen(serviceName: String, id: String) {
    val c = LocalController.current
    val service = c.streamingService(serviceName)
    val loader = rememberLoad(serviceName, id) { service.playlist(id) }
    LoadContent(loader) { d ->
        DetailLayout(
            art = d.playlist.artUrl,
            title = d.playlist.title,
            subtitle = d.playlist.owner,
            info = stringResource(R.string.tracks_duration, d.tracks.size, minutes(d.tracks)),
            tracks = d.tracks,
            placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
            headerActions = { FavoriteToggle(service, FavoriteKind.PLAYLISTS, d.playlist.id) },
        ) {
            itemsIndexed(d.tracks, key = { i, t -> t.id + i }) { i, t ->
                TrackRow(t, onClick = { c.play(d.tracks, InsertMode.REPLACE, i) }, showArt = true, menu = streamTrackMenu(service, t))
            }
        }
    }
}

@Composable
fun StreamArtistScreen(serviceName: String, id: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val service = c.streamingService(serviceName)
    val loader = rememberLoad(serviceName, id) { service.artist(id) }
    LoadContent(loader) { d ->
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(d.artist.pictureUrl, size = 96.dp, shape = CircleShape, placeholder = Icons.Default.Person)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(d.artist.name, style = MaterialTheme.typography.headlineSmall)
                        Text(com.lightningds.app.ui.components.pluralAlbums(d.albums.size), style = MaterialTheme.typography.bodyMedium)
                    }
                    FavoriteToggle(service, FavoriteKind.ARTISTS, d.artist.id)
                }
            }
            if (d.topTracks.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.top_tracks)) }
                d.topTracks.take(10).forEachIndexed { i, t ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "top$i") {
                        TrackRow(t, onClick = { c.play(d.topTracks, InsertMode.REPLACE, i) }, showArt = true, menu = streamTrackMenu(service, t))
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.albums)) }
            }
            items(d.albums, key = { it.id }) { a ->
                AlbumCard(a.title, a.year, a.artUrl, onClick = { nav.navigate(StreamRoutes.album(serviceName, a.id)) })
            }
        }
    }
}
