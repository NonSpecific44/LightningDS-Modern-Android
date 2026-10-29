package com.lightningds.core

import com.lightningds.core.library.Album
import com.lightningds.core.library.Library
import com.lightningds.core.library.LibraryCategory
import com.lightningds.core.library.LibraryIndex
import com.lightningds.core.media.Didl
import com.lightningds.core.media.Track
import com.lightningds.core.media.parseDuration
import com.lightningds.core.renderer.OpenHomeRenderer
import com.lightningds.core.upnp.DeviceDescription
import com.lightningds.core.upnp.EventServer
import com.lightningds.core.upnp.Soap
import com.lightningds.core.upnp.SsdpClient
import com.lightningds.core.upnp.SsdpMessage
import com.lightningds.core.upnp.mapArguments
import com.lightningds.core.upnp.parseLastChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class ParsingTest {

    @Test
    fun ssdpResponse() {
        val msg = SsdpClient.parse(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:55178/desc.xml\r\n" +
                "ST: urn:av-openhome-org:service:Product:1\r\nUSN: uuid:abc-123::urn:av-openhome-org:service:Product:1\r\n\r\n"
        )!!
        assertEquals(SsdpMessage.Kind.RESPONSE, msg.kind)
        assertEquals("uuid:abc-123", msg.udn)
        assertEquals("http://192.168.1.20:55178/desc.xml", msg.location)
        assertEquals(1800, msg.maxAge)
    }

    @Test
    fun ssdpByeBye() {
        val msg = SsdpClient.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nUSN: uuid:x::upnp:rootdevice\r\nNT: upnp:rootdevice\r\n\r\n")!!
        assertEquals(SsdpMessage.Kind.BYEBYE, msg.kind)
        assertEquals("uuid:x", msg.udn)
    }

    @Test
    fun deviceDescription() {
        val d = DeviceDescription.parse(Fixtures.DEVICE_XML, "http://10.0.0.5:4000/desc.xml")
        assertEquals("uuid:aries-1", d.udn)
        assertEquals("Living Room", d.friendlyName)
        assertEquals("10.0.0.5", d.host)
        val pl = d.services.first { it.name == "Playlist" }
        assertEquals("http://10.0.0.5:4000/Playlist/control", pl.controlUrl)
        assertEquals("av-openhome-org", pl.domain)
        assertEquals("yes", d.protect)
        assertEquals(1, d.embedded.size)
        assertEquals(2, d.flatten().size)
    }

    @Test
    fun soapRoundTrip() {
        val env = Soap.envelope("urn:av-openhome-org:service:Volume:1", "SetVolume", listOf("Value" to "5&"))
        assertTrue(env.contains("<Value>5&amp;</Value>"))
        val r = Soap.parseResponse(
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
              <u:TimeResponse xmlns:u="urn:av-openhome-org:service:Time:1"><TrackCount>3</TrackCount><Duration>200</Duration><Seconds>42</Seconds></u:TimeResponse>
              </s:Body></s:Envelope>""",
            "Time",
        )
        assertEquals(42, r.int("Seconds", 2))
        assertEquals("200", r.get("Missing", 1))
    }

    @Test
    fun argumentMapping() {
        // Name matches win; unmatched values fill remaining slots in order.
        assertEquals(
            listOf("InterFace" to "wlan0", "DHCP" to "", "IpAddress" to "1.2.3.4"),
            mapArguments(listOf("InterFace", "DHCP", "IpAddress"), listOf("InterFace" to "wlan0", "IpAddress" to "1.2.3.4")),
        )
        assertEquals(listOf("Status" to "1", "Format" to "DoP"), mapArguments(listOf("Status", "Format"), listOf("DsdNativeStatus" to "1", "SampleFormat" to "DoP")))
        assertEquals(emptyList<Pair<String, String>>(), mapArguments(emptyList(), listOf("x" to "y")))
    }

    @Test
    fun didlParseAndBuild() {
        val r = Didl.parse(Fixtures.DIDL)
        assertEquals(1, r.containers.size)
        assertEquals(2, r.items.size)
        val t = r.items[0]
        assertEquals("Song A", t.title)
        assertEquals("Artist", t.artist)
        assertEquals("Bach", t.composer)
        assertEquals(245, t.durationSeconds)
        assertEquals(24, t.bitsPerSample)
        assertEquals(96000, t.sampleFrequency)
        assertEquals("flac", t.fileType)
        assertEquals(1, t.trackNumber)
        val rebuilt = Didl.parseFirstItem(Didl.build(t))!!
        assertEquals(t.title, rebuilt.title)
        assertEquals(t.uri, rebuilt.uri)
        assertEquals(t.composer, rebuilt.composer)
        assertEquals(t.durationSeconds, rebuilt.durationSeconds)
    }

    @Test
    fun escapedDidlStillParses() {
        val escaped = Fixtures.DIDL.replace("<", "&lt;").replace(">", "&gt;")
        assertEquals(2, Didl.parse(escaped).items.size)
    }

    @Test
    fun durations() {
        assertEquals(3723, parseDuration("1:02:03.500"))
        assertEquals(65, parseDuration("01:05"))
        assertEquals(0, parseDuration(""))
    }

    @Test
    fun idArray() {
        val buf = ByteBuffer.allocate(12).putInt(5).putInt(7).putInt(300)
        val b64 = Base64.getEncoder().encodeToString(buf.array())
        assertEquals(listOf(5, 7, 300), OpenHomeRenderer.decodeIdArray(b64))
        assertEquals(emptyList<Int>(), OpenHomeRenderer.decodeIdArray(""))
    }

    @Test
    fun sourcesAndTrackList() {
        val sources = OpenHomeRenderer.parseSources(
            "<SourceList><Source><Name>Playlist</Name><Type>Playlist</Type><Visible>true</Visible></Source>" +
                "<Source><Name>Radio</Name><Type>InternetRadio</Type><Visible>1</Visible></Source>" +
                "<Source><Name>AirPlay</Name><Type>AirPlay</Type><Visible>true</Visible></Source></SourceList>"
        )
        assertEquals(3, sources.size)
        assertTrue(sources[1].isRadio)
        assertTrue(sources[2].isAirPlay)
        val meta = Fixtures.DIDL.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val list = OpenHomeRenderer.parseTrackList("<TrackList><Entry><Id>9</Id><Uri>http://x/a.flac</Uri><Metadata>$meta</Metadata></Entry></TrackList>")
        assertEquals(1, list.size)
        assertEquals(9, list[0].id)
        assertEquals("Song A", list[0].track.title)
        assertEquals("http://x/a.flac", list[0].track.uri)
    }

    @Test
    fun genaPropertySet() {
        val props = EventServer.parsePropertySet(
            """<?xml version="1.0"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
            <e:property><Volume>33</Volume></e:property><e:property><Mute>false</Mute></e:property></e:propertyset>"""
        )
        assertEquals("33", props["Volume"])
        assertEquals("false", props["Mute"])
        assertEquals(1800, EventServer.parseTimeout("Second-1800"))
        val lc = parseLastChange("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0"><TransportState val="PLAYING"/><Volume channel="Master" val="20"/></InstanceID></Event>""")
        assertEquals("PLAYING", lc["TransportState"])
        assertEquals("20", lc["Volume"])
    }

    @Test
    fun libraryGrouping() {
        val lib = Library(
            "uuid:s", "Server",
            listOf(
                Album("1", "The Wall", "Pink Floyd", tracks = listOf(Track(title = "a", artist = "Pink Floyd", genre = "Rock", sampleFrequency = 44100, fileType = "flac", date = "1979"))),
                Album("2", "Abbey Road", "The Beatles", tracks = listOf(Track(title = "b", artist = "The Beatles", composer = "Lennon/McCartney", genre = "Rock", sampleFrequency = 96000, bitsPerSample = 24, fileType = "wav"))),
                Album("3", "Kind of Blue", "Miles Davis", tracks = listOf(Track(title = "c", artist = "Miles Davis", genre = "Jazz", sampleFrequency = 2822400, bitsPerSample = 1, fileType = "dsf"))),
            ),
        )
        assertEquals(listOf("Abbey Road", "Kind of Blue", "The Wall"), LibraryIndex.sortedAlbums(lib, false).map { it.title })
        assertEquals(listOf("Jazz", "Rock"), LibraryIndex.group(lib, LibraryCategory.GENRES).map { it.key })
        assertEquals(2, LibraryIndex.group(lib, LibraryCategory.GENRES).first { it.key == "Rock" }.albums.size)
        assertEquals(listOf("44.1kHz", "96kHz", "DSD 2.8MHz"), LibraryIndex.group(lib, LibraryCategory.SAMPLING_RATE).map { it.key })
        assertTrue(LibraryIndex.group(lib, LibraryCategory.COMPOSERS).any { it.key == "McCartney" })
        assertEquals(listOf("WAV", "FLAC", "DSF").sorted(), LibraryIndex.group(lib, LibraryCategory.FILE_TYPE).map { it.key }.sorted())
        assertNotNull(LibraryIndex.search(lib, "beat").artists.firstOrNull())
    }
}
