package com.lightningds.app.ui.library

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadingBox
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.core.files.FileEntry
import com.lightningds.core.files.FileMatcher
import com.lightningds.core.files.FileStore
import com.lightningds.core.files.TagWriter
import com.lightningds.core.files.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FileRoutes {
    fun write(albumId: String) = "edit/files/${Uri.encode(albumId)}"
}

/** SMB connection details, shared by Settings and the write screen. */
@Composable
fun SmbSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val c = LocalController.current
    val s = c.settings.current
    val defaultHost = c.selectedServer.value?.host.orEmpty()
    var host by remember { mutableStateOf(s.smbHost) }
    var share by remember { mutableStateOf(s.smbShare) }
    var user by remember { mutableStateOf(s.smbUser) }
    var pass by remember { mutableStateOf(s.smbPassword) }
    var domain by remember { mutableStateOf(s.smbDomain) }
    var root by remember { mutableStateOf(s.smbRoot) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val okMsg = stringResource(R.string.smb_connected)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smb_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    Text(stringResource(R.string.smb_desc), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.smb_host)) }, placeholder = { Text(defaultHost) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(share, { share = it }, label = { Text(stringResource(R.string.smb_share)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(root, { root = it }, label = { Text(stringResource(R.string.smb_root)) }, placeholder = { Text("Music") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.smb_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !testing && share.isNotBlank() && (host.isNotBlank() || defaultHost.isNotBlank()), onClick = {
                            testing = true; result = null
                            scope.launch {
                                val store = com.lightningds.core.files.SmbFileStore(host.ifBlank { defaultHost }, share.trim('/', '\\'), user, pass, domain)
                                result = runCatching { withContext(Dispatchers.IO) { store.list(root.trim('/', '\\')) } }
                                    .fold({ okMsg.format(it.size) }, { it.message ?: it.javaClass.simpleName })
                                store.close()
                                testing = false
                            }
                        }) { Text(stringResource(R.string.smb_test)) }
                        if (testing) CircularProgressIndicator(Modifier.size(20.dp))
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.settings.setSmb(host, share, user, pass, domain, root)
                onDismiss(); onSaved()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private sealed interface Step {
    data object NeedSetup : Step
    data object Searching : Step
    data class NotFound(val message: String?) : Step
    data class Ready(val folder: String, val matches: List<FileMatcher.Match>) : Step
    data class Writing(val done: Int, val total: Int, val current: String) : Step
    data class Done(val results: List<WriteResult>) : Step
}

/** Writes an album's edits into its files on the SMB share. */
@Composable
fun WriteFilesScreen(albumId: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val state by c.library.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    val lib = (state as? LibraryState.Ready)?.library ?: return LoadingBox()
    val original = c.originalAlbum(albumId) ?: return EmptyState(Icons.Default.MusicNote, stringResource(R.string.unknown))
    val edits = c.metadata.forServer(lib.serverUdn)
    val hasCoverEdit = edits.albums[albumId]?.artUri != null
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<Step>(if (c.smbConfigured()) Step.Searching else Step.NeedSetup) }
    var store by remember { mutableStateOf<FileStore?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    var embedCover by remember { mutableStateOf(hasCoverEdit) }
    var folderCover by remember { mutableStateOf(hasCoverEdit) }

    suspend fun locate(folder: String? = null) {
        step = Step.Searching
        val s = c.smbStore() ?: run { step = Step.NeedSetup; return }
        store = s
        step = try {
            val f = folder ?: c.findAlbumFolder(s, albumId)
            if (f == null) Step.NotFound(null)
            else Step.Ready(f, FileMatcher.match(original.tracks, s.list(f)))
        } catch (e: Exception) {
            Step.NotFound(e.message ?: e.javaClass.simpleName)
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { (store as? java.io.Closeable)?.close() } }

    LaunchedEffect(settings.smbHost, settings.smbShare, settings.smbUser, settings.smbPassword, settings.smbRoot) {
        (store as? java.io.Closeable)?.close()
        if (c.smbConfigured()) locate()
    }

    if (showSetup) SmbSettingsDialog(onDismiss = { showSetup = false })
    val s = store
    if (browse && s != null) {
        FolderPickerDialog(s, start = (step as? Step.Ready)?.folder ?: settings.smbRoot, onDismiss = { browse = false }) { picked ->
            browse = false
            c.rememberAlbumFolder(albumId, picked)
            scope.launch { locate(picked) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(original.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.write_files_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when (val st = step) {
            Step.NeedSetup -> {
                Text(stringResource(R.string.smb_needed))
                Button(onClick = { showSetup = true }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.smb_title)) }
            }
            Step.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.write_files_searching), Modifier.padding(start = 12.dp))
            }
            is Step.NotFound -> {
                Text(st.message?.let { stringResource(R.string.write_files_error, it) } ?: stringResource(R.string.write_files_not_found))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = s != null, onClick = { browse = true }) { Text(stringResource(R.string.write_files_choose_folder)) }
                    OutlinedButton(onClick = { showSetup = true }) { Text(stringResource(R.string.smb_title)) }
                }
            }
            is Step.Ready -> {
                val found = st.matches.count { it.file != null }
                ListItem(
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    headlineContent = { Text(st.folder.ifBlank { "/" }) },
                    supportingContent = { Text(stringResource(R.string.write_files_matched, found, st.matches.size)) },
                    trailingContent = { TextButton(onClick = { browse = true }) { Text(stringResource(R.string.write_files_change)) } },
                )
                if (hasCoverEdit) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(embedCover, { embedCover = it }); Text(stringResource(R.string.write_files_embed_cover)) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(folderCover, { folderCover = it }); Text(stringResource(R.string.write_files_folder_cover)) }
                }
                Button(
                    enabled = found > 0 && s != null,
                    modifier = Modifier.padding(vertical = 8.dp),
                    onClick = {
                        val st0 = st
                        scope.launch {
                            step = Step.Writing(0, st0.matches.size, "")
                            step = try {
                                Step.Done(c.writeAlbumTags(s!!, albumId, st0.folder, st0.matches, embedCover, folderCover) { d, t, cur -> step = Step.Writing(d, t, cur) })
                            } catch (e: Exception) {
                                Step.NotFound(e.message)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.write_files_go, found)) }
                SectionHeader(stringResource(R.string.tracks), Modifier.padding(start = 0.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(st.matches, key = { it.track.id }) { m ->
                        val f = m.file
                        ListItem(
                            headlineContent = { Text(m.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    when {
                                        f == null -> stringResource(R.string.write_files_no_file)
                                        !TagWriter.isSupported(f.name) -> f.name + " · " + stringResource(R.string.write_files_unsupported)
                                        else -> f.name
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (f == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
            is Step.Writing -> {
                Text(stringResource(R.string.write_files_progress, st.done, st.total))
                if (st.current.isNotBlank()) Text(st.current, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(stringResource(R.string.write_files_keep_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Step.Done -> {
                val ok = st.results.count { it.ok }
                Text(stringResource(R.string.write_files_done, ok, st.results.size), style = MaterialTheme.typography.titleMedium)
                if (ok > 0) Text(stringResource(R.string.write_files_rescan), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.back() }) { Text(stringResource(R.string.done)) }
                    OutlinedButton(onClick = { c.syncLibrary() }) { Text(stringResource(R.string.refresh_cache)) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(st.results, key = { it.track.id }) { r ->
                        ListItem(
                            leadingContent = { Icon(if (r.ok) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
                            headlineContent = { Text(r.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(r.file?.name, r.error, r.note).joinToString(" · "), maxLines = 2) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPickerDialog(store: FileStore, start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf(start.trim('/')) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        entries = null; error = null
        runCatching { withContext(Dispatchers.IO) { store.list(path) } }
            .onSuccess { entries = it.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
            .onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("/" + path) },
        text = {
            Column {
                if (path.isNotEmpty()) {
                    ListItem(
                        modifier = Modifier.clickable { path = path.substringBeforeLast('/', "") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                        headlineContent = { Text("..") },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val list = entries
                if (list == null && error == null) CircularProgressIndicator()
                if (list != null) {
                    val audio = list.count { !it.isDirectory && TagWriter.isAudio(it.name) }
                    if (audio > 0) Text(stringResource(R.string.write_files_audio_here, audio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    LazyColumn(Modifier.heightIn(max = 360.dp).widthIn(min = 280.dp)) {
                        items(list.filter { it.isDirectory }) { e ->
                            ListItem(
                                modifier = Modifier.clickable { path = e.path },
                                leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                                headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(stringResource(R.string.write_files_use_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
