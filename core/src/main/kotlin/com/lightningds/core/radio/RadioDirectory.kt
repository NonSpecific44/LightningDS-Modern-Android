package com.lightningds.core.radio

import com.lightningds.core.media.Track
import com.lightningds.core.media.TrackSource
import com.lightningds.core.streaming.array
import com.lightningds.core.streaming.get
import com.lightningds.core.streaming.getJson
import com.lightningds.core.streaming.int
import com.lightningds.core.streaming.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val tags: String? = null,
    val country: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
) {
    val subtitle: String
        get() = listOfNotNull(
            country?.takeIf { it.isNotBlank() },
            tags?.split(',')?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
            if (bitrate > 0) "${codec.orEmpty()} ${bitrate}kbps".trim() else codec,
        ).joinToString(" · ")

    fun toTrack(): Track = Track(
        id = id,
        title = name,
        artist = subtitle.ifBlank { null },
        albumArtUri = logo,
        uri = url,
        protocolInfo = "http-get:*:*:*",
        upnpClass = "object.item.audioItem.audioBroadcast",
        source = TrackSource.RADIO,
        sourceKey = "radio",
    )
}

data class RadioCategory(val name: String, val code: String, val count: Int)

/**
 * Internet radio directory backed by the community Radio Browser API (radio-browser.info).
 * The vTuner directory used by Lightning DS 1.x required Auralic's partner key.
 */
class RadioDirectory(private val http: OkHttpClient) {
    @Volatile
    private var base: String? = null

    private suspend fun server(): String {
        base?.let { return it }
        val host = withContext(Dispatchers.IO) {
            runCatching {
                InetAddress.getAllByName("all.api.radio-browser.info").map { it.canonicalHostName }
                    .filter { it.contains("radio-browser") }.shuffled().firstOrNull()
            }.getOrNull()
        } ?: FALLBACK_HOST
        return "https://$host/json".also { base = it }
    }

    private suspend fun get(path: String, vararg params: Pair<String, Any?>): JsonElement {
        val b = (server() + "/" + path).toHttpUrl().newBuilder()
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v.toString())
        val req = Request.Builder().url(b.build()).header("User-Agent", "LightningDS-Android/2.0").build()
        return try {
            http.getJson(req)
        } catch (e: Exception) {
            base = null
            throw e
        }
    }

    suspend fun genres(limit: Int = 150): List<RadioCategory> =
        get("tags", "order" to "stationcount", "reverse" to true, "hidebroken" to true, "limit" to limit).array()
            .map { RadioCategory(it["name"].str().orEmpty(), it["name"].str().orEmpty(), it["stationcount"].int()) }
            .filter { it.name.isNotBlank() && it.count > 5 }

    suspend fun countries(): List<RadioCategory> =
        get("countries", "order" to "name", "hidebroken" to true).array()
            .map { RadioCategory(it["name"].str().orEmpty(), it["iso_3166_1"].str() ?: it["name"].str().orEmpty(), it["stationcount"].int()) }
            .filter { it.name.isNotBlank() && it.count > 0 }

    suspend fun byGenre(tag: String, offset: Int = 0, limit: Int = 100): List<RadioStation> =
        get("stations/bytagexact/$tag", "order" to "clickcount", "reverse" to true, "hidebroken" to true, "offset" to offset, "limit" to limit).array().map(::station)

    suspend fun byCountry(code: String, offset: Int = 0, limit: Int = 100): List<RadioStation> =
        get("stations/bycountrycodeexact/$code", "order" to "clickcount", "reverse" to true, "hidebroken" to true, "offset" to offset, "limit" to limit).array().map(::station)

    suspend fun popular(limit: Int = 100): List<RadioStation> =
        get("stations/topclick/$limit", "hidebroken" to true).array().map(::station)

    suspend fun search(query: String, limit: Int = 100): List<RadioStation> =
        get("stations/search", "name" to query, "order" to "clickcount", "reverse" to true, "hidebroken" to true, "limit" to limit).array().map(::station)

    /** Tells the directory a station was played (keeps its popularity ranking useful). */
    suspend fun reportClick(station: RadioStation) {
        runCatching { get("url/${station.id}") }
    }

    private fun station(j: JsonElement) = RadioStation(
        id = j["stationuuid"].str().orEmpty(),
        name = j["name"].str().orEmpty().trim(),
        url = j["url_resolved"].str()?.ifBlank { null } ?: j["url"].str().orEmpty(),
        logo = j["favicon"].str()?.ifBlank { null },
        tags = j["tags"].str(),
        country = j["country"].str(),
        codec = j["codec"].str(),
        bitrate = j["bitrate"].int(),
    )

    companion object {
        const val FALLBACK_HOST = "de1.api.radio-browser.info"
    }
}
