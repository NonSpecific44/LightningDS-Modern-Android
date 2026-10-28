package com.lightningds.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.lightningds.app.data.CollectionStore
import com.lightningds.app.data.LibraryCache
import com.lightningds.app.data.PlaylistStore
import com.lightningds.app.data.RadioStore
import com.lightningds.app.data.SearchHistoryStore
import com.lightningds.app.data.SettingsRepository
import com.lightningds.app.data.storeJson
import com.lightningds.core.library.ContentDirectory
import com.lightningds.core.library.Library
import com.lightningds.core.library.LibrarySync
import com.lightningds.core.library.SyncProgress
import com.lightningds.core.lightning.LightningConfig
import com.lightningds.core.lightning.LightningServerConfig
import com.lightningds.core.media.Track
import com.lightningds.core.radio.RadioDirectory
import com.lightningds.core.radio.RadioStation
import com.lightningds.core.renderer.InsertMode
import com.lightningds.core.renderer.OpenHomeRenderer
import com.lightningds.core.renderer.Renderer
import com.lightningds.core.renderer.RendererState
import com.lightningds.core.renderer.UpnpAvRenderer
import com.lightningds.core.streaming.QobuzService
import com.lightningds.core.streaming.QobuzSession
import com.lightningds.core.streaming.StreamingService
import com.lightningds.core.streaming.TidalService
import com.lightningds.core.streaming.TidalSession
import com.lightningds.core.upnp.ControlPoint
import com.lightningds.core.upnp.UpnpDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface

/** A discovered device as shown in device lists. */
data class DeviceItem(
    val udn: String,
    val name: String,
    val model: String?,
    val manufacturer: String?,
    val host: String?,
    val iconUrl: String?,
    val isRenderer: Boolean,
    val isServer: Boolean,
    val isLightning: Boolean,
    val isOpenHome: Boolean,
    val protected: Boolean,
)

sealed interface LibraryState {
    data object NoServer : LibraryState
    data object ServerOffline : LibraryState
    data class Syncing(val progress: SyncProgress?, val previous: Library?) : LibraryState
    data class Ready(val library: Library, val updating: Boolean = false) : LibraryState
    data class Failed(val message: String) : LibraryState
}

data class PasscodeRequest(val udn: String, val name: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppController(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsRepository(context, scope)
    private val dataDir = File(context.filesDir, "data")
    val playlists = PlaylistStore(dataDir, ioScope)
    val collection = CollectionStore(dataDir, ioScope)
    val radioStore = RadioStore(dataDir, ioScope)
    val searchHistory = SearchHistoryStore(dataDir, ioScope)
    private val libraryCache = LibraryCache(File(context.filesDir, "libraries"))

    val controlPoint = ControlPoint(networkInterface = ::wifiInterface)
    val http get() = controlPoint.http
    val radio = RadioDirectory(controlPoint.http)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun toast(msg: String) { _messages.tryEmit(msg) }
    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    fun resources(): android.content.res.Resources = context.resources

    // ---- streaming services ------------------------------------------------------------------

    val tidal = TidalService(
        controlPoint.http,
        clientId = { settings.current.tidalClientId },
        clientSecret = { settings.current.tidalClientSecret },
        session = settings.current.tidalSession?.let { runCatching { storeJson.decodeFromString(TidalSession.serializer(), it) }.getOrNull() },
        onSession = { s -> settings.setTidalSession(s?.let { storeJson.encodeToString(TidalSession.serializer(), it) }); bumpStreaming() },
    )
    val qobuz = QobuzService(
        controlPoint.http,
        appId = { settings.current.qobuzAppId },
        session = settings.current.qobuzSession?.let { runCatching { storeJson.decodeFromString(QobuzSession.serializer(), it) }.getOrNull() },
        onSession = { s -> settings.setQobuzSession(s?.let { storeJson.encodeToString(QobuzSession.serializer(), it) }); bumpStreaming() },
    )
    val streamingServices: List<StreamingService> = listOf(tidal, qobuz)
    private val _streamingVersion = MutableStateFlow(0)
    /** Changes whenever a streaming login state changes, so UI can recompose. */
    val streamingVersion: StateFlow<Int> = _streamingVersion.asStateFlow()
    private fun bumpStreaming() { _streamingVersion.value++ }
    fun streamingService(name: String?): StreamingService = streamingServices.firstOrNull { it.name == name } ?: tidal

    // ---- devices -----------------------------------------------------------------------------

    val devices: StateFlow<List<DeviceItem>> = controlPoint.devices.map { m ->
        m.values.map { it.toItem() }.sortedWith(compareByDescending<DeviceItem> { it.isLightning }.thenBy { it.name.lowercase() })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val renderers: StateFlow<List<DeviceItem>> = devices.map { l -> l.filter { it.isRenderer } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val servers: StateFlow<List<DeviceItem>> = devices.map { l -> l.filter { it.isServer } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun UpnpDevice.toItem() = DeviceItem(
        udn = udn,
        name = friendlyName.ifBlank { description.modelName ?: udn },
        model = description.modelName,
        manufacturer = description.manufacturer,
        host = host,
        iconUrl = description.bestIcon()?.url,
        isRenderer = isRenderer,
        isServer = isServer,
        isLightning = isLightningRenderer || isLightningServer,
        isOpenHome = isOpenHomeRenderer,
        protected = description.protect.equals("yes", true),
    )

    fun device(udn: String?): UpnpDevice? = controlPoint.device(udn)
    fun lightningConfig(udn: String?): LightningConfig? = device(udn)?.let { LightningConfig.of(it) }
    fun serverConfig(udn: String?): LightningServerConfig? = device(udn)?.let { LightningServerConfig.of(it) }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    fun onForeground() {
        if (started) return
        started = true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("LightningDS").apply { setReferenceCounted(false); acquire() }
        ioScope.launch { runCatching { controlPoint.start() }.onFailure { toast(it.message ?: "Discovery failed") } }
    }

    fun onBackground() {
        // Keep the control point alive (events keep the UI fresh when returning), but let the
        // radio sleep: release the multicast lock only.
        multicastLock?.release()
        multicastLock = null
        started = false
    }

    fun refreshDevices(clear: Boolean = false) = controlPoint.refresh(clear)

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun wifiInterface(): NetworkInterface? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        } ?: return null
        val name = cm.getLinkProperties(net)?.interfaceName ?: return null
        return runCatching { NetworkInterface.getByName(name) }.getOrNull()
    }

    // ---- renderer ----------------------------------------------------------------------------

    private val _renderer = MutableStateFlow<Renderer?>(null)
    val renderer: StateFlow<Renderer?> = _renderer.asStateFlow()

    private val _passcodeRequest = MutableStateFlow<PasscodeRequest?>(null)
    val passcodeRequest: StateFlow<PasscodeRequest?> = _passcodeRequest.asStateFlow()

    /** False when a Lightning device has software volume control switched off (fixed output). */
    private val _softwareVolume = MutableStateFlow(true)

    val rendererState: StateFlow<RendererState?> = combine(
        _renderer.flatMapLatest { it?.state ?: flowOf(null) },
        _softwareVolume,
    ) { s, sv -> s?.let { if (!sv) it.copy(volumeAvailable = false) else it } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val selectedRenderer: StateFlow<DeviceItem?> = combine(settings.settings, devices) { s, d ->
        d.firstOrNull { it.udn == s.rendererUdn }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // (Re)create the renderer whenever the selected device comes online.
        scope.launch {
            combine(settings.settings.map { it.rendererUdn }.distinctUntilChanged(), controlPoint.devices) { udn, map -> udn to map[udn] }
                .distinctUntilChanged { a, b -> a.first == b.first && (a.second == null) == (b.second == null) && a.second?.description?.location == b.second?.description?.location }
                .collect { (_, dev) -> attachRenderer(dev) }
        }
        scope.launch {
            combine(settings.settings.map { it.serverUdn }.distinctUntilChanged(), controlPoint.devices.map { it.keys }.distinctUntilChanged()) { udn, keys ->
                udn to (udn != null && udn in keys)
            }.distinctUntilChanged().collect { (udn, online) -> onServerChanged(udn, online) }
        }
    }

    private fun attachRenderer(dev: UpnpDevice?) {
        val current = _renderer.value
        if (dev == null) {
            return // keep last state; it reconnects when the device returns
        }
        current?.close()
        if (!dev.isRenderer) { _renderer.value = null; return }
        val r: Renderer = if (dev.isOpenHomeRenderer) OpenHomeRenderer(dev, controlPoint.events, ioScope)
        else UpnpAvRenderer(dev, controlPoint.events, ioScope)
        r.start()
        _renderer.value = r
        _softwareVolume.value = true
        LightningConfig.of(dev)?.let { cfg ->
            ioScope.launch { runCatching { cfg.volumeControl() }.onSuccess { _softwareVolume.value = it } }
        }
    }

    /** Re-reads the software volume control flag (after changing it in settings). */
    fun refreshVolumeControl() {
        val cfg = lightningConfig(settings.current.rendererUdn) ?: return
        ioScope.launch { runCatching { cfg.volumeControl() }.onSuccess { _softwareVolume.value = it } }
    }

    fun selectRenderer(item: DeviceItem) {
        ioScope.launch {
            val cfg = lightningConfig(item.udn)
            val pass = cfg?.let { runCatching { it.passcode() }.getOrNull() }
            if (pass != null && pass.protected && settings.unlockedPasscode(item.udn) != pass.passcode) {
                _passcodeRequest.value = PasscodeRequest(item.udn, item.name)
                return@launch
            }
            settings.setRenderer(item.udn)
        }
    }

    fun submitPasscode(code: String, onResult: (Boolean) -> Unit) {
        val req = _passcodeRequest.value ?: return
        ioScope.launch {
            val ok = runCatching { lightningConfig(req.udn)?.passcode()?.passcode == code }.getOrDefault(false)
            scope.launch {
                if (ok) {
                    settings.setUnlocked(req.udn, code)
                    settings.setRenderer(req.udn)
                    _passcodeRequest.value = null
                }
                onResult(ok)
            }
        }
    }

    fun cancelPasscode() { _passcodeRequest.value = null }

    /** Runs a renderer command, reporting errors as a message. */
    fun command(block: suspend Renderer.() -> Unit) {
        val r = _renderer.value
        if (r == null) {
            toast(context.getString(R.string.no_device_selected))
            return
        }
        ioScope.launch {
            try {
                r.block()
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.operation_failed))
            }
        }
    }

    fun play(tracks: List<Track>, mode: InsertMode, startAt: Int = 0) {
        val playable = tracks.filter { it.uri != null }
        if (playable.isEmpty()) return
        command {
            enqueue(playable, mode, startAt.coerceIn(0, playable.lastIndex))
            if (mode == InsertMode.ADD_NEXT || mode == InsertMode.ADD_LATER) {
                toast(context.resources.getQuantityString(R.plurals.added_to_queue, playable.size, playable.size))
            }
        }
    }

    fun playRadio(station: RadioStation) {
        radioStore.played(station)
        ioScope.launch { radio.reportClick(station) }
        command { playRadio(station.url, station.toTrack()) }
    }

    // ---- library -----------------------------------------------------------------------------

    private val _library = MutableStateFlow<LibraryState>(LibraryState.NoServer)
    val library: StateFlow<LibraryState> = _library.asStateFlow()
    private var syncJob: Job? = null
    private var loadedFor: String? = null

    val selectedServer: StateFlow<DeviceItem?> = combine(settings.settings, devices) { s, d ->
        d.firstOrNull { it.udn == s.serverUdn }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun onServerChanged(udn: String?, online: Boolean) {
        if (udn == null) {
            loadedFor = null
            _library.value = LibraryState.NoServer
            return
        }
        if (loadedFor == udn && _library.value is LibraryState.Ready) {
            if (online) checkForLibraryUpdate(udn)
            return
        }
        loadedFor = udn
        scope.launch {
            val cached = libraryCache.load(udn)
            when {
                cached != null -> {
                    _library.value = LibraryState.Ready(cached)
                    if (online) checkForLibraryUpdate(udn)
                }
                online -> syncLibrary()
                else -> _library.value = LibraryState.ServerOffline
            }
        }
    }

    private fun checkForLibraryUpdate(udn: String) {
        val cfg = serverConfig(udn) ?: return
        val ready = _library.value as? LibraryState.Ready ?: return
        ioScope.launch {
            val v = runCatching { cfg.scanVersion() }.getOrNull() ?: return@launch
            if (v.isNotBlank() && ready.library.scanVersion.isNotBlank() && v != ready.library.scanVersion) syncLibrary()
        }
    }

    fun selectServer(udn: String?) {
        settings.setServer(udn)
    }

    fun syncLibrary() {
        val udn = settings.current.serverUdn ?: return
        val dev = device(udn)
        val cd = dev?.let { ContentDirectory.of(it) }
        if (cd == null) {
            _library.value = LibraryState.ServerOffline
            return
        }
        syncJob?.cancel()
        val previous = (_library.value as? LibraryState.Ready)?.library
        _library.value = if (previous != null) LibraryState.Ready(previous, updating = true) else LibraryState.Syncing(null, null)
        syncJob = ioScope.launch {
            try {
                val lib = LibrarySync(cd, dev.friendlyName).run { p ->
                    if (previous == null) _library.value = LibraryState.Syncing(p, null)
                }
                val scan = serverConfig(udn)?.let { runCatching { it.scanVersion() }.getOrNull() }.orEmpty()
                val withScan = lib.copy(scanVersion = scan)
                libraryCache.save(withScan)
                _library.value = LibraryState.Ready(withScan)
                toast(context.getString(R.string.sync_data_success))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _library.value = if (previous != null) LibraryState.Ready(previous) else LibraryState.Failed(e.message ?: "")
                toast(context.getString(R.string.sync_data_fail))
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        onServerChanged(settings.current.serverUdn, false)
    }

    fun clearLibraryCache() {
        val udn = settings.current.serverUdn
        scope.launch {
            syncJob?.cancel()
            libraryCache.clear(null)
            loadedFor = null
            _library.value = if (udn == null) LibraryState.NoServer else LibraryState.Syncing(null, null)
            if (udn != null) syncLibrary()
            toast(context.getString(R.string.clear_cache_success))
        }
    }

    fun libraryCacheSize(): Long = libraryCache.sizeBytes()

    fun contentDirectory(): ContentDirectory? = device(settings.current.serverUdn)?.let { ContentDirectory.of(it) }

    fun launchIo(block: suspend CoroutineScope.() -> Unit) = ioScope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(e.message ?: context.getString(R.string.operation_failed))
        }
    }
}
