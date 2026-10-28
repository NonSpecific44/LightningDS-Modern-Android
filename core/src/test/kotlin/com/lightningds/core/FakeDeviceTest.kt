package com.lightningds.core

import com.lightningds.core.lightning.LightningConfig
import com.lightningds.core.library.ContentDirectory
import com.lightningds.core.library.LibrarySync
import com.lightningds.core.media.Track
import com.lightningds.core.renderer.InsertMode
import com.lightningds.core.renderer.OpenHomeRenderer
import com.lightningds.core.upnp.ControlPoint
import com.lightningds.core.upnp.Xml
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.Collections

/** Runs the control point against a fake Lightning device served by MockWebServer. */
class FakeDeviceTest {
    private val server = MockWebServer()
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val callbacks = Collections.synchronizedList(mutableListOf<String>())
    private var nextId = 100

    private val playlistNs = "urn:av-openhome-org:service:Playlist:1"
    private val productNs = "urn:av-openhome-org:service:Product:1"
    private val hwNs = "urn:av-openhome-org:service:HardwareConfig:1"
    private val cdNs = "urn:schemas-upnp-org:service:ContentDirectory:1"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "SUBSCRIBE") {
                    request.getHeader("CALLBACK")?.let { callbacks += it }
                    return MockResponse().setHeader("SID", "uuid:sub-" + path.substringBefore("/event").trim('/')).setHeader("TIMEOUT", "Second-1800")
                }
                return when (path) {
                    "/desc.xml" -> MockResponse().setBody(Fixtures.DEVICE_XML)
                    "/Playlist/scpd.xml" -> MockResponse().setBody(
                        Fixtures.scpd(
                            "Insert" to listOf("AfterId" to true, "Uri" to true, "Metadata" to true, "NewId" to false),
                            "DeleteAll" to emptyList(),
                            "SeekId" to listOf("Value" to true),
                        )
                    )
                    "/HardwareConfig/scpd.xml" -> MockResponse().setBody(
                        Fixtures.scpd(
                            "GetHardWareInfo" to listOf("Info" to false),
                            "SetDsdNativeStatus" to listOf("Native" to true, "Format" to true),
                        )
                    )
                    "/Playlist/control", "/HardwareConfig/control", "/Product/control", "/CD/control" -> control(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun control(request: RecordedRequest): MockResponse {
        val action = request.getHeader("SOAPACTION")!!.trim('"').substringAfter('#')
        val body = request.body.readUtf8()
        calls += "$action:" + body.substringAfter("<u:$action").substringAfter('>').substringBefore("</u:$action>")
        val xml = when (action) {
            "Insert" -> Fixtures.soapResponse(action, playlistNs, "NewId" to (nextId++).toString())
            "GetHardWareInfo" -> Fixtures.soapResponse(action, hwNs, "Info" to Xml.escape("<MFG>AURALiC</MFG><TYPE>ARIES</TYPE><SN>123</SN><FW>4.0</FW>"))
            "Browse" -> {
                val id = body.substringAfter("<ObjectID>").substringBefore("</ObjectID>")
                val didl = when (id) {
                    "0" -> "<container id=\"albums\" parentID=\"0\"><dc:title>Albums</dc:title><upnp:class>object.container</upnp:class></container>"
                    "albums" -> "<container id=\"alb1\" parentID=\"albums\"><dc:title>An Album</dc:title><upnp:class>object.container.album.musicAlbum</upnp:class></container>"
                    "alb1" -> "<item id=\"t1\" parentID=\"alb1\"><dc:title>Song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res duration=\"0:01:00\">http://x/1.flac</res></item>"
                    else -> ""
                }
                val count = if (didl.isEmpty()) 0 else 1
                Fixtures.soapResponse(
                    action, cdNs,
                    "Result" to Xml.escape("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">$didl</DIDL-Lite>"),
                    "NumberReturned" to count.toString(), "TotalMatches" to count.toString(), "UpdateID" to "1",
                )
            }
            "Search" -> return MockResponse().setResponseCode(500).setBody("<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError><errorCode>708</errorCode><errorDescription>Unsupported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>")
            else -> Fixtures.soapResponse(action, productNs)
        }
        return MockResponse().setBody(xml)
    }

    @Test
    fun discoveryConfigQueueAndEvents() = runBlocking {
        val cp = ControlPoint()
        try {
            cp.fetch(server.url("/desc.xml").toString())
            val devices = cp.devices.value
            assertEquals(2, devices.size)
            val aries = devices["uuid:aries-1"]!!
            assertTrue(aries.isOpenHomeRenderer)
            assertTrue(aries.isLightningRenderer)
            assertTrue(devices["uuid:server-1"]!!.isServer)

            // Vendor service with argument names that differ from the guessed ones.
            val cfg = LightningConfig.of(aries)!!
            val info = cfg.hardwareInfo()
            assertEquals("ARIES", info.model)
            assertEquals("123", info.serial)
            cfg.setDsdStatus(true, "DoP")
            assertTrue(calls.last(), calls.last().contains("<Native>1</Native><Format>DoP</Format>"))

            val renderer = OpenHomeRenderer(aries, cp.events, cp.scope)
            renderer.start()
            calls.clear()
            renderer.enqueue(
                listOf(Track(id = "a", title = "A", uri = "http://x/a.flac"), Track(id = "b", title = "B", uri = "http://x/b.flac")),
                InsertMode.REPLACE,
            )
            val actions = calls.map { it.substringBefore(':') }
            assertTrue(actions.toString(), actions.containsAll(listOf("DeleteAll", "Insert", "SeekId")))
            assertTrue(calls.first { it.startsWith("Insert") }.contains("<AfterId>0</AfterId>"))
            assertTrue(calls.filter { it.startsWith("Insert") }[1].contains("<AfterId>100</AfterId>"))
            assertTrue(calls.first { it.startsWith("SeekId") }.contains("<Value>100</Value>"))

            // GENA: wait for the playlist subscription, then send a NOTIFY to our event server.
            withTimeout(15000) { while (callbacks.none()) delay(50) }
            val port = cp.events.port
            val sid = "uuid:sub-Playlist"
            withTimeout(15000) {
                while (true) {
                    sendNotify(port, sid, "<TransportState>Playing</TransportState>")
                    delay(100)
                    if (renderer.state.value.transport.name == "PLAYING") break
                }
            }
            renderer.close()

            // Library import with Search unsupported: falls back to walking the tree.
            val cd = ContentDirectory.of(devices["uuid:server-1"]!!)!!
            val lib = LibrarySync(cd, "Lightning Server").run { }
            assertEquals(1, lib.albums.size)
            assertEquals("Song", lib.albums[0].tracks[0].title)
            assertEquals(60, lib.albums[0].durationSeconds)
        } finally {
            cp.stop()
        }
    }

    private fun sendNotify(port: Int, sid: String, prop: String) {
        val body = "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property>$prop</e:property></e:propertyset>"
        Socket("127.0.0.1", port).use { s ->
            val req = "NOTIFY /event HTTP/1.1\r\nHOST: 127.0.0.1:$port\r\nCONTENT-TYPE: text/xml\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $sid\r\nSEQ: 0\r\nCONTENT-LENGTH: ${body.toByteArray().size}\r\n\r\n$body"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().read()
        }
    }
}
