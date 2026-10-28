package com.lightningds.core.streaming

import com.lightningds.core.media.Track
import com.lightningds.core.media.TrackSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class TidalSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val userId: String,
    val countryCode: String,
    val sessionId: String,
    val userName: String,
)

data class TidalDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/**
 * TIDAL catalogue client (v1 API, successor of the WiMP API used by Lightning DS 1.x).
 * Sign-in uses TIDAL's OAuth device flow; the client id/secret are entered by the user.
 */
class TidalService(
    private val http: OkHttpClient,
    private val clientId: () -> String,
    private val clientSecret: () -> String,
    session: TidalSession? = null,
    private val onSession: (TidalSession?) -> Unit = {},
) : StreamingService {
    override val name: String = NAME
    var session: TidalSession? = session
        private set

    override val isLoggedIn: Boolean get() = session != null
    override val userName: String? get() = session?.userName

    override val featuredSections = listOf(
        FeaturedSection("new", "New"),
        FeaturedSection("recommended", "Recommended"),
        FeaturedSection("top", "Top 20"),
        FeaturedSection("local", "Local"),
        FeaturedSection("exclusive", "Exclusive"),
    )

    // ---- authentication ----------------------------------------------------------------------

    suspend fun startDeviceLogin(): TidalDeviceCode {
        requireClient()
        val req = Request.Builder().url("$AUTH/device_authorization")
            .post(FormBody.Builder().add("client_id", clientId()).add("scope", SCOPE).build())
            .build()
        val j = http.getJson(req)
        return TidalDeviceCode(
            deviceCode = j["deviceCode"].str() ?: throw StreamingException("TIDAL did not return a device code"),
            userCode = j["userCode"].str().orEmpty(),
            verificationUri = j["verificationUriComplete"].str()?.let { if (it.startsWith("http")) it else "https://$it" }
                ?: j["verificationUri"].str()?.let { if (it.startsWith("http")) it else "https://$it" } ?: "https://link.tidal.com",
            expiresInSeconds = j["expiresIn"].int().takeIf { it > 0 } ?: 300,
            intervalSeconds = j["interval"].int().coerceAtLeast(2),
        )
    }

    /** Polls until the user approved the device code in the browser. */
    suspend fun completeDeviceLogin(code: TidalDeviceCode): TidalSession {
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.intervalSeconds * 1000L)
            val form = FormBody.Builder()
                .add("client_id", clientId())
                .apply { if (clientSecret().isNotBlank()) add("client_secret", clientSecret()) }
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("scope", SCOPE)
                .build()
            val j = try {
                http.getJson(Request.Builder().url("$AUTH/token").post(form).build())
            } catch (e: StreamingException) {
                if (e.code == 400 && (e.message?.contains("pending", true) == true || e.message?.contains("authorization", true) == true)) continue
                throw e
            }
            return finishLogin(j)
        }
        throw StreamingException("The TIDAL sign-in code expired. Please try again.")
    }

    private suspend fun finishLogin(token: JsonElement): TidalSession {
        val access = token["access_token"].str() ?: throw StreamingException("TIDAL sign-in failed")
        val expires = System.currentTimeMillis() + token["expires_in"].long() * 1000L
        val tmp = TidalSession(access, token["refresh_token"].str() ?: session?.refreshToken, expires, "", "", "", "")
        session = tmp
        val s = http.getJson(Request.Builder().url("$BASE/sessions").header("Authorization", "Bearer $access").build())
        val user = token["user"]
        val full = tmp.copy(
            userId = s["userId"].str() ?: s["userId"].long().toString(),
            countryCode = s["countryCode"].str() ?: user["countryCode"].str() ?: "US",
            sessionId = s["sessionId"].str().orEmpty(),
            userName = user["username"].str() ?: user["email"].str() ?: "TIDAL",
        )
        session = full
        onSession(full)
        return full
    }

    private suspend fun refreshIfNeeded() {
        val s = session ?: return
        if (System.currentTimeMillis() < s.expiresAt - 60_000L || s.refreshToken == null) return
        val form = FormBody.Builder()
            .add("client_id", clientId())
            .apply { if (clientSecret().isNotBlank()) add("client_secret", clientSecret()) }
            .add("refresh_token", s.refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", SCOPE)
            .build()
        val j = http.getJson(Request.Builder().url("$AUTH/token").post(form).build())
        val access = j["access_token"].str() ?: return
        val updated = s.copy(accessToken = access, expiresAt = System.currentTimeMillis() + j["expires_in"].long() * 1000L)
        session = updated
        onSession(updated)
    }

    override fun logout() {
        session = null
        onSession(null)
    }

    private fun requireClient() {
        if (clientId().isBlank()) throw StreamingException("A TIDAL client ID is required. Add it in Settings → Streaming services.")
    }

    private fun requireLogin(): TidalSession = session ?: throw StreamingException("Please sign in to TIDAL first.")

    // ---- requests ----------------------------------------------------------------------------

    private fun url(path: String, vararg params: Pair<String, Any?>): HttpUrl {
        val b = "$BASE/$path".toHttpUrl().newBuilder()
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v.toString())
        b.addQueryParameter("countryCode", session?.countryCode?.ifBlank { null } ?: "US")
        return b.build()
    }

    private suspend fun get(path: String, vararg params: Pair<String, Any?>): JsonElement {
        requireLogin()
        refreshIfNeeded()
        val req = Request.Builder().url(url(path, *params)).header("Authorization", "Bearer ${session!!.accessToken}").build()
        return http.getJson(req)
    }

    override suspend fun featuredAlbums(section: String, offset: Int, limit: Int): List<StreamAlbum> =
        get("featured/$section/albums", "limit" to limit, "offset" to offset).items().map(::album)

    override suspend fun featuredPlaylists(offset: Int, limit: Int): List<StreamPlaylist> =
        get("featured/recommended/playlists", "limit" to limit, "offset" to offset).items().map(::playlist)

    override suspend fun genres(): List<StreamGenre> = get("genres").array().filter { it["hasAlbums"].bool() || it["hasAlbums"] == null }.map {
        StreamGenre(it["path"].str().orEmpty(), it["name"].str().orEmpty(), image(it["image"].str(), 640, 428))
    }

    override suspend fun genreAlbums(genre: StreamGenre, offset: Int, limit: Int): List<StreamAlbum> =
        get("genres/${genre.id}/albums", "limit" to limit, "offset" to offset).items().map(::album)

    override suspend fun favorites(): Favorites {
        val uid = requireLogin().userId
        fun JsonElement.inner() = items().mapNotNull { it["item"] }
        return Favorites(
            albums = get("users/$uid/favorites/albums", "limit" to 500).inner().map(::album),
            artists = get("users/$uid/favorites/artists", "limit" to 500).inner().map(::artist),
            tracks = get("users/$uid/favorites/tracks", "limit" to 500).inner().map(::track),
            playlists = get("users/$uid/favorites/playlists", "limit" to 500).inner().map(::playlist),
        )
    }

    override suspend fun userPlaylists(): List<StreamPlaylist> =
        get("users/${requireLogin().userId}/playlists", "limit" to 500).items().map(::playlist)

    override suspend fun album(id: String): StreamAlbumDetail {
        val a = album(get("albums/$id"))
        val tracks = get("albums/$id/tracks", "limit" to 500).items().map(::track)
        return StreamAlbumDetail(a, tracks)
    }

    override suspend fun artist(id: String): StreamArtistDetail {
        val a = artist(get("artists/$id"))
        val albums = get("artists/$id/albums", "limit" to 200).items().map(::album)
        val top = runCatching { get("artists/$id/toptracks", "limit" to 20).items().map(::track) }.getOrDefault(emptyList())
        return StreamArtistDetail(a, albums, top)
    }

    override suspend fun playlist(id: String): StreamPlaylistDetail {
        val p = playlist(get("playlists/$id"))
        val tracks = get("playlists/$id/tracks", "limit" to 500).items().map(::track)
        return StreamPlaylistDetail(p, tracks)
    }

    override suspend fun search(query: String, limit: Int): StreamSearchResult {
        val j = get("search", "query" to query, "types" to "ARTISTS,ALBUMS,TRACKS,PLAYLISTS", "limit" to limit)
        return StreamSearchResult(
            albums = j["albums"].items().map(::album),
            artists = j["artists"].items().map(::artist),
            tracks = j["tracks"].items().map(::track),
            playlists = j["playlists"].items().map(::playlist),
        )
    }

    override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean) {
        val s = requireLogin()
        refreshIfNeeded()
        val (path, param) = when (kind) {
            FavoriteKind.ALBUMS -> "albums" to "albumIds"
            FavoriteKind.ARTISTS -> "artists" to "artistIds"
            FavoriteKind.TRACKS -> "tracks" to "trackIds"
            FavoriteKind.PLAYLISTS -> "playlists" to "uuids"
        }
        val req = if (favorite) {
            Request.Builder().url(url("users/${s.userId}/favorites/$path"))
                .post(FormBody.Builder().add(param, id).build())
        } else {
            Request.Builder().url(url("users/${s.userId}/favorites/$path/$id")).delete()
        }.header("Authorization", "Bearer ${session!!.accessToken}").build()
        http.getJson(req)
    }

    // ---- mapping -----------------------------------------------------------------------------

    private fun album(j: JsonElement) = StreamAlbum(
        id = j["id"].str() ?: j["id"].long().toString(),
        title = j["title"].str().orEmpty(),
        artist = j["artist"]["name"].str() ?: j["artists"].array().firstOrNull()["name"].str().orEmpty(),
        artistId = j["artist"]["id"].let { it.str() ?: it.long().toString() },
        artUrl = image(j["cover"].str(), 640, 640),
        year = j["releaseDate"].str()?.take(4),
        trackCount = j["numberOfTracks"].int(),
        quality = j["audioQuality"].str(),
    )

    private fun artist(j: JsonElement) = StreamArtist(
        id = j["id"].str() ?: j["id"].long().toString(),
        name = j["name"].str().orEmpty(),
        pictureUrl = image(j["picture"].str(), 480, 480),
    )

    private fun playlist(j: JsonElement) = StreamPlaylist(
        id = j["uuid"].str().orEmpty(),
        title = j["title"].str().orEmpty(),
        description = j["description"].str(),
        artUrl = image(j["squareImage"].str(), 480, 480) ?: image(j["image"].str(), 640, 428),
        trackCount = j["numberOfTracks"].int(),
        owner = j["creator"]["name"].str(),
    )

    private fun track(j: JsonElement): Track {
        val id = j["id"].str() ?: j["id"].long().toString()
        val alb = j["album"]
        return Track(
            id = id,
            parentId = alb["id"].str() ?: alb["id"].long().toString(),
            title = j["title"].str().orEmpty().let { t -> j["version"].str()?.let { "$t ($it)" } ?: t },
            artist = j["artist"]["name"].str() ?: j["artists"].array().firstOrNull()["name"].str(),
            album = alb["title"].str(),
            date = j["streamStartDate"].str()?.take(10),
            trackNumber = j["trackNumber"].int(),
            discNumber = j["volumeNumber"].int(),
            albumArtUri = image(alb["cover"].str(), 640, 640),
            uri = trackUri(id),
            protocolInfo = "http-get:*:audio/x-flac:*",
            durationSeconds = j["duration"].int(),
            fileType = "flac",
            source = TrackSource.STREAMING,
            sourceKey = NAME,
        )
    }

    /** URI understood by Lightning firmware (same format Lightning DS 1.x produced for WiMP/TIDAL). */
    fun trackUri(trackId: String): String {
        val s = session
        return "Streaming://$NAME?user=${s?.userId.orEmpty()}&track_id=$trackId&quality=$QUALITY" +
            "&session=${s?.sessionId.orEmpty()}&countryCode=${s?.countryCode.orEmpty()}"
    }

    companion object {
        const val NAME = "TIDAL"
        const val BASE = "https://api.tidal.com/v1"
        const val AUTH = "https://auth.tidal.com/v1/oauth2"
        const val SCOPE = "r_usr w_usr w_sub"
        const val QUALITY = "LOSSLESS"

        fun image(uuid: String?, w: Int, h: Int): String? =
            uuid?.takeIf { it.isNotBlank() }?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/${w}x$h.jpg" }
    }
}
