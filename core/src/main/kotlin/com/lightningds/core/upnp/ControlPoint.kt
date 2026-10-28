package com.lightningds.core.upnp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ServiceTypes {
    const val OH = "av-openhome-org"
    const val UPNP = "schemas-upnp-org"
}

/** A single logical UPnP device (root or embedded) and its services. */
class UpnpDevice(
    val description: DeviceDescription,
    http: OkHttpClient,
) {
    val udn: String get() = description.udn
    val friendlyName: String get() = description.friendlyName
    val host: String? get() = description.host

    val services: List<UpnpService> = description.services.map { UpnpService(it, http) }

    fun service(name: String, domain: String? = null): UpnpService? =
        services.filter { it.info.name == name && (domain == null || it.info.domain == domain) }
            .maxByOrNull { it.info.version }

    val isOpenHomeRenderer: Boolean get() = service("Product", ServiceTypes.OH) != null
    val isUpnpRenderer: Boolean get() = service("AVTransport", ServiceTypes.UPNP) != null
    val isRenderer: Boolean get() = isOpenHomeRenderer || isUpnpRenderer
    val isServer: Boolean get() = service("ContentDirectory", ServiceTypes.UPNP) != null

    /** Auralic Lightning devices expose the proprietary HardwareConfig/ServerConfig services. */
    val isLightningRenderer: Boolean get() = isRenderer && service("HardwareConfig") != null
    val isLightningServer: Boolean get() = isServer && service("ServerConfig") != null

    override fun toString(): String = "UpnpDevice($friendlyName, $udn)"
}

/**
 * Discovers devices and keeps an up-to-date registry.
 */
class ControlPoint(
    val http: OkHttpClient = defaultHttpClient(),
    networkInterface: () -> NetworkInterface? = { null },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val events = EventServer(http, scope)

    private val _devices = MutableStateFlow<Map<String, UpnpDevice>>(emptyMap())
    val devices: StateFlow<Map<String, UpnpDevice>> = _devices.asStateFlow()

    private val expiry = ConcurrentHashMap<String, Long>()
    private val locations = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private var maintenance: Job? = null

    private val ssdp = SsdpClient(scope, networkInterface) { onSsdp(it) }

    @Synchronized
    fun start() {
        ssdp.start()
        events.start()
        ssdp.search()
        if (maintenance == null) {
            maintenance = scope.launch {
                var tick = 0
                while (isActive) {
                    delay(15_000)
                    tick++
                    sweep()
                    if (tick % 4 == 0) ssdp.search()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        maintenance?.cancel()
        maintenance = null
        ssdp.stop()
        events.stop()
    }

    fun refresh(clear: Boolean = false) {
        if (clear) {
            _devices.value = emptyMap()
            expiry.clear()
            locations.clear()
        }
        ssdp.search()
    }

    fun device(udn: String?): UpnpDevice? = udn?.let { _devices.value[it] }

    private fun onSsdp(msg: SsdpMessage) {
        val udn = msg.udn ?: return
        when (msg.kind) {
            SsdpMessage.Kind.BYEBYE -> remove(udn)
            else -> {
                val location = msg.location ?: return
                val known = _devices.value.containsKey(udn) || _devices.value.values.any { d -> d.description.flatten().any { it.udn == udn } }
                expiry[udn] = System.currentTimeMillis() + (msg.maxAge.coerceAtLeast(60) * 1000L) + 30_000L
                if (known && locations[udn] == location) {
                    refreshExpiryFor(location, msg.maxAge)
                    return
                }
                if (!pending.add(location)) return
                scope.launch {
                    try {
                        fetch(location, msg.maxAge)
                    } catch (_: Exception) {
                    } finally {
                        pending.remove(location)
                    }
                }
            }
        }
    }

    private fun refreshExpiryFor(location: String, maxAge: Int) {
        val until = System.currentTimeMillis() + (maxAge.coerceAtLeast(60) * 1000L) + 30_000L
        locations.filterValues { it == location }.keys.forEach { expiry[it] = until }
    }

    /** Fetches and registers the device(s) described at [location]. */
    suspend fun fetch(location: String, maxAge: Int = 1800) {
        val xml = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(location).header("User-Agent", Soap.USER_AGENT).build()).execute().use {
                if (!it.isSuccessful) throw UpnpException(it.code, "description ${it.code}")
                it.body!!.string()
            }
        }
        val root = DeviceDescription.parse(xml, location)
        val until = System.currentTimeMillis() + (maxAge.coerceAtLeast(60) * 1000L) + 30_000L
        val added = root.flatten().filter { it.udn.isNotBlank() && it.services.isNotEmpty() }.map { UpnpDevice(it, http) }
        _devices.update { current ->
            val m = current.toMutableMap()
            for (d in added) {
                m[d.udn] = d
                locations[d.udn] = location
                expiry[d.udn] = until
            }
            m
        }
    }

    private fun remove(udn: String) {
        val loc = locations[udn]
        val toRemove = if (loc != null) locations.filterValues { it == loc }.keys else setOf(udn)
        _devices.update { it - toRemove }
        toRemove.forEach { expiry.remove(it); locations.remove(it) }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        val dead = _devices.value.keys.filter { (expiry[it] ?: 0L) < now }
        if (dead.isNotEmpty()) _devices.update { it - dead.toSet() }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
