package com.lightningds.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.ConfirmDialog
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.core.lightning.IpConfig
import com.lightningds.core.lightning.LightningConfig
import com.lightningds.core.lightning.NetInterface
import com.lightningds.core.lightning.WirelessNetwork

private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

/** Device network setup: wired/wireless, DHCP or manual IP, Wi-Fi network selection. */
@Composable
fun NetworkSettingsScreen(udn: String) {
    val c = LocalController.current
    val cfg = remember(udn) { c.lightningConfig(udn) } ?: return EmptyState(Icons.Default.Router, stringResource(R.string.not_lightning_device))
    val ifaces = rememberLoad(udn, "ifaces") { cfg.netInterfaces() }
    LoadContent(ifaces) { net ->
        var selected by remember { mutableStateOf(net.interfaces.firstOrNull { it.name == net.current } ?: net.interfaces.firstOrNull()) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                net.interfaces.forEach { i ->
                    FilterChip(
                        selected = i == selected,
                        onClick = { selected = i },
                        label = { Text((if (i.isWireless) stringResource(R.string.network_wireless) else stringResource(R.string.network_wired)) + " · " + i.name) },
                        leadingIcon = { Icon(if (i.isWireless) Icons.Default.Wifi else Icons.Default.SettingsEthernet, null) },
                    )
                }
            }
            val sel = selected
            if (sel == null) EmptyState(Icons.Default.Router, stringResource(R.string.network_not_connected))
            else InterfaceView(cfg, sel, sel.name == net.current)
        }
    }
}

@Composable
private fun InterfaceView(cfg: LightningConfig, iface: NetInterface, active: Boolean) {
    val c = LocalController.current
    val ip = rememberLoad(iface.name, "ip") { runCatching { cfg.ipConfig(iface.name) }.getOrNull() }
    val wifi = if (iface.isWireless) rememberLoad(iface.name, "wifi") { cfg.wirelessList(iface.name) } else null
    var manual by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<WirelessNetwork?>(null) }
    var addOther by remember { mutableStateOf(false) }
    var pendingApply by remember { mutableStateOf<(() -> Unit)?>(null) }

    pendingApply?.let { action ->
        ConfirmDialog(stringResource(R.string.network), stringResource(R.string.network_apply_note), { pendingApply = null }, action)
    }

    LoadContent(ip) { config ->
        if (manual) {
            ManualIpDialog(config, onDismiss = { manual = false }) { new ->
                manual = false
                pendingApply = { c.launchIo { cfg.setNetwork(iface.name, new.ip, new.netmask, new.gateway, new.dns) } }
            }
        }
        joining?.let { n ->
            WifiJoinDialog(n.essid, n.encryption, n.encryptionType, editableSsid = false, onDismiss = { joining = null }) { ssid, pass, enc ->
                joining = null
                pendingApply = { c.launchIo { cfg.setNetwork(iface.name, ssid = ssid, password = pass, encryption = enc) } }
            }
        }
        if (addOther) {
            WifiJoinDialog("", true, "WPA2", editableSsid = true, onDismiss = { addOther = false }) { ssid, pass, enc ->
                addOther = false
                pendingApply = { c.launchIo { cfg.setNetwork(iface.name, ssid = ssid, password = pass, encryption = enc) } }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader(if (active) stringResource(R.string.connected) else iface.name) }
            if (config != null) {
                item { SettingRow(stringResource(R.string.dhcp), null, trailing = { if (config.dhcp) Icon(Icons.Default.Check, null) }, onClick = {
                    pendingApply = { c.launchIo { cfg.setNetwork(iface.name) } }
                }) }
                item { SettingRow(stringResource(R.string.ip_address), config.ip.ifBlank { "—" }) }
                item { SettingRow(stringResource(R.string.subnet_mask), config.netmask.ifBlank { "—" }) }
                item { SettingRow(stringResource(R.string.gateway), config.gateway.ifBlank { "—" }) }
                if (config.dns.isNotBlank()) item { SettingRow(stringResource(R.string.dns), config.dns) }
            }
            item { SettingRow(stringResource(R.string.edit_manually), null, Icons.Default.Edit, onClick = { manual = true }) }
            if (wifi != null) {
                item { SectionHeader(stringResource(R.string.wifi_networks)) }
                when (val w = wifi.value) {
                    is com.lightningds.app.ui.components.Load.Ok -> {
                        items(w.value.networks, key = { it.essid }) { n ->
                            ListItem(
                                modifier = Modifier.clickable { joining = n },
                                leadingContent = { Icon(Icons.Default.Wifi, null) },
                                headlineContent = { Text(n.essid) },
                                supportingContent = { Text(if (n.essid == w.value.current) stringResource(R.string.connected) else "${n.quality}%") },
                                trailingContent = { if (n.encryption) Icon(Icons.Default.Lock, null) },
                            )
                        }
                    }
                    is com.lightningds.app.ui.components.Load.Error -> item { Text(w.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    else -> item { Text(stringResource(R.string.loading), Modifier.padding(16.dp)) }
                }
                item { SettingRow(stringResource(R.string.wifi_other), null, Icons.Default.Add, onClick = { addOther = true }) }
            }
        }
    }
}

@Composable
private fun ManualIpDialog(current: IpConfig?, onDismiss: () -> Unit, onSave: (IpConfig) -> Unit) {
    var ip by remember { mutableStateOf(current?.ip.orEmpty()) }
    var mask by remember { mutableStateOf(current?.netmask?.ifBlank { null } ?: "255.255.255.0") }
    var gw by remember { mutableStateOf(current?.gateway.orEmpty()) }
    var dns by remember { mutableStateOf(current?.dns?.ifBlank { null } ?: current?.gateway.orEmpty()) }
    val valid = listOf(ip, mask, gw).all { IPV4.matches(it.trim()) } && (dns.isBlank() || IPV4.matches(dns.trim()))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_manually)) },
        text = {
            Column {
                listOf(
                    Triple(R.string.ip_address, ip) { v: String -> ip = v },
                    Triple(R.string.subnet_mask, mask) { v: String -> mask = v },
                    Triple(R.string.gateway, gw) { v: String -> gw = v },
                    Triple(R.string.dns, dns) { v: String -> dns = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value, set, singleLine = true, label = { Text(stringResource(label)) },
                        isError = value.isNotBlank() && !IPV4.matches(value.trim()),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                if (!valid) Text(stringResource(R.string.ip_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(IpConfig(false, ip.trim(), mask.trim(), gw.trim(), dns.trim())) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun WifiJoinDialog(
    ssid: String,
    secured: Boolean,
    encryption: String,
    editableSsid: Boolean,
    onDismiss: () -> Unit,
    onJoin: (ssid: String, password: String, encryption: String) -> Unit,
) {
    var name by remember { mutableStateOf(ssid) }
    var pass by remember { mutableStateOf("") }
    var enc by remember { mutableStateOf(encryption.ifBlank { if (secured) "WPA2" else "NONE" }) }
    val needsPass = enc != "NONE"
    val passError = needsPass && pass.isNotEmpty() && pass.length < 8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editableSsid) stringResource(R.string.wifi_add) else ssid) },
        text = {
            Column {
                if (editableSsid) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.ssid)) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.security), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NONE", "WEP", "WPA", "WPA2").forEach { e -> FilterChip(selected = e == enc, onClick = { enc = e }, label = { Text(e) }) }
                    }
                }
                if (needsPass) {
                    OutlinedTextField(
                        pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = passError,
                        supportingText = if (passError) ({ Text(stringResource(R.string.password_length_error)) }) else null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (!needsPass || pass.length >= 8 || enc == "WEP" && pass.isNotEmpty()),
                onClick = { onJoin(name.trim(), pass, enc) },
            ) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
