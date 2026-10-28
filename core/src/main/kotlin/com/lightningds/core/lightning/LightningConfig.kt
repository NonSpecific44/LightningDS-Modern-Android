package com.lightningds.core.lightning

import com.lightningds.core.upnp.UpnpDevice
import com.lightningds.core.upnp.UpnpService
import com.lightningds.core.upnp.Xml
import com.lightningds.core.upnp.children
import com.lightningds.core.upnp.localName2
import com.lightningds.core.upnp.toUpnpBool
import org.w3c.dom.Element

/** Parses the loose `<Tag>value</Tag>` fragments Lightning devices return into elements. */
internal fun fragment(text: String?): Element? = Xml.parseOrNull("<Body>${text.orEmpty()}</Body>")?.documentElement

internal fun Element.valueOf(name: String): String =
    children().firstOrNull { it.localName2.equals(name, true) }?.textContent?.trim().orEmpty()

data class HardwareInfo(
    val manufacturer: String,
    val model: String,
    val serial: String,
    val coreVersion: String,
    val wifiVersion: String,
    val mainVersion: String,
    val firmware: String,
    val lanMac: String,
    val wlanMac: String,
)

data class OutputChannel(val device: String, val name: String)

data class OutputChannels(val current: String, val channels: List<OutputChannel>)

data class NetInterface(val name: String, val type: String) {
    val isWireless: Boolean get() = type.contains("wireless", true) || type.contains("wifi", true) || name.startsWith("wlan")
}

data class NetInterfaces(val current: String, val interfaces: List<NetInterface>)

data class IpConfig(val dhcp: Boolean, val ip: String, val netmask: String, val gateway: String, val dns: String)

data class WirelessNetwork(val essid: String, val quality: Int, val encryption: Boolean, val encryptionType: String)

data class WirelessList(val current: String, val networks: List<WirelessNetwork>)

data class DsdStatus(val nativeEnabled: Boolean, val sampleFormat: String)

data class PasscodeState(val protected: Boolean, val passcode: String)

data class UpdateInfo(val version: String, val progress: Int)

/**
 * Wrapper around Auralic's proprietary `HardwareConfig` service, which the original app used for
 * all "Device" settings (name, passcode, operating mode, network, DAC delay, output, firmware...).
 */
class LightningConfig(private val svc: UpnpService) {

    suspend fun hardwareInfo(): HardwareInfo {
        val r = svc.call("GetHardWareInfo")
        val f = fragment(r.get("HardWareInfo", 0))
        fun v(n: String) = f?.valueOf(n).orEmpty()
        return HardwareInfo(v("MFG"), v("TYPE"), v("SN"), v("CORE"), v("WIFI"), v("MAIN"), v("FW"), v("MAC0"), v("MAC1"))
    }

    suspend fun setRoomName(name: String) {
        svc.call("SetRoomName", "RoomName" to name)
    }

    suspend fun outputChannels(): OutputChannels {
        val r = svc.call("GetOutChannel")
        val list = r.get("OutChannel", 2).let { raw ->
            fragment(raw)?.children()?.filter { it.localName2.equals("Channel", true) }?.map {
                OutputChannel(it.valueOf("Device"), it.valueOf("Name"))
            }.orEmpty()
        }
        return OutputChannels(r.get("CurrentChannel", 1), list)
    }

    suspend fun setOutputChannel(device: String) {
        svc.call("SetOutChannel", "OutChannel" to device)
    }

    /** "OpenHome" or "Upnp" (legacy UPnP AV renderer mode). */
    suspend fun upnpType(): String = svc.call("GetUpnpType").get("UpnpType", 0)

    suspend fun setUpnpType(type: String) {
        svc.call("SetUpnpType", "UpnpType" to type)
    }

    suspend fun netInterfaces(): NetInterfaces {
        val r = svc.call("GetNetInterface")
        val list = fragment(r.get("InterfaceList", 2))?.children()?.filter { it.localName2.equals("interface", true) }?.map {
            NetInterface(it.valueOf("name"), it.valueOf("type"))
        }.orEmpty()
        return NetInterfaces(r.get("CurrentUse", 1), list)
    }

    suspend fun ipConfig(iface: String): IpConfig {
        val r = svc.call("GetIpAddress", "InterFace" to iface)
        return IpConfig(
            dhcp = r.get("DHCP").toUpnpBool(),
            ip = r.get("IpAddress", 0),
            netmask = r["NetMask"] ?: r["Netmask"].orEmpty(),
            gateway = r["GateWay"] ?: r["Gateway"].orEmpty(),
            dns = r["DNS"].orEmpty(),
        )
    }

    suspend fun wirelessList(iface: String): WirelessList {
        val r = svc.call("GetWirelessList", "InterFace" to iface)
        val raw = r["WirelessList"] ?: r.values.values.maxByOrNull { it.length }.orEmpty()
        val nets = fragment(raw)?.children()?.filter { it.localName2.equals("wireless", true) }?.map {
            WirelessNetwork(
                essid = it.valueOf("essid"),
                quality = it.valueOf("quality").filter(Char::isDigit).toIntOrNull() ?: 0,
                encryption = it.valueOf("encryption").let { e -> e.toUpnpBool() || e.equals("on", true) },
                encryptionType = it.valueOf("encryp_type"),
            )
        }.orEmpty()
        return WirelessList(r["CurrentUse"].orEmpty(), nets.distinctBy { it.essid }.sortedByDescending { it.quality })
    }

    /**
     * Applies a network configuration. An empty [ip] means DHCP; [ssid]/[password]/[encryption]
     * are only used for wireless interfaces.
     */
    suspend fun setNetwork(
        iface: String,
        ip: String = "",
        netmask: String = "",
        gateway: String = "",
        dns: String = "",
        ssid: String = "",
        password: String = "",
        encryption: String = "",
    ) {
        svc.call(
            "SetNetWork",
            "InterFace" to iface,
            "IpAddress" to ip,
            "NetMask" to netmask,
            "GateWay" to gateway,
            "DNS" to dns,
            "Ssid" to ssid,
            "PassWord" to password,
            "EncrypType" to encryption,
        )
    }

    suspend fun passcode(): PasscodeState {
        val r = svc.call("GetPasswordProtect")
        return PasscodeState(r.get("Protect", 0).let { it.equals("yes", true) || it.toUpnpBool() }, r.get("ProtectPassword", 1))
    }

    suspend fun setPasscode(enabled: Boolean, passcode: String) {
        svc.call("SetPasswordProtect", "Protect" to if (enabled) "yes" else "no", "ProtectPassword" to passcode)
    }

    suspend fun dsdStatus(): DsdStatus {
        val r = svc.call("GetDsdNativeStatus")
        return DsdStatus(r.get("Status", 0).let { it.toUpnpBool() || it.equals("on", true) || it.equals("enable", true) }, r.get("SampleFormat", 1))
    }

    suspend fun setDsdStatus(enabled: Boolean, sampleFormat: String) {
        svc.call("SetDsdNativeStatus", "Status" to if (enabled) "1" else "0", "SampleFormat" to sampleFormat)
    }

    /** DAC output delay in seconds ("0" = off). */
    suspend fun waitingTime(): Int = svc.call("GetWaitingTime").get("WaitingTime", 0).filter(Char::isDigit).toIntOrNull() ?: 0

    suspend fun setWaitingTime(seconds: Int) {
        svc.call("SetWaitingTime", "WaitingTime" to seconds.toString())
    }

    /** Whether software (digital) volume control is enabled. */
    suspend fun volumeControl(): Boolean = svc.call("GetVolumeControl").bool("VolumeControl", 0)

    suspend fun setVolumeControl(enabled: Boolean) {
        svc.call("SetVolumeControl", "VolumeControl" to enabled)
    }

    suspend fun updateInfo(): UpdateInfo {
        if (svc.hasAction("CheckUpdate")) runCatching { svc.call("CheckUpdate") }
        val r = svc.call("GetUpdateInfo")
        return UpdateInfo(r.get("Version", 0), r.get("Progress", 1).filter(Char::isDigit).toIntOrNull() ?: -1)
    }

    suspend fun applyUpdate() {
        svc.call("Update")
    }

    suspend fun activeStatus(): String = svc.call("GetActiveStatus").get("ActiveStatus", 0)

    suspend fun activate(realName: String, email: String, subscribeNewsletter: Boolean) {
        svc.call("Active", "RealName" to realName, "Email" to email, "IsSubscribe" to subscribeNewsletter)
    }

    suspend fun isAlive(): Boolean = runCatching { svc.call("IsAlive").get("Alive", 0).let { it.isEmpty() || it.toUpnpBool() } }.getOrDefault(false)

    companion object {
        const val MODE_OPENHOME = "OpenHome"
        const val MODE_UPNP = "Upnp"
        val DAC_DELAYS = listOf(0, 1, 2, 3, 4, 5)

        fun of(device: UpnpDevice): LightningConfig? = device.service("HardwareConfig")?.let { LightningConfig(it) }
    }
}

data class DiskInfo(val connected: Boolean, val statusCode: Int, val statusInfo: String, val capacity: String, val fileCount: Int)

data class SmbConfig(val address: String, val userName: String, val password: String)

data class WorkMode(val type: String, val name: String)

data class WorkModes(val current: String, val modes: List<WorkMode>)

/** Wrapper around the Lightning Server's proprietary `ServerConfig` service. */
class LightningServerConfig(private val svc: UpnpService) {
    suspend fun version(): String = svc.call("GetServerVersion").get("ServerVersion", 0)

    suspend fun setName(name: String) {
        svc.call("SetServerName", "ServerName" to name)
    }

    suspend fun progress(): String = svc.call("GetProgressInfo").get("Progress", 0)

    suspend fun scanVersion(): String = svc.call("GetScanVersion").get("ScanVersion", 0)

    suspend fun workModes(): WorkModes {
        val r = svc.call("GetWorkMode")
        val modes = fragment(r.get("WorkModeList", 1))?.children()?.filter { it.localName2.equals("WorkMode", true) }?.map {
            WorkMode(it.valueOf("type"), it.valueOf("name"))
        }.orEmpty()
        return WorkModes(r.get("WorkMode", 0), modes)
    }

    suspend fun setWorkMode(mode: String) {
        svc.call("SetWorkMode", "WorkMode" to mode)
    }

    suspend fun diskInfo(): DiskInfo {
        val r = svc.call("GetDiskInfo")
        return DiskInfo(r.bool("IsConnected", 0), r.int("StatusCode", 1), r.get("StatusInfo", 2), r.get("Capacity", 3), r.int("FileCount", 4))
    }

    suspend fun smbConfig(): SmbConfig {
        val r = svc.call("GetSMBConfig")
        return SmbConfig(r.get("SMBAddr", 0), r.get("SMBUserName", 1), r.get("SMBPassWord", 2))
    }

    suspend fun setSmbConfig(cfg: SmbConfig) {
        svc.call("SetSMBConfig", "SMBAddr" to cfg.address, "SMBUserName" to cfg.userName, "SMBPassWord" to cfg.password)
    }

    suspend fun rescan() { svc.call("Rescan") }
    suspend fun initHdd() { svc.call("InitHDD") }
    suspend fun mount() { svc.call("HandMount") }
    suspend fun unmount() { svc.call("HandUMount") }
    suspend fun driveMountResult(): Boolean = svc.call("GetDriveMountResult").bool("DriveMountResult", 0)
    suspend fun deleteLocalDatabase() { svc.call("DelAllLocalDB") }

    companion object {
        fun of(device: UpnpDevice): LightningServerConfig? = device.service("ServerConfig")?.let { LightningServerConfig(it) }

        /** Messages for Lightning Server disk status codes (from the setup guide). */
        val STATUS_MESSAGES = mapOf(
            1 to "Please make sure you have attached the USB drive.",
            2 to "Drive is unmounted.",
            3 to "The media is read-only. Lightning Server is unable to scan it.",
            4 to "This USB drive can't be used.",
            5 to "The USB drive contains journal errors; please repair it on a computer.",
            6 to "The shared network folder address is invalid.",
            7 to "The shared network folder is unreachable.",
            8 to "The shared network folder does not provide a Samba (SMB) service.",
            9 to "The username or password for the shared network folder is wrong.",
        )
    }
}
