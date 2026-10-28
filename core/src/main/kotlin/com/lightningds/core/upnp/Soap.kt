package com.lightningds.core.upnp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class UpnpException(val code: Int, message: String) : IOException(message)

/**
 * Output arguments of an action, in the order the device returned them.
 * Lookups fall back to position because some vendor services (Auralic's HardwareConfig and
 * ServerConfig) are not documented and argument names had to be recovered from the old app.
 */
class ActionResult(val values: LinkedHashMap<String, String>) {
    private val list = values.values.toList()

    operator fun get(name: String): String? = values[name]
        ?: values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun get(name: String, index: Int): String = get(name) ?: list.getOrNull(index).orEmpty()

    fun int(name: String, index: Int = 0): Int = get(name, index).trim().toLongOrNull()?.toInt() ?: 0

    fun long(name: String, index: Int = 0): Long = get(name, index).trim().toLongOrNull() ?: 0L

    fun bool(name: String, index: Int = 0): Boolean = get(name, index).toUpnpBool()

    override fun toString(): String = values.toString()
}

fun String?.toUpnpBool(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}

fun Boolean.toUpnp(): String = if (this) "1" else "0"

/** A UPnP service on a device that can be invoked and subscribed to. */
class UpnpService(
    val info: ServiceInfo,
    private val http: OkHttpClient,
) {
    private val scpdMutex = Mutex()
    private var scpd: ServiceDescription? = null
    private var scpdFailed = false

    val serviceType: String get() = info.serviceType
    val name: String get() = info.name

    suspend fun description(): ServiceDescription? = scpdMutex.withLock {
        if (scpd == null && !scpdFailed && info.scpdUrl.isNotBlank()) {
            scpd = try {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(info.scpdUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) null else ServiceDescription.parse(r.body!!.string())
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (scpd == null) scpdFailed = true
        }
        scpd
    }

    suspend fun hasAction(action: String): Boolean = description()?.actions?.containsKey(action) ?: true

    /**
     * Invokes [action]. Arguments are given with their documented names; if the device's SCPD
     * uses different names but the same number of inputs they are matched by position.
     */
    suspend fun call(action: String, vararg args: Pair<String, Any?>): ActionResult {
        val desc = description()?.actions?.get(action)
        val named = if (desc != null) mapArguments(desc.inputs.map { it.name }, args.map { it.first to it.second.toArgString() })
        else args.map { it.first to it.second.toArgString() }
        return Soap.invoke(http, info.controlUrl, info.serviceType, action, named)
    }

    private fun Any?.toArgString(): String = when (this) {
        null -> ""
        is Boolean -> this.toUpnp()
        else -> toString()
    }
}

/**
 * Maps the arguments we provide onto the device's declared input names: exact/case-insensitive
 * name matches first, then the remaining values in order. Inputs we know nothing about are sent
 * empty, which is what ohNet did as well.
 */
internal fun mapArguments(declared: List<String>, provided: List<Pair<String, String>>): List<Pair<String, String>> {
    if (declared.isEmpty()) return emptyList()
    val remaining = provided.toMutableList()
    val matched = declared.map { name ->
        val idx = remaining.indexOfFirst { it.first.equals(name, ignoreCase = true) }
        if (idx >= 0) name to remaining.removeAt(idx).second else null
    }
    return declared.mapIndexed { i, name ->
        matched[i] ?: (name to (if (remaining.isNotEmpty()) remaining.removeAt(0).second else ""))
    }
}

object Soap {
    private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((k, v) in args) {
            append('<').append(k).append('>').append(Xml.escape(v)).append("</").append(k).append('>')
        }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    suspend fun invoke(
        http: OkHttpClient,
        controlUrl: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
    ): ActionResult = withContext(Dispatchers.IO) {
        val body = envelope(serviceType, action, args)
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(XML))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseFault(text) ?: UpnpException(response.code, "$action failed: HTTP ${response.code}")
            }
            parseResponse(text, action)
        }
    }

    fun parseResponse(text: String, action: String): ActionResult {
        val doc = Xml.parse(text)
        val body = doc.documentElement.child("Body") ?: throw UpnpException(-1, "$action: no SOAP body")
        body.child("Fault")?.let { throw faultFrom(it) }
        val resp = body.children().firstOrNull() ?: throw UpnpException(-1, "$action: empty response")
        val map = LinkedHashMap<String, String>()
        for (c in resp.children()) map[c.localName2] = c.textContent ?: ""
        return ActionResult(map)
    }

    private fun parseFault(text: String): UpnpException? = try {
        val body = Xml.parse(text).documentElement.child("Body")
        body?.child("Fault")?.let { faultFrom(it) }
    } catch (_: Exception) {
        null
    }

    private fun faultFrom(fault: org.w3c.dom.Element): UpnpException {
        val err = fault.descendant("UPnPError")
        val code = err?.childText("errorCode")?.toIntOrNull() ?: -1
        val desc = err?.childText("errorDescription") ?: fault.childText("faultstring") ?: "UPnP error"
        return UpnpException(code, "$desc ($code)")
    }

    const val USER_AGENT = "Android UPnP/1.1 LightningDS/2.0"
}
