package com.lightningds.core.streaming

import com.lightningds.core.media.Track
import com.lightningds.core.media.TrackSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

@Serializable
data class QobuzSession(val userId: String, val token: String, val displayName: String)

/**
 * Qobuz catalogue client (api.json/0.2, the API Lightning DS used).
 * Qobuz requires an application id issued by Qobuz; it's entered by the user in Settings.
 */
class QobuzService(
    private val http: OkHttpClient,
    private val appId: () -> String,
    session: QobuzSession? = null,
    private val onSession: (QobuzSession?) -> Unit = {},
) : StreamingService {
    override val name: String = NAME
    var session: QobuzSession? = session
        private set

    override val isLoggedIn: Boolean get() = session != null
    override val userName: String? get() = session?.displayName
    override val supportsPurchases: Boolean = true

    override val featuredSections = listOf(
        FeaturedSection("new-releases", "New Releases"),
        FeaturedSection("press-awards", "Press Awards"),
        FeaturedSection("best-sellers", "Best Sellers"),
        FeaturedSection("editor-picks", "Editor's Picks"),
        FeaturedSection("most-streamed", "Most Streamed"),
        FeaturedSection("ideal-discography", "Ideal Discography"),
    )

    private fun url(path: String, vararg params: Pair<String, Any?>): HttpUrl {
        val b = "$BASE/$path".toHttpUrl().newBuilder()
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v.toString())
        b.addQueryParameter("app_id", appId())
        return b.build()
    }

    private fun request(url: HttpUrl): Request = Request.Builder().url(url).apply {
        header("X-App-Id", appId())
        session?.let { header("X-User-Auth-Token", it.token) }
    }.build()

    private suspend fun get(path: String, vararg params: Pair<String, Any?>): JsonElement {
        if (appId().isBlank()) throw StreamingException("A Qobuz application ID is required. Add it in Settings → Streaming services.")
        return http.getJson(request(url(path, *params)))
    }

    suspend fun login(username: String, password: String): QobuzSession {
        if (appId().isBlank()) throw StreamingException("A Qobuz application ID is required. Add it in Settings → Streaming services.")
        val req = Request.Builder()
            .url(url("user/login"))
            .header("X-App-Id", appId())
            .post(FormBody.Builder().add("username", username).add("password", md5(password)).add("email", username).build())
            .build()
        val j = http.getJson(req)
        val token = j["user_auth_token"].str() ?: throw StreamingException("Login failed")
        val user = j["user"]
        val s = QobuzSession(user["id"].str() ?: user["id"].long().toString(), token, user["display_name"].str() ?: user["login"].str() ?: username)
        session = s
        onSession(s)
        return s
    }

    override fun logout() {
        session = null
        onSession(null)
    }

    override suspend fun featuredAlbums(section: String, offset: Int, limit: Int): List<StreamAlbum> =
        get("album/getFeatured", "type" to section, "limit" to limit, "offset" to offset)["albums"].items().map(::album)

    override suspend fun featuredPlaylists(offset: Int, limit: Int): List<StreamPlaylist> =
        get("playlist/getFeatured", "type" to "editor-picks", "limit" to limit, "offset" to offset)["playlists"].items().map(::playlist)

    override suspend fun genres(): List<StreamGenre> =
        get("genre/list")["genres"].items().map { StreamGenre(it["id"].str() ?: it["id"].int().toString(), it["name"].str().orEmpty()) }

    override suspend fun genreAlbums(genre: StreamGenre, offset: Int, limit: Int): List<StreamAlbum> =
        get("album/getFeatured", "type" to "new-releases", "genre_id" to genre.id, "limit" to limit, "offset" to offset)["albums"].items().map(::album)

    override suspend fun favorites(): Favorites {
        requireLogin()
        val j = get("favorite/getUserFavorites", "user_id" to session?.userId, "limit" to 500)
        return Favorites(
            albums = j["albums"].items().map(::album),
            artists = j["artists"].items().map(::artist),
            tracks = j["tracks"].items().map { track(it, null) },
        )
    }

    override suspend fun userPlaylists(): List<StreamPlaylist> {
        requireLogin()
        return get("playlist/getUserPlaylists", "user_id" to session?.userId, "limit" to 500)["playlists"].items().map(::playlist)
    }

    override suspend fun purchases(): Favorites {
        requireLogin()
        val j = get("purchase/getUserPurchases", "limit" to 500)
        return Favorites(albums = j["albums"].items().map(::album), tracks = j["tracks"].items().map { track(it, null) })
    }

    override suspend fun album(id: String): StreamAlbumDetail {
        val j = get("album/get", "album_id" to id)
        val a = album(j)
        return StreamAlbumDetail(a, j["tracks"].items().map { track(it, j) })
    }

    override suspend fun artist(id: String): StreamArtistDetail {
        val j = get("artist/get", "artist_id" to id, "extra" to "albums", "limit" to 200)
        return StreamArtistDetail(artist(j), j["albums"].items().map(::album), emptyList())
    }

    override suspend fun playlist(id: String): StreamPlaylistDetail {
        val j = get("playlist/get", "playlist_id" to id, "extra" to "tracks", "limit" to 500)
        return StreamPlaylistDetail(playlist(j), j["tracks"].items().map { track(it, null) })
    }

    override suspend fun search(query: String, limit: Int): StreamSearchResult {
        val j = get("catalog/search", "query" to query, "limit" to limit)
        return StreamSearchResult(
            albums = j["albums"].items().map(::album),
            artists = j["artists"].items().map(::artist),
            tracks = j["tracks"].items().map { track(it, null) },
            playlists = j["playlists"].items().map(::playlist),
        )
    }

    override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean) {
        requireLogin()
        val key = when (kind) {
            FavoriteKind.ALBUMS -> "album_ids"
            FavoriteKind.ARTISTS -> "artist_ids"
            FavoriteKind.TRACKS -> "track_ids"
            FavoriteKind.PLAYLISTS -> {
                get(if (favorite) "playlist/subscribe" else "playlist/unsubscribe", "playlist_id" to id)
                return
            }
        }
        get(if (favorite) "favorite/create" else "favorite/delete", key to id)
    }

    private fun requireLogin() {
        if (session == null) throw StreamingException("Please sign in to Qobuz first.")
    }

    // ---- mapping --------------------------------------------------------------------------

    private fun image(j: JsonElement?): String? = j["image"].let { it["large"].str() ?: it["small"].str() ?: it["thumbnail"].str() }

    private fun album(j: JsonElement): StreamAlbum {
        val bits = j["maximum_bit_depth"].int()
        val rate = j["maximum_sampling_rate"].double()
        return StreamAlbum(
            id = j["id"].str() ?: j["id"].long().toString(),
            title = j["title"].str().orEmpty(),
            artist = j["artist"]["name"].str().orEmpty(),
            artistId = j["artist"]["id"].let { it.str() ?: it.long().toString() },
            artUrl = image(j),
            year = j["release_date_original"].str()?.take(4) ?: j["released_at"].long().takeIf { it > 0 }?.let { yearOf(it) },
            trackCount = j["tracks_count"].int(),
            quality = if (bits > 0) "${bits}bit / ${rate.toString().removeSuffix(".0")}kHz" else null,
        )
    }

    private fun artist(j: JsonElement) = StreamArtist(
        id = j["id"].str() ?: j["id"].long().toString(),
        name = j["name"].str().orEmpty(),
        pictureUrl = j["image"]["large"].str() ?: j["picture"].str(),
    )

    private fun playlist(j: JsonElement) = StreamPlaylist(
        id = j["id"].str() ?: j["id"].long().toString(),
        title = j["name"].str().orEmpty(),
        description = j["description"].str(),
        artUrl = j["images300"].array().firstOrNull().str() ?: j["image_rectangle"].array().firstOrNull().str() ?: j["images"].array().firstOrNull().str(),
        trackCount = j["tracks_count"].int(),
        owner = j["owner"]["name"].str(),
    )

    private fun track(j: JsonElement, albumJson: JsonElement?): Track {
        val alb = j["album"] ?: albumJson
        val id = j["id"].str() ?: j["id"].long().toString()
        val bits = j["maximum_bit_depth"].int()
        val rate = (j["maximum_sampling_rate"].double() * 1000).toInt()
        return Track(
            id = id,
            parentId = alb["id"].str() ?: alb["id"].long().toString(),
            title = j["title"].str().orEmpty().let { t -> j["version"].str()?.let { "$t ($it)" } ?: t },
            artist = j["performer"]["name"].str() ?: alb["artist"]["name"].str(),
            albumArtist = alb["artist"]["name"].str(),
            composer = j["composer"]["name"].str(),
            album = alb["title"].str(),
            genre = alb["genre"]["name"].str(),
            date = alb["release_date_original"].str(),
            trackNumber = j["track_number"].int(),
            discNumber = j["media_number"].int(),
            albumArtUri = image(alb),
            uri = trackUri(id),
            protocolInfo = "http-get:*:audio/x-flac:*",
            durationSeconds = j["duration"].int(),
            bitsPerSample = bits,
            sampleFrequency = rate,
            fileType = "flac",
            source = TrackSource.STREAMING,
            sourceKey = NAME,
        )
    }

    /** URI understood by Lightning firmware: the device fetches the stream itself. */
    fun trackUri(trackId: String): String {
        val s = session
        return "Streaming://$NAME?user=${s?.userId.orEmpty()}&track_id=$trackId&quality=$QUALITY&session=${s?.token.orEmpty()}"
    }

    companion object {
        const val NAME = "Qobuz"
        const val BASE = "https://www.qobuz.com/api.json/0.2"
        /** 6 = FLAC 16/44.1, 27 = Hi-Res; Lightning DS 1.x always requested 6. */
        const val QUALITY = "6"

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun yearOf(epochSeconds: Long): String =
            java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).year.toString()
    }
}
