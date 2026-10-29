package com.lightningds.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.TextInputDialog
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.core.lightning.DsdStatus
import com.lightningds.core.lightning.HardwareInfo
import com.lightningds.core.lightning.LightningConfig
import com.lightningds.core.lightning.NetInterfaces
import com.lightningds.core.lightning.OutputChannels
import com.lightningds.core.lightning.PasscodeState
import com.lightningds.core.lightning.UpdateInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private data class DeviceConfig(
    val info: HardwareInfo?,
    val passcode: PasscodeState?,
    val mode: String?,
    val net: NetInterfaces?,
    val delay: Int?,
    val outputs: OutputChannels?,
    val dsd: DsdStatus?,
    val volumeControl: Boolean?,
)

private suspend fun load(cfg: LightningConfig): DeviceConfig = coroutineScope {
    val info = async { runCatching { cfg.hardwareInfo() }.getOrNull() }
    val pass = async { runCatching { cfg.passcode() }.getOrNull() }
    val mode = async { runCatching { cfg.upnpType() }.getOrNull() }
    val net = async { runCatching { cfg.netInterfaces() }.getOrNull() }
    val delay = async { runCatching { cfg.waitingTime() }.getOrNull() }
    val out = async { runCatching { cfg.outputChannels() }.getOrNull() }
    val dsd = async { runCatching { cfg.dsdStatus() }.getOrNull() }
    val vol = async { runCatching { cfg.volumeControl() }.getOrNull() }
    DeviceConfig(info.await(), pass.await(), mode.await(), net.await(), delay.await(), out.await(), dsd.await(), vol.await())
}

@Composable
fun DeviceSettingsScreen(udn: String) {
    val c = LocalController.current
    val nav = LocalNavigator.current
    val devices by c.devices.collectAsStateCompat()
    val item = devices.firstOrNull { it.udn == udn }
    val cfg = remember(udn, item != null) { c.lightningConfig(udn) }
    val unknown = stringResource(R.string.unknown)

    if (cfg == null) {
        Column(Modifier.fillMaxSize()) {
            if (item != null) {
                SettingRow(item.name, listOfNotNull(item.manufacturer, item.model, item.host).joinToString(" · "), Icons.Default.Info)
                if (item.isRenderer) SettingRow(stringResource(R.string.renderers), stringResource(if (item.isOpenHome) R.string.openhome_device else R.string.upnp_device))
            }
            EmptyState(Icons.Default.Tune, stringResource(R.string.not_lightning_device))
        }
        return
    }

    val loader = rememberLoad(udn) { load(cfg) }
    var dialog by remember { mutableStateOf<String?>(null) }

    fun change(block: suspend () -> Unit) = c.launchIo {
        block()
        delay(400)
        loader.reload()
    }

    LoadContent(loader) { d ->
        when (dialog) {
            "name" -> TextInputDialog(stringResource(R.string.device_name), initial = item?.name.orEmpty(), onDismiss = { dialog = null }, onConfirm = {
                dialog = null
                change { cfg.setRoomName(it.trim()) }
            })
            "mode" -> ChoiceDialog(
                stringResource(R.string.operating_mode),
                listOf(LightningConfig.MODE_OPENHOME to stringResource(R.string.mode_openhome), LightningConfig.MODE_UPNP to stringResource(R.string.mode_upnp)),
                d.mode, { dialog = null }, { m -> change { cfg.setUpnpType(m) }; c.toast(c.string(R.string.mode_change_note)) },
            )
            "delay" -> ChoiceDialog(
                stringResource(R.string.dac_delay),
                LightningConfig.DAC_DELAYS.map { it to if (it == 0) c.string(R.string.off) else c.resources().getQuantityString(R.plurals.seconds, it, it) },
                d.delay, { dialog = null }, { s -> change { cfg.setWaitingTime(s) } },
            )
            "output" -> ChoiceDialog(
                stringResource(R.string.output_channel),
                d.outputs?.channels.orEmpty().map { it.device to it.name.ifBlank { it.device } },
                d.outputs?.current, { dialog = null }, { ch -> change { cfg.setOutputChannel(ch) } },
            )
            "passcode" -> PasscodeSetupDialog(onDismiss = { dialog = null }) { code ->
                dialog = null
                change {
                    cfg.setPasscode(true, code)
                    c.settings.setUnlocked(udn, code)
                }
            }
            "firmware" -> FirmwareDialog(cfg) { dialog = null }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.device_name)) }
            item { SettingRow(item?.name ?: unknown, stringResource(R.string.edit), Icons.Default.Edit, onClick = { dialog = "name" }) }

            item { SectionHeader(stringResource(R.string.device_passcode)) }
            item {
                SwitchRow(stringResource(R.string.passcode), null, d.passcode?.protected == true, Icons.Default.Lock, enabled = d.passcode != null) { on ->
                    if (on) dialog = "passcode" else change { cfg.setPasscode(false, d.passcode?.passcode.orEmpty()) }
                }
            }
            if (d.passcode?.protected == true) item { SettingRow(stringResource(R.string.change_passcode), null, Icons.Default.Password, onClick = { dialog = "passcode" }) }

            item { SectionHeader(stringResource(R.string.operating_mode)) }
            item {
                SettingRow(
                    stringResource(R.string.operating_mode),
                    when {
                        d.mode == null -> unknown
                        d.mode.equals(LightningConfig.MODE_UPNP, true) -> stringResource(R.string.mode_upnp)
                        else -> stringResource(R.string.mode_openhome)
                    },
                    Icons.Default.SettingsInputComponent, onClick = { dialog = "mode" },
                )
            }

            item { SectionHeader(stringResource(R.string.network)) }
            item {
                val current = d.net?.interfaces?.firstOrNull { it.name == d.net.current }
                SettingRow(
                    stringResource(R.string.network),
                    when {
                        current == null -> d.net?.current?.ifBlank { null } ?: stringResource(R.string.network_not_connected)
                        current.isWireless -> stringResource(R.string.network_wireless) + " (${current.name})"
                        else -> stringResource(R.string.network_wired) + " (${current.name})"
                    },
                    Icons.Default.Wifi, onClick = { nav.navigate(SettingsRoutes.network(udn)) },
                )
            }

            item { SectionHeader(stringResource(R.string.dac_delay)) }
            item {
                SettingRow(
                    stringResource(R.string.dac_delay),
                    d.delay?.let { if (it == 0) stringResource(R.string.off) else pluralStringResource(R.plurals.seconds, it, it) } ?: unknown,
                    Icons.Default.Timer, onClick = { dialog = "delay" },
                )
            }

            item { SectionHeader(stringResource(R.string.output)) }
            item {
                SettingRow(
                    stringResource(R.string.output_channel),
                    d.outputs?.let { o -> o.channels.firstOrNull { it.device == o.current }?.name ?: o.current } ?: unknown,
                    Icons.Default.Cable, onClick = { if (d.outputs?.channels?.isNotEmpty() == true) dialog = "output" },
                )
            }
            item {
                SwitchRow(stringResource(R.string.native_dsd), stringResource(R.string.native_dsd_desc), d.dsd?.nativeEnabled == true, Icons.Default.GraphicEq, enabled = d.dsd != null) { on ->
                    change { cfg.setDsdStatus(on, d.dsd?.sampleFormat.orEmpty()) }
                }
            }
            item {
                SwitchRow(stringResource(R.string.software_volume), stringResource(R.string.software_volume_desc), d.volumeControl == true, Icons.AutoMirrored.Filled.VolumeUp, enabled = d.volumeControl != null) { on ->
                    change {
                        cfg.setVolumeControl(on)
                        c.refreshVolumeControl()
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.device_info)) }
            val info = d.info
            val rows = listOf(
                R.string.brand to info?.manufacturer,
                R.string.model to info?.model,
                R.string.serial_number to info?.serial,
                R.string.core_version to info?.coreVersion,
                R.string.wifi_version to info?.wifiVersion,
                R.string.main_version to info?.mainVersion,
                R.string.firmware_version to info?.firmware,
                R.string.lan_mac to info?.lanMac,
                R.string.wlan_mac to info?.wlanMac,
                R.string.ip to item?.host,
            ).filter { !it.second.isNullOrBlank() }
            rows.forEach { (label, value) -> item { SettingRow(stringResource(label), value, Icons.Default.Badge) } }

            item { SectionHeader(stringResource(R.string.firmware)) }
            item { SettingRow(stringResource(R.string.firmware), info?.firmware?.let { stringResource(R.string.version, it) }, Icons.Default.SystemUpdate, onClick = { dialog = "firmware" }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Two-step 4-digit passcode entry, like the original keypad screen. */
@Composable
fun PasscodeSetupDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.passcode_mismatch)
    val length = stringResource(R.string.passcode_length)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_passcode)) },
        text = {
            Column {
                Text(stringResource(if (first == null) R.string.passcode_new else R.string.passcode_again))
                OutlinedTextField(
                    code, { if (it.length <= 4 && it.all(Char::isDigit)) code = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    code.length != 4 -> error = length
                    first == null -> { first = code; code = ""; error = null }
                    first != code -> { first = null; code = ""; error = mismatch }
                    else -> onSet(code)
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FirmwareDialog(cfg: LightningConfig, onDismiss: () -> Unit) {
    val c = LocalController.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cfg.updateInfo() } }
            .onSuccess { info = it }.onFailure { error = it.message }
    }
    LaunchedEffect(updating) {
        while (updating) {
            delay(2000)
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cfg.updateInfo() } }.onSuccess { info = it }
        }
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.firmware_update), stringResource(R.string.firmware_update_confirm), { confirm = false }, {
            updating = true
            c.launchIo { cfg.applyUpdate() }
        }, confirmLabel = stringResource(R.string.firmware_update))
    }
    val i = info
    val available = i != null && i.version.isNotBlank() && !i.version.equals("none", true) && !i.version.equals("0", true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.firmware)) },
        text = {
            Column {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    i == null -> {
                        Text(stringResource(R.string.firmware_checking))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    updating && i.progress in 0..100 -> {
                        Text(stringResource(R.string.firmware_downloading, i.progress))
                        LinearProgressIndicator(progress = { i.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    available -> Text(stringResource(R.string.firmware_available, i.version))
                    else -> Text(stringResource(R.string.firmware_up_to_date))
                }
            }
        },
        confirmButton = {
            Row {
                if (available && !updating) Button(onClick = { confirm = true }) { Text(stringResource(R.string.firmware_update)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
