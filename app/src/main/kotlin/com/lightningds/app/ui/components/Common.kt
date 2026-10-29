package com.lightningds.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.lightningds.app.AppController
import com.lightningds.app.R
import com.lightningds.core.media.Track
import com.lightningds.core.media.formatDuration
import com.lightningds.core.renderer.InsertMode

val LocalController = staticCompositionLocalOf<AppController> { error("No AppController") }

/** Navigation callbacks shared by all screens. */
class Navigator(val navigate: (String) -> Unit, val back: () -> Unit)

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    placeholder: ImageVector = Icons.Default.Album,
) {
    val m = (if (size != null) modifier.size(size) else modifier).clip(shape)
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(placeholder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.45f))
        }
    }
    if (url.isNullOrBlank()) {
        Box(m) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = m,
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

@Composable
fun AlbumCard(
    title: String,
    subtitle: String?,
    artUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    placeholder: ImageVector = Icons.Default.Album,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(artUrl, Modifier.fillMaxWidth().aspectRatio(1f), placeholder = placeholder)
        Spacer(Modifier.height(6.dp))
        Text(
            title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

data class MenuAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    leadingIcon = a.icon?.let { { Icon(it, null) } },
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

/** The standard queue actions offered for tracks/albums (as in the original "Add to Queue" sheet). */
@Composable
fun queueActions(tracks: () -> List<Track>, startAt: Int = 0, extra: List<MenuAction> = emptyList()): List<MenuAction> {
    val c = LocalController.current
    var pickPlaylist by remember { mutableStateOf(false) }
    if (pickPlaylist) AddToPlaylistDialog(tracks(), onDismiss = { pickPlaylist = false })
    return listOf(
        MenuAction(stringResource(R.string.play_now), Icons.Default.PlayArrow) { c.play(tracks(), InsertMode.PLAY_NOW, startAt) },
        MenuAction(stringResource(R.string.add_next), Icons.Default.SkipNext) { c.play(tracks(), InsertMode.ADD_NEXT) },
        MenuAction(stringResource(R.string.add_later), Icons.Default.QueueMusic) { c.play(tracks(), InsertMode.ADD_LATER) },
        MenuAction(stringResource(R.string.add_new), Icons.Default.SwapHoriz) { c.play(tracks(), InsertMode.REPLACE, startAt) },
        MenuAction(stringResource(R.string.add_to_playlist), Icons.AutoMirrored.Filled.PlaylistAdd) { pickPlaylist = true },
    ) + extra
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = false,
    playing: Boolean = false,
    menu: List<MenuAction> = emptyList(),
    subtitle: String? = null,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = if (playing) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = when {
            showArt -> ({ Artwork(track.albumArtUri, size = 48.dp, placeholder = Icons.Default.MusicNote) })
            index != null -> ({
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    if (playing) Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    else Text(index.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
            else -> null
        },
        headlineContent = {
            Text(track.title.ifBlank { track.uri.orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.SemiBold else null)
        },
        supportingContent = {
            val s = subtitle ?: listOfNotNull(track.displayArtist.ifBlank { null }, track.album?.takeIf { showArt }).joinToString(" · ")
            if (s.isNotBlank()) Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.durationSeconds > 0) {
                    Text(formatDuration(track.durationSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (menu.isNotEmpty()) OverflowMenu(menu)
            }
        },
    )
}

/** Big Play / Add next / Add later / Replace buttons for album and playlist headers. */
@Composable
fun PlayButtons(tracks: List<Track>, modifier: Modifier = Modifier) {
    val c = LocalController.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { c.play(tracks, InsertMode.REPLACE) }, enabled = tracks.isNotEmpty()) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play))
        }
        FilledTonalButton(onClick = { c.play(tracks, InsertMode.ADD_NEXT) }, enabled = tracks.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(stringResource(R.string.add_next))
        }
        FilledTonalButton(onClick = { c.play(tracks, InsertMode.ADD_LATER) }, enabled = tracks.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(stringResource(R.string.add_later))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    password: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = label?.let { { Text(it) } },
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String?, onDismiss: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = stringResource(R.string.ok)) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val c = LocalController.current
    val lists by c.playlists.playlists.collectAsStateCompat()
    var creating by remember { mutableStateOf(false) }
    val addedMsg = stringResource(R.string.added_to_playlist)
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_playlist),
            label = stringResource(R.string.playlist_name),
            onDismiss = { creating = false },
            onConfirm = { name ->
                c.playlists.create(name, tracks)
                c.toast(addedMsg.format(name))
                creating = false
                onDismiss()
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_playlist)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    modifier = Modifier.clickable { creating = true },
                )
                lists.forEach { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(pluralSongs(p.tracks.size)) },
                        modifier = Modifier.clickable {
                            c.playlists.addTracks(p.id, tracks)
                            c.toast(addedMsg.format(p.name))
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun pluralSongs(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.songs_count, n, n)

@Composable
fun pluralAlbums(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.albums_count, n, n)

@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> = collectAsStateWithLifecycle()

/** Result of an asynchronous load in a composable. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

class Loader<T>(val state: MutableState<Load<T>>, private val trigger: MutableState<Int>) {
    val value: Load<T> get() = state.value
    fun reload() { trigger.value++ }
}

/** Runs [block] when [keys] change (or on reload()), exposing Loading/Ok/Error. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    val state = remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    val trigger = remember(*keys) { mutableStateOf(0) }
    LaunchedEffect(*keys, trigger.value) {
        if (state.value !is Load.Ok) state.value = Load.Loading
        state.value = try {
            Load.Ok(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Error(e.message ?: e.javaClass.simpleName)
        }
    }
    return remember(state, trigger) { Loader(state, trigger) }
}

@Composable
fun <T> LoadContent(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = loader.value) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Error -> EmptyState(
            Icons.Default.MusicNote, s.message, modifier,
            action = { Button(onClick = { loader.reload() }) { Text(stringResource(R.string.retry)) } },
        )
        is Load.Ok -> content(s.value)
    }
}

