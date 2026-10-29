package com.lightningds.core

object Fixtures {
    val DEVICE_XML = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:av-openhome-org:device:Source:1</deviceType>
    <friendlyName>Living Room</friendlyName>
    <manufacturer>AURALiC</manufacturer>
    <modelName>ARIES</modelName>
    <UDN>uuid:aries-1</UDN>
    <protect>yes</protect>
    <serviceList>
      <service><serviceType>urn:av-openhome-org:service:Product:1</serviceType><serviceId>urn:av-openhome-org:serviceId:Product</serviceId><SCPDURL>/Product/scpd.xml</SCPDURL><controlURL>/Product/control</controlURL><eventSubURL>/Product/event</eventSubURL></service>
      <service><serviceType>urn:av-openhome-org:service:Playlist:1</serviceType><serviceId>urn:av-openhome-org:serviceId:Playlist</serviceId><SCPDURL>/Playlist/scpd.xml</SCPDURL><controlURL>/Playlist/control</controlURL><eventSubURL>/Playlist/event</eventSubURL></service>
      <service><serviceType>urn:av-openhome-org:service:HardwareConfig:1</serviceType><serviceId>urn:av-openhome-org:serviceId:HardwareConfig</serviceId><SCPDURL>/HardwareConfig/scpd.xml</SCPDURL><controlURL>/HardwareConfig/control</controlURL><eventSubURL>/HardwareConfig/event</eventSubURL></service>
    </serviceList>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
        <friendlyName>Lightning Server</friendlyName>
        <UDN>uuid:server-1</UDN>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><SCPDURL>/CD/scpd.xml</SCPDURL><controlURL>/CD/control</controlURL><eventSubURL>/CD/event</eventSubURL></service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>"""

    val DIDL = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
<container id="alb1" parentID="albums" childCount="2"><dc:title>An Album</dc:title><upnp:class>object.container.album.musicAlbum</upnp:class><upnp:artist>Artist</upnp:artist><upnp:albumArtURI>http://x/art.jpg</upnp:albumArtURI></container>
<item id="t1" parentID="alb1" restricted="1"><dc:title>Song A</dc:title><upnp:artist>Artist</upnp:artist><upnp:artist role="Composer">Bach</upnp:artist><upnp:album>An Album</upnp:album><upnp:originalTrackNumber>1</upnp:originalTrackNumber><upnp:class>object.item.audioItem.musicTrack</upnp:class>
<res protocolInfo="http-get:*:audio/x-flac:*" duration="0:04:05.000" size="1000">http://x/a.flac</res><lightning bitsPerSample="24" samplingRate="96000" fileType="flac"/></item>
<item id="t2" parentID="alb1" restricted="1"><dc:title>Song B &amp; C</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/mpeg:*" duration="0:03:00">http://x/b.mp3</res></item>
</DIDL-Lite>"""

    fun scpd(vararg actions: Pair<String, List<Pair<String, Boolean>>>): String = buildString {
        append("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><actionList>")
        for ((name, args) in actions) {
            append("<action><name>").append(name).append("</name><argumentList>")
            for ((a, input) in args) {
                append("<argument><name>").append(a).append("</name><direction>").append(if (input) "in" else "out").append("</direction></argument>")
            }
            append("</argumentList></action>")
        }
        append("</actionList><serviceStateTable/></scpd>")
    }

    fun soapResponse(action: String, service: String, vararg out: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>")
        append("<u:").append(action).append("Response xmlns:u=\"").append(service).append("\">")
        for ((k, v) in out) append('<').append(k).append('>').append(v).append("</").append(k).append('>')
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }
}
