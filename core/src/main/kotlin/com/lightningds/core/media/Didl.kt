package com.lightningds.core.media

import com.lightningds.core.upnp.Xml
import com.lightningds.core.upnp.attr
import com.lightningds.core.upnp.children
import com.lightningds.core.upnp.localName2
import kotlinx.serialization.Serializable
import org.w3c.dom.Element

/** Where a track comes from. Mirrors the original app's "server type". */
@Serializable
enum class TrackSource { LIBRARY, STREAMING, RADIO, UNKNOWN }

@Serializable
data class Track(
    val id: String = "",
    val parentId: String = "",
    val title: String = "",
    val artist: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val conductor: String? = null,
    val orchestra: String? = null,
    val creator: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val albumArtUri: String? = null,
    val uri: String? = null,
    val protocolInfo: String? = null,
    val durationSeconds: Int = 0,
    val size: Long = 0,
    val bitrate: Int = 0,
    val bitsPerSample: Int = 0,
    val sampleFrequency: Int = 0,
    val channels: Int = 0,
    val fileType: String? = null,
    val upnpClass: String = "object.item.audioItem.musicTrack",
    val source: TrackSource = TrackSource.LIBRARY,
    /** Opaque key identifying the owning server/service (server UDN, "Qobuz", "TIDAL"...). */
    val sourceKey: String? = null,
) {
    val displayArtist: String get() = artist ?: albumArtist ?: creator ?: ""

    /** "24bit / 96kHz" style label (Lightning Server provides these). */
    val qualityLabel: String?
        get() {
            val parts = buildList {
                fileType?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                if (bitsPerSample > 0) add("${bitsPerSample}bit")
                if (sampleFrequency > 0) add(formatRate(sampleFrequency))
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
}

@Serializable
data class Container(
    val id: String,
    val parentId: String = "",
    val title: String = "",
    val upnpClass: String = "object.container",
    val childCount: Int = -1,
    val artist: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val albumArtUri: String? = null,
) {
    val isAlbum: Boolean get() = upnpClass.startsWith("object.container.album")
}

data class DidlResult(val containers: List<Container>, val items: List<Track>)

fun formatRate(hz: Int): String = when {
    hz >= 1_000_000 -> "%.1fMHz".format(hz / 1_000_000.0)
    hz % 1000 == 0 -> "${hz / 1000}kHz"
    else -> "%.1fkHz".format(hz / 1000.0)
}

fun parseDuration(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    val main = text.trim().substringBefore('.')
    val parts = main.split(':').map { it.toIntOrNull() ?: 0 }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> 0
    }
}

fun formatDuration(seconds: Int, forceHours: Boolean = false): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0 || forceHours) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

object Didl {
    fun parse(xml: String?, source: TrackSource = TrackSource.LIBRARY, sourceKey: String? = null): DidlResult {
        if (xml.isNullOrBlank()) return DidlResult(emptyList(), emptyList())
        var doc = Xml.parseOrNull(xml)
        if (doc == null && xml.contains("&lt;")) {
            doc = Xml.parseOrNull(xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""))
        }
        if (doc == null) return DidlResult(emptyList(), emptyList())
        val root = doc.documentElement
        val containers = root.children("container").map { parseContainer(it) }
        val items = root.children("item").map { parseItem(it, source, sourceKey) }
        return DidlResult(containers, items)
    }

    fun parseFirstItem(xml: String?, source: TrackSource = TrackSource.UNKNOWN): Track? =
        parse(xml, source).items.firstOrNull()

    private fun parseContainer(el: Element): Container {
        var title = ""
        var cls = "object.container"
        var artist: String? = null
        var genre: String? = null
        var date: String? = null
        var art: String? = null
        for (c in el.children()) {
            val t = c.textContent?.trim().orEmpty()
            when (c.localName2) {
                "title" -> title = t
                "class" -> cls = t
                "artist" -> if (artist == null || c.attr("role") == "AlbumArtist") artist = t
                "creator" -> if (artist == null) artist = t
                "genre" -> if (genre == null) genre = t
                "date" -> date = t
                "albumArtURI" -> if (art == null) art = t
            }
        }
        return Container(
            id = el.attr("id").orEmpty(),
            parentId = el.attr("parentID").orEmpty(),
            title = title,
            upnpClass = cls,
            childCount = el.attr("childCount")?.toIntOrNull() ?: -1,
            artist = artist?.ifBlank { null },
            genre = genre?.ifBlank { null },
            date = date?.ifBlank { null },
            albumArtUri = art?.ifBlank { null },
        )
    }

    private fun parseItem(el: Element, source: TrackSource, sourceKey: String?): Track {
        var t = Track(id = el.attr("id").orEmpty(), parentId = el.attr("parentID").orEmpty(), source = source, sourceKey = sourceKey)
        var resSet = false
        for (c in el.children()) {
            val text = c.textContent?.trim().orEmpty()
            when (c.localName2) {
                "title" -> t = t.copy(title = text)
                "artist" -> t = when (c.attr("role")) {
                    "Composer" -> t.copy(composer = text.ifBlank { null })
                    "AlbumArtist" -> t.copy(albumArtist = text.ifBlank { null })
                    "Conductor" -> t.copy(conductor = text.ifBlank { null })
                    "Orchestra" -> t.copy(orchestra = text.ifBlank { null })
                    else -> if (t.artist == null) t.copy(artist = text.ifBlank { null }) else t
                }
                "creator" -> t = t.copy(creator = text.ifBlank { null })
                "album" -> t = t.copy(album = text.ifBlank { null })
                "genre" -> if (t.genre == null) t = t.copy(genre = text.ifBlank { null })
                "date" -> t = t.copy(date = text.ifBlank { null })
                "originalTrackNumber" -> t = t.copy(trackNumber = text.toIntOrNull() ?: 0)
                "originalDiscNumber" -> t = t.copy(discNumber = text.toIntOrNull() ?: 0)
                "albumArtURI" -> if (t.albumArtUri == null) t = t.copy(albumArtUri = text.ifBlank { null })
                "class" -> t = t.copy(upnpClass = text)
                "res" -> if (!resSet) {
                    resSet = true
                    t = t.copy(
                        uri = text.ifBlank { null },
                        protocolInfo = c.attr("protocolInfo"),
                        durationSeconds = parseDuration(c.attr("duration")),
                        size = c.attr("size")?.toLongOrNull() ?: 0,
                        bitrate = c.attr("bitrate")?.toIntOrNull() ?: 0,
                        bitsPerSample = c.attr("bitsPerSample")?.toIntOrNull() ?: t.bitsPerSample,
                        sampleFrequency = c.attr("sampleFrequency")?.toIntOrNull() ?: t.sampleFrequency,
                        channels = c.attr("nrAudioChannels")?.toIntOrNull() ?: 0,
                    )
                }
                // Lightning Server extension carrying the real file format.
                "lightning" -> t = t.copy(
                    bitsPerSample = c.attr("bitsPerSample")?.toIntOrNull() ?: t.bitsPerSample,
                    sampleFrequency = (c.attr("samplingRate") ?: c.attr("sampleFrequency"))?.toIntOrNull() ?: t.sampleFrequency,
                    channels = c.attr("nrAudioChannels")?.toIntOrNull() ?: t.channels,
                    fileType = c.attr("fileType") ?: t.fileType,
                )
            }
        }
        if (t.fileType == null) t = t.copy(fileType = guessFileType(t.protocolInfo, t.uri))
        return t
    }

    private fun guessFileType(protocolInfo: String?, uri: String?): String? {
        val mime = protocolInfo?.split(':')?.getOrNull(2)?.lowercase()
        val fromMime = when {
            mime == null -> null
            "flac" in mime -> "flac"
            "wav" in mime -> "wav"
            "aiff" in mime -> "aiff"
            "dsd" in mime || "dsf" in mime -> "dsf"
            "dff" in mime -> "dff"
            "mpeg" in mime || "mp3" in mime -> "mp3"
            "mp4" in mime || "m4a" in mime || "aac" in mime -> "m4a"
            "alac" in mime -> "alac"
            "ogg" in mime -> "ogg"
            else -> null
        }
        if (fromMime != null) return fromMime
        val ext = uri?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
        return ext?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
    }

    /** Builds a DIDL-Lite document for one track, suitable for Playlist.Insert / SetAVTransportURI. */
    fun build(track: Track): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        append("<item id=\"").append(Xml.escape(track.id.ifBlank { "1" }))
        append("\" parentID=\"").append(Xml.escape(track.parentId.ifBlank { "0" }))
        append("\" restricted=\"1\">")
        tag("dc:title", track.title)
        track.date?.let { tag("dc:date", it) }
        track.album?.let { tag("upnp:album", it) }
        track.artist?.let { tag("upnp:artist", it) }
        track.albumArtist?.let { append("<upnp:artist role=\"AlbumArtist\">").append(Xml.escape(it)).append("</upnp:artist>") }
        track.composer?.let { append("<upnp:artist role=\"Composer\">").append(Xml.escape(it)).append("</upnp:artist>") }
        track.conductor?.let { append("<upnp:artist role=\"Conductor\">").append(Xml.escape(it)).append("</upnp:artist>") }
        (track.creator ?: track.artist)?.let { tag("dc:creator", it) }
        track.genre?.let { tag("upnp:genre", it) }
        if (track.trackNumber > 0) tag("upnp:originalTrackNumber", track.trackNumber.toString())
        track.albumArtUri?.let { tag("upnp:albumArtURI", it) }
        append("<res protocolInfo=\"").append(Xml.escape(track.protocolInfo ?: "http-get:*:*:*")).append('"')
        if (track.durationSeconds > 0) append(" duration=\"").append(formatDuration(track.durationSeconds, true)).append(".000\"")
        if (track.size > 0) append(" size=\"").append(track.size).append('"')
        if (track.bitrate > 0) append(" bitrate=\"").append(track.bitrate).append('"')
        if (track.bitsPerSample > 0) append(" bitsPerSample=\"").append(track.bitsPerSample).append('"')
        if (track.sampleFrequency > 0) append(" sampleFrequency=\"").append(track.sampleFrequency).append('"')
        if (track.channels > 0) append(" nrAudioChannels=\"").append(track.channels).append('"')
        append('>').append(Xml.escape(track.uri.orEmpty())).append("</res>")
        tag("upnp:class", track.upnpClass)
        append("</item></DIDL-Lite>")
    }

    private fun StringBuilder.tag(name: String, value: String) {
        append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>')
    }
}
