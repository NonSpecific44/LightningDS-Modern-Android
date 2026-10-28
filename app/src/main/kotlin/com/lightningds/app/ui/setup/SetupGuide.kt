package com.lightningds.app.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightningds.app.LibraryState
import com.lightningds.app.R
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.library.SyncProgressView
import com.lightningds.core.lightning.OutputChannel
import com.lightningds.core.lightning.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step {
    WELCOME, PRODUCT, AUDIO, POWER, NETWORK, FIND, NAME, OUTPUT, VOLUME, DSD, ACTIVATE, UPDATE, DEVICE_DONE,
    SERVER_ENABLE, SERVER_PICK, STORAGE, SMB, USB, SCANNING, IMPORT, LIBRARY_DONE,
}

/**
 * Setup guide: walks through the same steps as Lightning DS 1.x ("GuideRender" and "GuideServer"
 * screens), applying each choice to the device as the user goes.
 */
@Composable
fun SetupGuide(onFinish: () -> Unit) {
    val c = LocalController.current
    val renderers by c.renderers.collectAsStateCompat()
    val servers by c.servers.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    val library by c.library.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf(Step.WELCOME) }
    val step = history.last()
    var product by remember { mutableStateOf<String?>(null) }
    var deviceUdn by remember { mutableStateOf(settings.rendererUdn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun go(s: Step) { error = null; history.add(s) }
    fun back() { if (history.size > 1) history.removeAt(history.lastIndex) else onFinish() }
    fun finishSetup() { c.settings.setOnboardingDone(true); onFinish() }
    val cfg = c.lightningConfig(deviceUdn)
    val productName = product ?: stringResource(R.string.product_aries)
    /** Runs a device change, then advances; errors stay on the page (the "Operation failed" screens). */
    fun applyThen(next: Step, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            busy = false
            r.onSuccess { go(next) }.onFailure { error = c.string(R.string.operation_failed) + ": " + (it.message ?: "") }
        }
    }

    AnimatedContent(targetState = step, label = "setup") { s ->
        when (s) {
            Step.WELCOME -> Page(Icons.Default.Speaker, stringResource(R.string.welcome_title), stringResource(R.string.welcome_message)) {
                Button(onClick = { go(Step.PRODUCT) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_lightning_device)) }
                OutlinedButton(onClick = { go(Step.SERVER_ENABLE) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_music_library)) }
                TextButton(onClick = { finishSetup() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.just_start)) }
            }
            Step.PRODUCT -> Page(Icons.Default.Speaker, stringResource(R.string.select_product), null, onBack = ::back) {
                listOf(R.string.product_aries, R.string.product_aries_le, R.string.product_aries_mini, R.string.product_other).forEach { id ->
                    val name = stringResource(id)
                    OutlinedCard(onClick = { product = name; go(Step.AUDIO) }, Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.Speaker, null) })
                    }
                }
            }
            Step.AUDIO -> InfoPage(Icons.Default.Cable, stringResource(R.string.connect_audio_title), stringResource(R.string.connect_audio_message, productName), ::back) { go(Step.POWER) }
            Step.POWER -> InfoPage(Icons.Default.ElectricalServices, stringResource(R.string.connect_power_title), stringResource(R.string.connect_power_message, productName), ::back) { go(Step.NETWORK) }
            Step.NETWORK -> InfoPage(Icons.Default.Router, stringResource(R.string.connect_network_title), stringResource(R.string.connect_network_message), ::back) {
                c.refreshDevices()
                go(Step.FIND)
            }
            Step.FIND -> {
                val lightning = renderers.filter { it.isLightning }.ifEmpty { renderers }
                Page(Icons.Default.Search, stringResource(R.string.find_device_title), stringResource(R.string.find_device_message), onBack = ::back) {
                    if (lightning.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.searching_devices), Modifier.padding(start = 12.dp))
                        }
                        Text(stringResource(R.string.cant_find_device), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { c.refreshDevices(clear = true) }) { Text(stringResource(R.string.retry)) }
                    }
                    lightning.forEach { d ->
                        OutlinedCard(onClick = {
                            deviceUdn = d.udn
                            c.selectRenderer(d)
                            go(if (c.lightningConfig(d.udn) != null) Step.NAME else Step.DEVICE_DONE)
                        }, Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(d.name) },
                                supportingContent = { Text(listOfNotNull(d.model, d.host).joinToString(" · ")) },
                                leadingContent = { Icon(Icons.Default.Speaker, null) },
                            )
                        }
                    }
                }
            }
            Step.NAME -> {
                var custom by remember { mutableStateOf("") }
                var other by remember { mutableStateOf(false) }
                Page(Icons.Default.Edit, stringResource(R.string.name_device_title), null, onBack = ::back, error = error) {
                    listOf(R.string.room_living, R.string.room_bed, R.string.room_dining, R.string.room_kitchen).forEach { id ->
                        val name = stringResource(id)
                        OutlinedCard(onClick = { applyThen(Step.OUTPUT) { cfg?.setRoomName(name) } }, Modifier.fillMaxWidth(), enabled = !busy) {
                            ListItem(headlineContent = { Text(name) })
                        }
                    }
                    OutlinedCard(onClick = { other = true }, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(stringResource(R.string.room_other)) }) }
                    if (other) {
                        OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text(stringResource(R.string.device_name)) }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = custom.isNotBlank() && !busy, onClick = { applyThen(Step.OUTPUT) { cfg?.setRoomName(custom.trim()) } }) { Text(stringResource(R.string.next)) }
                    }
                    SkipButton { go(Step.OUTPUT) }
                }
            }
            Step.OUTPUT -> {
                var channels by remember { mutableStateOf<List<OutputChannel>?>(null) }
                var current by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(deviceUdn) {
                    runCatching { withContext(Dispatchers.IO) { cfg?.outputChannels() } }.getOrNull()?.let { channels = it.channels; current = it.current }
                    if (channels == null) channels = emptyList()
                }
                Page(Icons.Default.Cable, stringResource(R.string.select_output_title), null, onBack = ::back, error = error) {
                    when (val ch = channels) {
                        null -> CircularProgressIndicator()
                        else -> ch.forEach { o ->
                            ListItem(
                                modifier = Modifier.clickable(enabled = !busy) { applyThen(Step.VOLUME) { cfg?.setOutputChannel(o.device) } },
                                leadingContent = { RadioButton(selected = o.device == current, onClick = null) },
                                headlineContent = { Text(o.name.ifBlank { o.device }) },
                            )
                        }
                    }
                    SkipButton { go(Step.VOLUME) }
                }
            }
            Step.VOLUME -> YesNoPage(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.volume_control_title), stringResource(R.string.volume_control_message), busy, error, ::back,
                yes = { applyThen(Step.DSD) { cfg?.setVolumeControl(true); c.refreshVolumeControl() } },
                no = { applyThen(Step.DSD) { cfg?.setVolumeControl(false); c.refreshVolumeControl() } })
            Step.DSD -> YesNoPage(Icons.Default.GraphicEq, stringResource(R.string.dsd_title), stringResource(R.string.dsd_message), busy, error, ::back,
                yes = { applyThen(Step.ACTIVATE) { cfg?.let { it.setDsdStatus(true, runCatching { it.dsdStatus().sampleFormat }.getOrDefault("")) } } },
                no = { applyThen(Step.ACTIVATE) { cfg?.let { it.setDsdStatus(false, runCatching { it.dsdStatus().sampleFormat }.getOrDefault("")) } } })
            Step.ACTIVATE -> {
                var status by remember { mutableStateOf<String?>(null) }
                var name by remember { mutableStateOf("") }
                var email by remember { mutableStateOf("") }
                var news by remember { mutableStateOf(false) }
                LaunchedEffect(deviceUdn) {
                    status = runCatching { withContext(Dispatchers.IO) { cfg?.activeStatus() } }.getOrNull().orEmpty()
                }
                val active = status?.let { it.equals("active", true) || it.equals("1", true) || it.equals("true", true) || it.contains("activated", true) }
                LaunchedEffect(active) { if (active == true || (status != null && cfg == null)) go(Step.UPDATE) }
                Page(Icons.Default.VerifiedUser, stringResource(R.string.activate_title), stringResource(R.string.activate_message), onBack = ::back, error = error) {
                    if (status == null) CircularProgressIndicator()
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(stringResource(R.string.email)) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(news, { news = it })
                        Text(stringResource(R.string.newsletter))
                    }
                    Button(enabled = name.isNotBlank() && email.contains('@') && !busy, onClick = {
                        applyThen(Step.UPDATE) { cfg?.activate(name.trim(), email.trim(), news) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
                    SkipButton { go(Step.UPDATE) }
                }
            }
            Step.UPDATE -> {
                var text by remember { mutableStateOf<String?>(null) }
                var version by remember { mutableStateOf("") }
                var progress by remember { mutableStateOf(-1) }
                var updating by remember { mutableStateOf(false) }
                val upToDate = stringResource(R.string.firmware_up_to_date)
                val availableFmt = stringResource(R.string.firmware_available)
                LaunchedEffect(deviceUdn) {
                    val info = runCatching { withContext(Dispatchers.IO) { cfg?.updateInfo() } }.getOrNull()
                    version = info?.version.orEmpty()
                    text = if (version.isBlank() || version.equals("none", true) || version == "0") upToDate else availableFmt.format(version)
                }
                LaunchedEffect(updating) {
                    while (updating) {
                        delay(2000)
                        runCatching { withContext(Dispatchers.IO) { cfg?.updateInfo() } }.getOrNull()?.let { progress = it.progress }
                    }
                }
                Page(Icons.Default.SystemUpdate, stringResource(R.string.check_update_title), text ?: stringResource(R.string.firmware_checking), onBack = ::back) {
                    if (updating) {
                        Text(stringResource(R.string.firmware_downloading, progress.coerceAtLeast(0)))
                        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    } else if (text != null && text != upToDate) {
                        Text(stringResource(R.string.firmware_update_confirm), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { updating = true; c.launchIo { cfg?.applyUpdate() } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.firmware_update)) }
                    }
                    Button(onClick = { go(Step.DEVICE_DONE) }, Modifier.fillMaxWidth(), enabled = text != null) { Text(stringResource(R.string.next)) }
                }
            }
            Step.DEVICE_DONE -> Page(Icons.Default.CheckCircle, stringResource(R.string.setup_complete_title), stringResource(R.string.setup_complete_message)) {
                Button(onClick = { go(Step.SERVER_ENABLE) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_music_library)) }
                OutlinedButton(onClick = { finishSetup() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.finish)) }
            }
            Step.SERVER_ENABLE -> {
                val lightningServer = servers.firstOrNull { it.isLightning && (it.host == renderers.firstOrNull { r -> r.udn == deviceUdn }?.host || deviceUdn == null) }
                    ?: servers.firstOrNull { it.isLightning }
                Page(Icons.Default.Dns, stringResource(R.string.enable_server_title), stringResource(R.string.enable_server_message), onBack = ::back) {
                    Button(enabled = lightningServer != null, onClick = {
                        lightningServer?.let { c.selectServer(it.udn) }
                        go(Step.STORAGE)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.yes_setup_server)) }
                    OutlinedButton(onClick = { go(Step.SERVER_PICK) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.no_use_dlna)) }
                    if (lightningServer == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text(stringResource(R.string.searching_devices), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Step.SERVER_PICK -> Page(Icons.Default.Dns, stringResource(if (servers.isEmpty()) R.string.no_servers_title else R.string.found_servers_title),
                if (servers.isEmpty()) stringResource(R.string.no_servers_message) else null, onBack = ::back) {
                if (servers.isEmpty()) {
                    CircularProgressIndicator()
                    TextButton(onClick = { c.refreshDevices() }) { Text(stringResource(R.string.retry)) }
                }
                servers.forEach { srv ->
                    OutlinedCard(onClick = {
                        c.selectServer(srv.udn)
                        go(Step.IMPORT)
                    }, Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text(srv.name) }, supportingContent = { Text(listOfNotNull(srv.model, srv.host).joinToString(" · ")) }, leadingContent = { Icon(Icons.Default.Dns, null) })
                    }
                }
            }
            Step.STORAGE -> {
                val serverCfg = c.serverConfig(settings.serverUdn)
                Page(Icons.Default.LibraryMusic, stringResource(R.string.storage_title), null, onBack = ::back, error = error) {
                    val modes = remember { mutableStateOf<com.lightningds.core.lightning.WorkModes?>(null) }
                    LaunchedEffect(settings.serverUdn) { modes.value = runCatching { withContext(Dispatchers.IO) { serverCfg?.workModes() } }.getOrNull() }
                    fun modeFor(network: Boolean): String? = modes.value?.modes?.firstOrNull {
                        val net = it.type.contains("smb", true) || it.type.contains("net", true) || it.name.contains("network", true)
                        net == network
                    }?.type
                    OutlinedCard(onClick = { applyThen(Step.USB) { modeFor(false)?.let { serverCfg?.setWorkMode(it) } } }, Modifier.fillMaxWidth(), enabled = !busy) {
                        ListItem(headlineContent = { Text(stringResource(R.string.usb_drive)) }, leadingContent = { Icon(Icons.Default.Usb, null) })
                    }
                    OutlinedCard(onClick = { applyThen(Step.SMB) { modeFor(true)?.let { serverCfg?.setWorkMode(it) } } }, Modifier.fillMaxWidth(), enabled = !busy) {
                        ListItem(headlineContent = { Text(stringResource(R.string.network_folder)) }, leadingContent = { Icon(Icons.Default.FolderShared, null) })
                    }
                }
            }
            Step.USB -> InfoPage(Icons.Default.Usb, stringResource(R.string.usb_drive), stringResource(R.string.connect_usb_message), ::back) {
                c.launchIo { c.serverConfig(settings.serverUdn)?.let { it.mount(); it.rescan() } }
                go(Step.SCANNING)
            }
            Step.SMB -> {
                var addr by remember { mutableStateOf("smb://") }
                var user by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }
                Page(Icons.Default.FolderShared, stringResource(R.string.network_folder), null, onBack = ::back, error = error) {
                    OutlinedTextField(addr, { addr = it }, singleLine = true, label = { Text(stringResource(R.string.network_folder_path)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Button(enabled = addr.length > 6 && !busy, onClick = {
                        applyThen(Step.SCANNING) {
                            val sc = c.serverConfig(settings.serverUdn) ?: error(c.string(R.string.server_offline))
                            sc.setSmbConfig(SmbConfig(addr.trim(), user.trim(), pass))
                            sc.mount()
                            sc.rescan()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mount)) }
                }
            }
            Step.SCANNING -> {
                var progress by remember { mutableStateOf("") }
                var problem by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(settings.serverUdn) {
                    val sc = c.serverConfig(settings.serverUdn) ?: return@LaunchedEffect
                    while (true) {
                        val disk = runCatching { withContext(Dispatchers.IO) { sc.diskInfo() } }.getOrNull()
                        progress = runCatching { withContext(Dispatchers.IO) { sc.progress() } }.getOrDefault("")
                        problem = disk?.statusCode?.let { com.lightningds.core.lightning.LightningServerConfig.STATUS_MESSAGES[it] }
                        delay(3000)
                    }
                }
                Page(if (problem != null) Icons.Default.Error else Icons.Default.Search, stringResource(if (problem != null) R.string.server_error_title else R.string.scanning_title),
                    problem ?: stringResource(R.string.scanning_message), onBack = ::back) {
                    if (problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (progress.isNotBlank()) Text(stringResource(R.string.scan_progress, progress))
                    Button(onClick = { c.syncLibrary(); go(Step.IMPORT) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
                }
            }
            Step.IMPORT -> {
                LaunchedEffect(Unit) { if (library !is LibraryState.Syncing) c.syncLibrary() }
                when (val l = library) {
                    is LibraryState.Ready -> if (!l.updating) {
                        LaunchedEffect(Unit) { go(Step.LIBRARY_DONE) }
                    } else SyncProgressView(null)
                    is LibraryState.Syncing -> SyncProgressView(l.progress)
                    is LibraryState.Failed -> Page(Icons.Default.Error, stringResource(R.string.sync_data_fail), l.message, onBack = ::back) {
                        Button(onClick = { c.syncLibrary() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                    }
                    else -> SyncProgressView(null)
                }
            }
            Step.LIBRARY_DONE -> Page(Icons.Default.CheckCircle, stringResource(R.string.library_ready_title), stringResource(R.string.library_ready_message)) {
                Button(onClick = { finishSetup() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.finish)) }
            }
        }
    }
}

@Composable
private fun Page(
    icon: ImageVector,
    title: String,
    message: String?,
    onBack: (() -> Unit)? = null,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp))
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun InfoPage(icon: ImageVector, title: String, message: String, onBack: () -> Unit, onNext: () -> Unit) {
    Page(icon, title, message, onBack) {
        Button(onClick = onNext, Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
    }
}

@Composable
private fun YesNoPage(icon: ImageVector, title: String, message: String, busy: Boolean, error: String?, onBack: () -> Unit, yes: () -> Unit, no: () -> Unit) {
    Page(icon, title, message, onBack, error) {
        Button(onClick = yes, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.yes)) }
        OutlinedButton(onClick = no, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.no)) }
    }
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.skip)) }
}
