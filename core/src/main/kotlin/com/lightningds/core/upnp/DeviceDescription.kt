package com.lightningds.core.upnp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element

data class ServiceInfo(
    val serviceType: String,
    val serviceId: String,
    val scpdUrl: String,
    val controlUrl: String,
    val eventSubUrl: String,
) {
    /** e.g. "Playlist" from "urn:av-openhome-org:service:Playlist:1". */
    val name: String get() = serviceType.split(':').getOrNull(3) ?: serviceType

    /** e.g. "av-openhome-org" from "urn:av-openhome-org:service:Playlist:1". */
    val domain: String get() = serviceType.split(':').getOrNull(1) ?: ""

    val version: Int get() = serviceType.split(':').getOrNull(4)?.toIntOrNull() ?: 1
}

data class Icon(val mimeType: String?, val width: Int, val height: Int, val url: String)

data class DeviceDescription(
    val udn: String,
    val deviceType: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val serialNumber: String?,
    val presentationUrl: String?,
    val icons: List<Icon>,
    val services: List<ServiceInfo>,
    val embedded: List<DeviceDescription>,
    val location: String,
    /** Lightning devices advertise a passcode lock in their description (`<protect>`). */
    val protect: String?,
) {
    val host: String? get() = location.toHttpUrlOrNull()?.host

    /** All devices in this tree (self first). */
    fun flatten(): List<DeviceDescription> = listOf(this) + embedded.flatMap { it.flatten() }

    fun bestIcon(): Icon? = icons.filter { it.mimeType?.contains("png") != false }
        .maxByOrNull { it.width * it.height } ?: icons.maxByOrNull { it.width * it.height }

    companion object {
        fun parse(xml: String, location: String): DeviceDescription {
            val doc = Xml.parse(xml)
            val root = doc.documentElement
            val base = root.childText("URLBase")?.takeIf { it.isNotBlank() } ?: location
            val baseUrl = base.toHttpUrlOrNull() ?: location.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Bad location $location")
            val deviceEl = root.child("device") ?: throw IllegalArgumentException("No <device> element")
            return parseDevice(deviceEl, baseUrl, location)
        }

        private fun parseDevice(el: Element, base: HttpUrl, location: String): DeviceDescription {
            val services = el.child("serviceList")?.children("service")?.map { s ->
                ServiceInfo(
                    serviceType = s.childText("serviceType").orEmpty(),
                    serviceId = s.childText("serviceId").orEmpty(),
                    scpdUrl = resolve(base, s.childText("SCPDURL")),
                    controlUrl = resolve(base, s.childText("controlURL")),
                    eventSubUrl = resolve(base, s.childText("eventSubURL")),
                )
            }.orEmpty()
            val icons = el.child("iconList")?.children("icon")?.map { i ->
                Icon(
                    mimeType = i.childText("mimetype"),
                    width = i.childText("width")?.toIntOrNull() ?: 0,
                    height = i.childText("height")?.toIntOrNull() ?: 0,
                    url = resolve(base, i.childText("url")),
                )
            }.orEmpty()
            val embedded = el.child("deviceList")?.children("device")?.map { parseDevice(it, base, location) }.orEmpty()
            return DeviceDescription(
                udn = el.childText("UDN").orEmpty(),
                deviceType = el.childText("deviceType").orEmpty(),
                friendlyName = el.childText("friendlyName").orEmpty(),
                manufacturer = el.childText("manufacturer"),
                modelName = el.childText("modelName"),
                modelNumber = el.childText("modelNumber"),
                serialNumber = el.childText("serialNumber"),
                presentationUrl = el.childText("presentationURL")?.let { resolve(base, it) },
                icons = icons,
                services = services,
                embedded = embedded,
                location = location,
                protect = el.descendant("protect")?.textContent?.trim(),
            )
        }

        internal fun resolve(base: HttpUrl, path: String?): String {
            if (path.isNullOrBlank()) return ""
            val p = path.trim()
            return base.resolve(p)?.toString() ?: p
        }
    }
}

data class ActionArgument(val name: String, val directionIn: Boolean, val relatedStateVariable: String?)

data class ActionDescription(val name: String, val arguments: List<ActionArgument>) {
    val inputs: List<ActionArgument> get() = arguments.filter { it.directionIn }
    val outputs: List<ActionArgument> get() = arguments.filterNot { it.directionIn }
}

data class StateVariable(val name: String, val dataType: String, val sendEvents: Boolean, val allowedValues: List<String>)

data class ServiceDescription(val actions: Map<String, ActionDescription>, val stateVariables: Map<String, StateVariable>) {
    companion object {
        fun parse(xml: String): ServiceDescription {
            val root = Xml.parse(xml).documentElement
            val actions = root.child("actionList")?.children("action")?.map { a ->
                ActionDescription(
                    name = a.childText("name").orEmpty(),
                    arguments = a.child("argumentList")?.children("argument")?.map { arg ->
                        ActionArgument(
                            name = arg.childText("name").orEmpty(),
                            directionIn = arg.childText("direction").equals("in", ignoreCase = true),
                            relatedStateVariable = arg.childText("relatedStateVariable"),
                        )
                    }.orEmpty(),
                )
            }.orEmpty().associateBy { it.name }
            val vars = root.child("serviceStateTable")?.children("stateVariable")?.map { v ->
                StateVariable(
                    name = v.childText("name").orEmpty(),
                    dataType = v.childText("dataType").orEmpty(),
                    sendEvents = v.attr("sendEvents")?.equals("yes", true) ?: false,
                    allowedValues = v.child("allowedValueList")?.children("allowedValue")?.map { it.textContent.trim() }.orEmpty(),
                )
            }.orEmpty().associateBy { it.name }
            return ServiceDescription(actions, vars)
        }
    }
}
