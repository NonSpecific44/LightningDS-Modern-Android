package com.lightningds.core.streaming

import com.lightningds.core.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class StreamAlbum(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val artUrl: String? = null,
    val year: String? = null,
    val trackCount: Int = 0,
    val quality: String? = null,
)

@Serializable
data class StreamArtist(val id: String, val name: String, val pictureUrl: String? = null)

@Serializable
data class StreamPlaylist(
    val id: String,
    val title: String,
    val description: String? = null,
    val artUrl: String? = null,
    val trackCount: Int = 0,
    val owner: String? = null,
)

data class StreamGenre(val id: String, val name: String, val artUrl: String? = null)

data class StreamAlbumDetail(val album: StreamAlbum, val tracks: List<Track>)
data class StreamArtistDetail(val artist: StreamArtist, val albums: List<StreamAlbum>, val topTracks: List<Track>)
data class StreamPlaylistDetail(val playlist: StreamPlaylist, val tracks: List<Track>)

data class StreamSearchResult(
    val albums: List<StreamAlbum> = emptyList(),
    val artists: List<StreamArtist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<StreamPlaylist> = emptyList(),
)

/** A named row of content on the "Featured" page. */
data class FeaturedSection(val key: String, val title: String)

enum class FavoriteKind { ALBUMS, ARTISTS, TRACKS, PLAYLISTS }

data class Favorites(
    val albums: List<StreamAlbum> = emptyList(),
    val artists: List<StreamArtist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<StreamPlaylist> = emptyList(),
)

class StreamingException(message: String, val code: Int = 0) : IOException(message)

/**
 * Common surface of the streaming services the original app integrated (TIDAL/WiMP and Qobuz).
 * Playback is done by the Lightning device itself: the app hands it a `Streaming://` URI with the
 * user's session, exactly as Lightning DS 1.x did.
 */
interface StreamingService {
    val name: String
    val isLoggedIn: Boolean
    val userName: String?
    val supportsPurchases: Boolean get() = false
    val featuredSections: List<FeaturedSection>

    fun logout()

    suspend fun featuredAlbums(section: String, offset: Int = 0, limit: Int = 50): List<StreamAlbum>
    suspend fun featuredPlaylists(offset: Int = 0, limit: Int = 50): List<StreamPlaylist>
    suspend fun genres(): List<StreamGenre>
    suspend fun genreAlbums(genre: StreamGenre, offset: Int = 0, limit: Int = 50): List<StreamAlbum>
    suspend fun favorites(): Favorites
    suspend fun userPlaylists(): List<StreamPlaylist>
    suspend fun purchases(): Favorites = Favorites()
    suspend fun album(id: String): StreamAlbumDetail
    suspend fun artist(id: String): StreamArtistDetail
    suspend fun playlist(id: String): StreamPlaylistDetail
    suspend fun search(query: String, limit: Int = 25): StreamSearchResult
    suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean)
}

// ---- small JSON helpers shared by the clients ------------------------------------------------

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.int(): Int = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 0
internal fun JsonElement?.long(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonElement?.double(): Double = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
internal fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
internal fun JsonElement?.items(): List<JsonElement> = this["items"].array()

internal suspend fun OkHttpClient.getJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
    newCall(request).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val msg = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull()?.let {
                it["message"].str() ?: it["userMessage"].str() ?: it["error_description"].str() ?: it["error"].str()
            }
            throw StreamingException(msg ?: "HTTP ${r.code}", r.code)
        }
        if (body.isBlank()) JsonObject(emptyMap()) else lenientJson.parseToJsonElement(body)
    }
}
