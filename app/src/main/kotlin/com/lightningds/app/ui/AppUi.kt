package com.lightningds.app.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lightningds.app.DeviceItem
import com.lightningds.app.R
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.Navigator
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.inputs.InputsScreen
import com.lightningds.app.ui.library.AlbumScreen
import com.lightningds.app.ui.library.FolderBrowser
import com.lightningds.app.ui.library.GroupScreen
import com.lightningds.app.ui.library.LibraryScreen
import com.lightningds.app.ui.library.LibraryTab
import com.lightningds.app.ui.library.LocalPlaylistScreen
import com.lightningds.app.ui.player.MiniPlayer
import com.lightningds.app.ui.player.NowPlayingContent
import com.lightningds.app.ui.player.QueueContent
import com.lightningds.app.ui.radio.RadioCountryScreen
import com.lightningds.app.ui.radio.RadioGenreScreen
import com.lightningds.app.ui.radio.RadioScreen
import com.lightningds.app.ui.search.SearchScreen
import com.lightningds.app.ui.settings.DeviceSettingsScreen
import com.lightningds.app.ui.settings.NetworkSettingsScreen
import com.lightningds.app.ui.settings.ServerSettingsScreen
import com.lightningds.app.ui.settings.SettingsScreen
import com.lightningds.app.ui.setup.SetupGuide
import com.lightningds.app.ui.streaming.StreamAlbumScreen
import com.lightningds.app.ui.streaming.StreamArtistScreen
import com.lightningds.app.ui.streaming.StreamGenreScreen
import com.lightningds.app.ui.streaming.StreamPlaylistScreen
import com.lightningds.app.ui.streaming.StreamingScreen

private enum class TopLevel(val route: String, val label: Int, val icon: ImageVector) {
    LIBRARY("library", R.string.nav_library, Icons.Default.LibraryMusic),
    STREAMING("streaming", R.string.nav_streaming, Icons.Default.Cloud),
    RADIO("radio", R.string.nav_radio, Icons.Default.Radio),
    INPUTS("inputs", R.string.nav_inputs, Icons.Default.Input),
    SEARCH("search", R.string.nav_search, Icons.Default.Search),
}

private fun NavBackStackEntry.arg(name: String): String = Uri.decode(arguments?.getString(name).orEmpty())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningAppUi() {
    val c = LocalController.current
    val settings by c.settings.settings.collectAsStateCompat()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    var showDevices by remember { mutableStateOf(false) }
    var onWifi by remember { mutableStateOf(c.isOnWifi()) }

    LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it) } }

    if (!settings.onboardingDone) {
        Surface(Modifier.fillMaxSize()) {
            SetupGuide(onFinish = { c.settings.setOnboardingDone(true) })
        }
        return
    }
    if (!onWifi) {
        Surface(Modifier.fillMaxSize()) {
            EmptyState(Icons.Default.WifiOff, stringResource(R.string.no_wifi_title) + "\n\n" + stringResource(R.string.no_wifi_message)) {
                Button(onClick = { onWifi = c.isOnWifi(); if (onWifi) c.refreshDevices() }) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = { onWifi = true }) { Text(stringResource(R.string.just_start)) }
            }
        }
        return
    }

    val navigator = remember(nav) {
        Navigator(navigate = { nav.navigate(it) { launchSingleTop = true } }, back = { nav.popBackStack() })
    }
    val topLevel = TopLevel.entries.firstOrNull { it.route == route }
    val currentTop = topLevel ?: TopLevel.entries.firstOrNull { t -> nav.currentBackStack.value.any { it.destination.route == t.route } } ?: TopLevel.LIBRARY

    PasscodeDialogHost()
    if (showDevices) DevicePickerSheet(onDismiss = { showDevices = false }, onSettings = { showDevices = false; navigator.navigate("settings") })

    androidx.compose.runtime.CompositionLocalProvider(LocalNavigator provides navigator) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 1000.dp
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    TopLevel.entries.forEach { t ->
                        item(
                            selected = t == currentTop,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                },
            ) {
                Row(Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        snackbarHost = { SnackbarHost(snackbar) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        titleFor(route, backStack),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    if (topLevel == null && nav.previousBackStackEntry != null) {
                                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                                    }
                                },
                                actions = {
                                    DeviceChip { showDevices = true }
                                    if (route != "settings") IconButton(onClick = { navigator.navigate("settings") }) { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) }
                                },
                            )
                        },
                        bottomBar = {
                            if (!expanded && route != "player" && route != "queue") MiniPlayer(onOpen = { navigator.navigate("player") })
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding).fillMaxSize()) {
                            AppNavHost(nav, expanded)
                        }
                    }
                    if (expanded) {
                        VerticalDivider()
                        NowPlayingPane(Modifier.width(400.dp).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun titleFor(route: String?, entry: NavBackStackEntry?): String = when {
    route == null -> stringResource(R.string.app_name)
    route == "library" -> stringResource(R.string.music_library)
    route == "streaming" -> stringResource(R.string.streaming_music)
    route == "radio" -> stringResource(R.string.radio)
    route == "inputs" -> stringResource(R.string.nav_inputs)
    route == "search" -> stringResource(R.string.nav_search)
    route == "settings" -> stringResource(R.string.nav_settings)
    route == "player" -> stringResource(R.string.now_playing)
    route == "queue" -> stringResource(R.string.queue)
    route == "setup" -> stringResource(R.string.setup_guide)
    route.startsWith("group/") -> entry?.arg("key").orEmpty()
    route.startsWith("folder/") -> entry?.arg("title").orEmpty()
    route.startsWith("stream/genre/") -> entry?.arg("name").orEmpty()
    route.startsWith("radio/country/") -> entry?.arg("name").orEmpty()
    route.startsWith("radio/genre/") -> entry?.arg("tag").orEmpty().replaceFirstChar { it.titlecase() }
    route.startsWith("settings/network") -> stringResource(R.string.network)
    route.startsWith("settings/server") -> stringResource(R.string.server_settings)
    route.startsWith("settings/") -> stringResource(R.string.settings_devices)
    else -> stringResource(R.string.app_name)
}

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController, expanded: Boolean) {
    val navigator = LocalNavigator.current
    NavHost(nav, startDestination = "library") {
        composable("library") { LibraryScreen() }
        composable("streaming") { StreamingScreen() }
        composable("radio") { RadioScreen() }
        composable("inputs") { InputsScreen() }
        composable("search") { SearchScreen() }
        composable("settings") { SettingsScreen() }
        composable("player") {
            if (expanded) QueueContent(Modifier.fillMaxSize()) else NowPlayingContent(onOpenQueue = { navigator.navigate("queue") })
        }
        composable("queue") { QueueContent(Modifier.fillMaxSize()) }
        composable("setup") { SetupGuide(onFinish = { navigator.back() }) }
        composable("album/{id}") { AlbumScreen(it.arg("id")) }
        composable("group/{tab}/{key}") { e ->
            GroupScreen(runCatching { LibraryTab.valueOf(e.arg("tab")) }.getOrDefault(LibraryTab.ARTISTS), e.arg("key"))
        }
        composable("folder/{id}/{title}") { e -> FolderBrowser(e.arg("id"), e.arg("title")) }
        composable("playlist/{id}") { LocalPlaylistScreen(it.arg("id")) }
        composable("stream/album/{service}/{id}") { e -> StreamAlbumScreen(e.arg("service"), e.arg("id")) }
        composable("stream/artist/{service}/{id}") { e -> StreamArtistScreen(e.arg("service"), e.arg("id")) }
        composable("stream/playlist/{service}/{id}") { e -> StreamPlaylistScreen(e.arg("service"), e.arg("id")) }
        composable("stream/genre/{service}/{id}/{name}") { e -> StreamGenreScreen(e.arg("service"), e.arg("id"), e.arg("name")) }
        composable("radio/genre/{tag}") { e -> RadioGenreScreen(e.arg("tag")) }
        composable("radio/country/{code}/{name}") { e -> RadioCountryScreen(e.arg("code")) }
        composable("settings/device/{udn}") { e -> DeviceSettingsScreen(e.arg("udn")) }
        composable("settings/server/{udn}") { e -> ServerSettingsScreen(e.arg("udn")) }
        composable("settings/network/{udn}") { e -> NetworkSettingsScreen(e.arg("udn")) }
    }
}

/** Tablet side pane: now playing and queue, always visible. */
@Composable
private fun NowPlayingPane(modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.now_playing)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.queue)) })
            }
            if (tab == 0) NowPlayingContent(onOpenQueue = { tab = 1 }) else QueueContent(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DeviceChip(onClick: () -> Unit) {
    val c = LocalController.current
    val selected by c.selectedRenderer.collectAsStateCompat()
    val state by c.rendererState.collectAsStateCompat()
    TextButton(onClick = onClick, modifier = Modifier.widthIn(max = 220.dp)) {
        Icon(Icons.Default.Speaker, null)
        Text(
            selected?.name ?: state?.name ?: stringResource(R.string.choose_device),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
        )
        Icon(Icons.Default.ArrowDropDown, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val c = LocalController.current
    val renderers by c.renderers.collectAsStateCompat()
    val servers by c.servers.collectAsStateCompat()
    val settings by c.settings.settings.collectAsStateCompat()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.renderers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { c.refreshDevices(clear = true) }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) }
                }
            }
            if (renderers.isEmpty()) {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Text(stringResource(R.string.no_devices_found), Modifier.padding(start = 16.dp))
                    }
                }
            }
            items(renderers, key = { "r" + it.udn }) { d ->
                DeviceListItem(d, d.udn == settings.rendererUdn) {
                    c.selectRenderer(d)
                    onDismiss()
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Text(stringResource(R.string.servers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(servers, key = { "s" + it.udn }) { d ->
                DeviceListItem(d, d.udn == settings.serverUdn) {
                    c.selectServer(d.udn)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun DeviceListItem(d: DeviceItem, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Default.Speaker, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(d.model, stringResource(if (d.isLightning) R.string.lightning_device else if (d.isOpenHome) R.string.openhome_device else R.string.upnp_device)).joinToString(" · "))
        },
        trailingContent = { if (d.protected) Icon(Icons.Default.Lock, null) },
    )
}

@Composable
private fun PasscodeDialogHost() {
    val c = LocalController.current
    val req by c.passcodeRequest.collectAsStateCompat()
    val r = req ?: return
    var code by remember(r) { mutableStateOf("") }
    var wrong by remember(r) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { c.cancelPasscode() },
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.passcode_title)) },
        text = {
            Column {
                Text(stringResource(R.string.passcode_prompt, r.name))
                OutlinedTextField(
                    code, { if (it.length <= 4 && it.all(Char::isDigit)) code = it },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.passcode_wrong)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = code.length == 4, onClick = { c.submitPasscode(code) { ok -> wrong = !ok; if (!ok) code = "" } }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { c.cancelPasscode() }) { Text(stringResource(R.string.cancel)) } },
    )
}
