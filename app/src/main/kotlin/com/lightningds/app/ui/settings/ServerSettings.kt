package com.lightningds.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.core.lightning.DiskInfo
import com.lightningds.core.lightning.LightningServerConfig
import com.lightningds.core.lightning.SmbConfig
import com.lightningds.core.lightning.WorkModes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private data class ServerState(
    val version: String?,
    val modes: WorkModes?,
    val disk: DiskInfo?,
    val smb: SmbConfig?,
    val progress: String?,
)

fun WorkModes.isNetwork(type: String = current): Boolean =
    type.contains("smb", true) || type.contains("net", true) || type.contains("samba", true) ||
        modes.firstOrNull { it.type == type }?.name?.contains("network", true) == true

@Composable
fun ServerSettingsScreen(udn: String) {
    val c = LocalController.current
    val devices by c.devices.collectAsStateCompat()
    val item = devices.firstOrNull { it.udn == udn }
    val cfg = remember(udn, item != null) { c.serverConfig(udn) }
    val settings by c.settings.settings.collectAsStateCompat()

    if (cfg == null) {
        Column(Modifier.fillMaxSize()) {
            if (item != null) {
                SettingRow(item.name, listOfNotNull(item.manufacturer, item.model, item.host).joinToString(" · "), Icons.Default.Info)
                SwitchRow(stringResource(R.string.using_this_library), null, settings.serverUdn == udn, Icons.Default.Dns) { c.selectServer(if (it) udn else null) }
            }
            EmptyState(Icons.Default.Dns, stringResource(R.string.not_lightning_device))
        }
        return
    }

    val loader = rememberLoad(udn) {
        coroutineScope {
            val v = async { runCatching { cfg.version() }.getOrNull() }
            val m = async { runCatching { cfg.workModes() }.getOrNull() }
            val d = async { runCatching { cfg.diskInfo() }.getOrNull() }
            val s = async { runCatching { cfg.smbConfig() }.getOrNull() }
            val p = async { runCatching { cfg.progress() }.getOrNull() }
            ServerState(v.await(), m.await(), d.await(), s.await(), p.await())
        }
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    fun change(block: suspend () -> Unit) = c.launchIo { block(); delay(500); loader.reload() }

    LoadContent(loader) { st ->
        when (dialog) {
            "name" -> TextInputDialog(stringResource(R.string.server_name), initial = item?.name.orEmpty(), onDismiss = { dialog = null }, onConfirm = {
                dialog = null
                change { cfg.setName(it.trim()) }
            })
            "mode" -> ChoiceDialog(
                stringResource(R.string.music_storage),
                st.modes?.modes.orEmpty().map { it.type to it.name.ifBlank { it.type } },
                st.modes?.current, { dialog = null }, { m -> change { cfg.setWorkMode(m) } },
            )
            "smb" -> SmbDialog(st.smb, onDismiss = { dialog = null }) { smb ->
                dialog = null
                change {
                    cfg.setSmbConfig(smb)
                    runCatching { cfg.mount() }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.server_settings)) }
            item { SwitchRow(stringResource(R.string.using_this_library), null, settings.serverUdn == udn, Icons.Default.Dns) { c.selectServer(if (it) udn else null) } }
            item { SettingRow(stringResource(R.string.server_name), item?.name, Icons.Default.Edit, onClick = { dialog = "name" }) }
            st.version?.let { item { SettingRow(stringResource(R.string.server_version), it, Icons.Default.Info) } }

            item { SectionHeader(stringResource(R.string.music_storage)) }
            st.modes?.let { m ->
                item {
                    val current = m.modes.firstOrNull { it.type == m.current }?.name ?: m.current
                    SettingRow(
                        stringResource(R.string.music_storage), current,
                        if (m.isNetwork()) Icons.Default.FolderShared else Icons.Default.Usb,
                        onClick = { if (m.modes.isNotEmpty()) dialog = "mode" },
                    )
                }
                if (m.isNetwork()) {
                    item { SettingRow(stringResource(R.string.network_folder_path), st.smb?.address?.ifBlank { null } ?: stringResource(R.string.network_folder_hint), Icons.Default.Folder, onClick = { dialog = "smb" }) }
                }
            }

            item { SectionHeader(stringResource(R.string.drive_information)) }
            st.disk?.let { d ->
                item {
                    SettingRow(
                        stringResource(R.string.drive_status),
                        (if (d.connected) stringResource(R.string.mounted) else stringResource(R.string.not_mounted)) +
                            (LightningServerConfig.STATUS_MESSAGES[d.statusCode]?.let { "\n$it" } ?: d.statusInfo.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                        Icons.Default.Storage,
                    )
                }
                if (d.capacity.isNotBlank()) item { SettingRow(stringResource(R.string.drive_size), d.capacity, Icons.Default.SdStorage) }
                item { SettingRow(stringResource(R.string.file_count), d.fileCount.toString(), Icons.Default.Folder) }
            }
            st.progress?.takeIf { it.isNotBlank() }?.let { item { SettingRow(stringResource(R.string.scan_progress, it), null, Icons.Default.Refresh) } }
            item {
                SettingRow(stringResource(R.string.rescan), null, Icons.Default.Refresh, onClick = {
                    change { cfg.rescan() }
                    c.toast(c.string(R.string.rescan_started))
                })
            }
            item { SettingRow(stringResource(R.string.mount), null, Icons.Default.Usb, onClick = { change { cfg.mount() } }) }
            item { SettingRow(stringResource(R.string.unmount), null, Icons.Default.Eject, onClick = { change { cfg.unmount() } }) }
            item { SettingRow(stringResource(R.string.refresh_cache), null, Icons.Default.Refresh, onClick = { c.selectServer(udn); c.syncLibrary() }) }
        }
    }
}

@Composable
fun SmbDialog(current: SmbConfig?, onDismiss: () -> Unit, onSave: (SmbConfig) -> Unit) {
    var addr by remember { mutableStateOf(current?.address?.ifBlank { null } ?: "smb://") }
    var user by remember { mutableStateOf(current?.userName.orEmpty()) }
    var pass by remember { mutableStateOf(current?.password.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_folder)) },
        text = {
            Column {
                OutlinedTextField(addr, { addr = it }, singleLine = true, label = { Text(stringResource(R.string.network_folder_path)) }, placeholder = { Text(stringResource(R.string.network_folder_hint)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(enabled = addr.length > "smb://".length, onClick = { onSave(SmbConfig(addr.trim(), user.trim(), pass)) }) { Text(stringResource(R.string.mount)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
