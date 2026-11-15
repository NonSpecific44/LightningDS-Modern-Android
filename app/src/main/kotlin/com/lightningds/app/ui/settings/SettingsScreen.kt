package com.lightningds.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lightningds.app.BuildConfigCompat
import com.lightningds.app.DeviceItem
import com.lightningds.app.R
import com.lightningds.app.data.TextAlign
import com.lightningds.app.data.ThemeMode
import com.lightningds.app.data.sort
import com.lightningds.app.ui.library.directionLabel
import com.lightningds.app.ui.library.label
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.collectAsStateCompat

object SettingsRoutes {
    fun device(udn: String) = "settings/device/${Uri.encode(udn)}"
    fun server(udn: String) = "settings/server/${Uri.encode(udn)}"
    fun network(udn: String) = "settings/network/${Uri.encode(udn)}"
    const val SETUP = "setup"
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = icon?.let { { Icon(it, null) } },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, icon, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (value, label) ->
                    ListItem(
                        modifier = Modifier.clickable { onDismiss(); onSelect(value) },
                        leadingContent = { RadioButton(selected = value == selected, onClick = { onDismiss(); onSelect(value) }) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SettingsScreen() {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateCompat()
    val renderers by c.renderers.collectAsStateCompat()
    val servers by c.servers.collectAsStateCompat()
    var dialog by remember { mutableStateOf<String?>(null) }

    when (dialog) {
        "theme" -> ChoiceDialog(
            stringResource(R.string.theme),
            listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark)),
            settings.themeMode, { dialog = null }, { c.settings.setThemeMode(it) },
        )
        "sort" -> ChoiceDialog(
            stringResource(R.string.sorting),
            com.lightningds.core.library.LibrarySort.entries.map { it to it.label() },
            settings.sort, { dialog = null }, { c.settings.setLibrarySort(it) },
        )
        "align" -> ChoiceDialog(
            stringResource(R.string.text_alignment),
            listOf(TextAlign.START to stringResource(R.string.align_start), TextAlign.CENTER to stringResource(R.string.align_center)),
            settings.textAlign, { dialog = null }, { c.settings.setTextAlign(it) },
        )
        "clear" -> ConfirmDialog(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_confirm), { dialog = null }, { c.clearLibraryCache() })
        "streaming" -> StreamingCredentialsDialog { dialog = null }
        "smb" -> com.lightningds.app.ui.library.SmbSettingsDialog(onDismiss = { dialog = null })
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.settings_devices)) }
        if (renderers.isEmpty() && servers.isEmpty()) {
            item { SettingRow(stringResource(R.string.searching_devices), icon = Icons.Default.Refresh, onClick = { c.refreshDevices(clear = true) }) }
        }
        items(renderers + servers.filter { s -> renderers.none { it.udn == s.udn } }, key = { "d" + it.udn }) { d ->
            DeviceRow(d, settings.rendererUdn == d.udn || settings.serverUdn == d.udn) {
                nav.navigate(if (d.isRenderer) SettingsRoutes.device(d.udn) else SettingsRoutes.server(d.udn))
            }
        }
        item { SettingRow(stringResource(R.string.refresh), icon = Icons.Default.Sync, onClick = { c.refreshDevices(clear = true) }) }
        item { SettingRow(stringResource(R.string.setup_guide), stringResource(R.string.setup_guide_desc), Icons.Default.Tune, onClick = { nav.navigate(SettingsRoutes.SETUP) }) }

        item { SectionHeader(stringResource(R.string.settings_library)) }
        items(servers, key = { "s" + it.udn }) { s ->
            SwitchRow(stringResource(R.string.using_this_library) + ": " + s.name, s.model, settings.serverUdn == s.udn, if (s.isLightning) Icons.Default.Star else Icons.Default.Dns) { on ->
                c.selectServer(if (on) s.udn else null)
            }
        }
        item { SettingRow(stringResource(R.string.refresh_cache), icon = Icons.Default.Refresh, onClick = { c.syncLibrary() }) }
        item { SettingRow(stringResource(R.string.missing_info_title), stringResource(R.string.missing_info_desc), Icons.Default.Tune, onClick = { nav.navigate(com.lightningds.app.ui.library.EditRoutes.MISSING) }) }
        item { SettingRow(stringResource(R.string.smb_title), settings.smbShare.takeIf { it.isNotBlank() }?.let { "\\\\" + c.smbHost() + "\\" + it } ?: stringResource(R.string.smb_desc_short), Icons.Default.Dns, onClick = { dialog = "smb" }) }
        item {
            SettingRow(
                stringResource(R.string.clear_cache),
                stringResource(R.string.cache_size, Formatter.formatShortFileSize(context, c.libraryCacheSize())),
                Icons.Default.Delete, onClick = { dialog = "clear" },
            )
        }
        item { SettingRow(stringResource(R.string.sorting), settings.sort.label() + " · " + directionLabel(settings.sort, settings.sortDescending), Icons.AutoMirrored.Filled.Sort, onClick = { dialog = "sort" }) }
        item {
            SettingRow(stringResource(R.string.sort_direction), directionLabel(settings.sort, settings.sortDescending), Icons.Default.SwapVert,
                onClick = { c.settings.setSortDescending(!settings.sortDescending) })
        }
        item { SettingRow(stringResource(R.string.text_alignment), stringResource(if (settings.textAlign == TextAlign.CENTER) R.string.align_center else R.string.align_start), Icons.Default.FormatAlignCenter, onClick = { dialog = "align" }) }

        item { SectionHeader(stringResource(R.string.settings_streaming)) }
        item { SettingRow(stringResource(R.string.settings_streaming), "TIDAL · Qobuz", Icons.Default.Cloud, onClick = { dialog = "streaming" }) }

        item { SectionHeader(stringResource(R.string.settings_general)) }
        item {
            SettingRow(stringResource(R.string.theme), stringResource(when (settings.themeMode) {
                ThemeMode.SYSTEM -> R.string.theme_system
                ThemeMode.LIGHT -> R.string.theme_light
                ThemeMode.DARK -> R.string.theme_dark
            }), Icons.Default.Brightness6, onClick = { dialog = "theme" })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item { SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_desc), settings.dynamicColor, Icons.Default.Palette) { c.settings.setDynamicColor(it) } }
        }
        item {
            SettingRow(stringResource(R.string.language), stringResource(R.string.language_desc), Icons.Default.Language, onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                } else {
                    Intent(Settings.ACTION_LOCALE_SETTINGS)
                }
                runCatching { context.startActivity(intent) }
            })
        }
        item { SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_desc), settings.keepScreenOn, Icons.Default.ScreenLockPortrait) { c.settings.setKeepScreenOn(it) } }

        item { SectionHeader(stringResource(R.string.settings_about)) }
        item {
            SettingRow(stringResource(R.string.user_guide), null, Icons.AutoMirrored.Filled.Help, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_URL))) }
            })
        }
        item {
            SettingRow(stringResource(R.string.feedback), null, Icons.Default.Feedback, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL))) }
            })
        }
        item {
            SettingRow(stringResource(R.string.update_check), stringResource(R.string.update_current, c.updater.currentVersion), Icons.Default.SystemUpdate,
                onClick = { c.launchIo { c.updater.check(quiet = false) } })
        }
        item { SwitchRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_desc), settings.autoUpdateCheck, Icons.Default.Sync) { c.settings.setAutoUpdateCheck(it) } }
        item { SettingRow(stringResource(R.string.app_name), stringResource(R.string.version, BuildConfigCompat.versionName(context)) + "\n" + stringResource(R.string.about_text), Icons.Default.Info) }
    }
}

const val USER_GUIDE_URL = "https://github.com/NonSpecific44/LightningDS-Modern-Android#readme"
const val FEEDBACK_URL = "https://github.com/NonSpecific44/LightningDS-Modern-Android/issues"

@Composable
private fun DeviceRow(d: DeviceItem, selected: Boolean, onClick: () -> Unit) {
    SettingRow(
        title = d.name,
        subtitle = listOfNotNull(
            d.model,
            stringResource(if (d.isLightning) R.string.lightning_device else if (d.isOpenHome) R.string.openhome_device else R.string.upnp_device),
            d.host,
        ).joinToString(" · "),
        icon = if (d.isRenderer) Icons.Default.Speaker else Icons.Default.Dns,
        onClick = onClick,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (d.protected) Icon(Icons.Default.Lock, null)
                if (selected) Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun StreamingCredentialsDialog(onDismiss: () -> Unit) {
    val c = LocalController.current
    val s = c.settings.current
    var qobuz by remember { mutableStateOf(s.qobuzAppId) }
    var tidalId by remember { mutableStateOf(s.tidalClientId) }
    var tidalSecret by remember { mutableStateOf(s.tidalClientSecret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_streaming)) },
        text = {
            Column(Modifier.widthIn(max = 480.dp)) {
                Text(stringResource(R.string.streaming_credentials_needed), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(tidalId, { tidalId = it }, label = { Text(stringResource(R.string.tidal_client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(tidalSecret, { tidalSecret = it }, label = { Text(stringResource(R.string.tidal_client_secret)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(qobuz, { qobuz = it }, label = { Text(stringResource(R.string.qobuz_app_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.settings.setQobuzAppId(qobuz)
                c.settings.setTidalClient(tidalId, tidalSecret)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
