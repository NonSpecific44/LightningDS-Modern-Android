package com.lightningds.core.upnp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class SsdpMessage(
    val kind: Kind,
    val location: String?,
    val usn: String?,
    val type: String?,
    val maxAge: Int,
) {
    enum class Kind { RESPONSE, ALIVE, BYEBYE, UPDATE }

    val udn: String? get() = usn?.substringBefore("::")
}

/**
 * Simple SSDP client: sends M-SEARCH requests and listens for both unicast responses and
 * multicast NOTIFY announcements.
 */
class SsdpClient(
    private val scope: CoroutineScope,
    private val networkInterface: () -> NetworkInterface?,
    private val onMessage: (SsdpMessage) -> Unit,
) {
    private var searchSocket: MulticastSocket? = null
    private var notifySocket: MulticastSocket? = null
    private val jobs = mutableListOf<Job>()

    @Synchronized
    fun start() {
        if (searchSocket != null) return
        val nif = networkInterface()
        val group = InetAddress.getByName(SSDP_ADDRESS)

        val search = MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            timeToLive = 4
            soTimeout = 2_000
            if (nif != null) try { networkInterface = nif } catch (_: Exception) {}
        }
        searchSocket = search

        val notify = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 2_000
                if (nif != null) joinGroup(InetSocketAddress(group, SSDP_PORT), nif) else @Suppress("DEPRECATION") joinGroup(group)
            }
        } catch (_: Exception) {
            null
        }
        notifySocket = notify

        jobs += scope.launch(Dispatchers.IO) { receiveLoop(search) }
        if (notify != null) jobs += scope.launch(Dispatchers.IO) { receiveLoop(notify) }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        try { searchSocket?.close() } catch (_: Exception) {}
        try { notifySocket?.close() } catch (_: Exception) {}
        searchSocket = null
        notifySocket = null
    }

    /** Sends a burst of M-SEARCH requests for the targets the app cares about. */
    fun search(targets: List<String> = DEFAULT_TARGETS) {
        val socket = searchSocket ?: return
        scope.launch(Dispatchers.IO) {
            val group = InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
            repeat(2) {
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: $st\r\n" +
                        "USER-AGENT: ${Soap.USER_AGENT}\r\n\r\n"
                    val bytes = msg.toByteArray()
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, group))
                    } catch (_: Exception) {
                    }
                    delay(30)
                }
                delay(400)
            }
        }
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buf = ByteArray(8192)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            parse(text)?.let { onMessage(it) }
        }
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900

        val DEFAULT_TARGETS = listOf(
            "urn:av-openhome-org:service:Product:1",
            "urn:av-openhome-org:service:Product:2",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "upnp:rootdevice",
        )

        fun parse(text: String): SsdpMessage? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty()) return null
            val first = lines[0].uppercase()
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().uppercase()] = l.substring(i + 1).trim()
            }
            val maxAge = headers["CACHE-CONTROL"]?.let { Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1800
            return when {
                first.startsWith("HTTP/") -> SsdpMessage(SsdpMessage.Kind.RESPONSE, headers["LOCATION"], headers["USN"], headers["ST"], maxAge)
                first.startsWith("NOTIFY") -> {
                    val kind = when (headers["NTS"]?.lowercase()) {
                        "ssdp:alive" -> SsdpMessage.Kind.ALIVE
                        "ssdp:byebye" -> SsdpMessage.Kind.BYEBYE
                        "ssdp:update" -> SsdpMessage.Kind.UPDATE
                        else -> return null
                    }
                    SsdpMessage(kind, headers["LOCATION"], headers["USN"], headers["NT"], maxAge)
                }
                else -> null
            }
        }
    }
}
