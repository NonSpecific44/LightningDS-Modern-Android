package com.lightningds.core.upnp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

typealias EventListener = (Map<String, String>) -> Unit

/**
 * GENA (UPnP eventing): a tiny HTTP server receiving NOTIFY requests plus
 * SUBSCRIBE/renew/UNSUBSCRIBE handling.
 */
class EventServer(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val listeners = ConcurrentHashMap<String, EventListener>()
    private val early = ConcurrentHashMap<String, MutableList<Map<String, String>>>()

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                launch(Dispatchers.IO) { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        listeners.clear()
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
                }
                val body = when {
                    headers["TRANSFER-ENCODING"]?.contains("chunked", true) == true -> readChunked(input)
                    else -> {
                        val len = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
                        readFully(input, len)
                    }
                }
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                if (!requestLine.startsWith("NOTIFY")) return
                val sid = headers["SID"] ?: return
                val props = parsePropertySet(String(body, Charsets.UTF_8))
                val l = listeners[sid]
                if (l != null) {
                    l(props)
                } else {
                    early.getOrPut(sid) { mutableListOf() }.add(props)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Subscribes to [service]; returns a handle that keeps the subscription renewed until cancelled. */
    fun subscribe(service: UpnpService, listener: EventListener): EventSubscription {
        start()
        val sub = EventSubscription(service, listener)
        sub.job = scope.launch(Dispatchers.IO) { sub.run() }
        return sub
    }

    inner class EventSubscription internal constructor(
        val service: UpnpService,
        private val listener: EventListener,
    ) {
        internal var job: Job? = null
        @Volatile
        var sid: String? = null
            private set

        internal suspend fun run() {
            val url = service.info.eventSubUrl
            if (url.isBlank()) return
            var failures = 0
            while (scope.isActive && job?.isActive == true) {
                val current = sid
                val timeout = try {
                    if (current == null) subscribeNew(url) else renew(url, current)
                } catch (e: Exception) {
                    if (current != null) {
                        listeners.remove(current)
                        sid = null
                        0
                    } else {
                        failures++
                        -1
                    }
                }
                when {
                    timeout > 0 -> {
                        failures = 0
                        delay((timeout * 1000L / 2).coerceIn(15_000L, 900_000L))
                    }
                    timeout == 0 -> Unit
                    else -> delay((5_000L * failures).coerceAtMost(60_000L))
                }
            }
        }

        private suspend fun subscribeNew(url: String): Int = withContext(Dispatchers.IO) {
            val host = URI(url).let { it.host to (if (it.port > 0) it.port else 80) }
            val local = localAddressFor(host.first, host.second)
            val hostPart = if (local is java.net.Inet6Address) "[${local.hostAddress.substringBefore('%')}]" else local.hostAddress
            val callback = "<http://$hostPart:$port/event>"
            val req = Request.Builder().url(url)
                .method("SUBSCRIBE", null)
                .header("CALLBACK", callback)
                .header("NT", "upnp:event")
                .header("TIMEOUT", "Second-1800")
                .header("User-Agent", Soap.USER_AGENT)
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw UpnpException(r.code, "SUBSCRIBE failed ${r.code}")
                val newSid = r.header("SID") ?: throw UpnpException(-1, "No SID")
                sid = newSid
                listeners[newSid] = listener
                early.remove(newSid)?.forEach { listener(it) }
                parseTimeout(r.header("TIMEOUT"))
            }
        }

        private suspend fun renew(url: String, current: String): Int = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .method("SUBSCRIBE", null)
                .header("SID", current)
                .header("TIMEOUT", "Second-1800")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw UpnpException(r.code, "Renew failed ${r.code}")
                parseTimeout(r.header("TIMEOUT"))
            }
        }

        fun cancel() {
            job?.cancel()
            val s = sid ?: return
            listeners.remove(s)
            sid = null
            scope.launch(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(service.info.eventSubUrl)
                        .method("UNSUBSCRIBE", ByteArray(0).toRequestBody(null))
                        .header("SID", s)
                        .build()
                    http.newCall(req).execute().close()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        fun parseTimeout(header: String?): Int {
            if (header == null) return 1800
            if (header.contains("infinite", true)) return 1800
            return header.substringAfter('-', "1800").trim().toIntOrNull() ?: 1800
        }

        fun parsePropertySet(xml: String): Map<String, String> {
            val doc = Xml.parseOrNull(xml) ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (prop in doc.documentElement.children("property")) {
                for (c in prop.children()) out[c.localName2] = c.textContent ?: ""
            }
            return out
        }

        /** Finds which local address routes to [host]. */
        fun localAddressFor(host: String, port: Int): InetAddress {
            DatagramSocket().use { s ->
                s.connect(InetSocketAddress(host, port))
                return s.localAddress
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }

        private fun readFully(input: InputStream, len: Int): ByteArray {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            return if (off == len) buf else buf.copyOf(off)
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLine(input)
                    break
                }
                out.write(readFully(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }
    }
}

/** Parses UPnP AV "LastChange" event XML into a flat map of variable name to `val`. */
fun parseLastChange(xml: String): Map<String, String> {
    val doc = Xml.parseOrNull(xml) ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    val instance = doc.documentElement.child("InstanceID") ?: return out
    for (c in instance.children()) {
        val channel = c.attr("channel")
        val key = if (channel != null && channel != "Master") "${c.localName2}:$channel" else c.localName2
        out[key] = c.attr("val") ?: ""
    }
    return out
}
