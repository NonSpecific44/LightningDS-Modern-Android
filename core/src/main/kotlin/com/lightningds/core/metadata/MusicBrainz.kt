package com.lightningds.core.metadata

import com.lightningds.core.streaming.array
import com.lightningds.core.streaming.get
import com.lightningds.core.streaming.getJson
import com.lightningds.core.streaming.int
import com.lightningds.core.streaming.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReleaseCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val date: String?,
    val country: String?,
    val trackCount: Int,
    val format: String?,
    val score: Int,
    val releaseGroupId: String?,
) {
    /** Cover Art Archive thumbnail; may not exist (the image then fails to load). */
    val thumbnailUrl: String get() = MusicBrainz.coverUrl(id, 250)
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 }
}

data class ReleaseTrack(val disc: Int, val position: Int, val title: String, val artist: String?, val lengthSeconds: Int)

data class ReleaseDetail(
    val id: String,
    val title: String,
    val artist: String,
    val date: String?,
    val genre: String?,
    val releaseGroupId: String?,
    val tracks: List<ReleaseTrack>,
)

/**
 * Looks up album information on MusicBrainz and cover art on the Cover Art Archive, both free and
 * open. MusicBrainz asks clients to identify themselves and stay under one request per second.
 */
class MusicBrainz(private val http: OkHttpClient) {
    private val gate = Mutex()
    private var lastRequest = 0L

    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonElement = gate.withLock {
        val wait = lastRequest + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            val b = "$BASE/$path".toHttpUrl().newBuilder()
            for ((k, v) in params) b.addQueryParameter(k, v)
            b.addQueryParameter("fmt", "json")
            http.getJson(Request.Builder().url(b.build()).header("User-Agent", USER_AGENT).header("Accept", "application/json").build())
        } finally {
            lastRequest = System.currentTimeMillis()
        }
    }

    /** Searches releases by album title and/or artist. */
    suspend fun searchReleases(album: String?, artist: String?, limit: Int = 15): List<ReleaseCandidate> {
        val query = buildQuery("release" to album, "artist" to artist)
        if (query.isBlank()) return emptyList()
        return parseReleaseSearch(get("release", "query" to query, "limit" to limit.toString()))
    }

    /** Finds releases containing a recording, for tracks that have a title but no album. */
    suspend fun searchByTrack(title: String, artist: String?, limit: Int = 15): List<ReleaseCandidate> {
        val query = buildQuery("recording" to title, "artist" to artist)
        if (query.isBlank()) return emptyList()
        return parseRecordingSearch(get("recording", "query" to query, "limit" to limit.toString()))
    }

    suspend fun release(id: String): ReleaseDetail =
        parseRelease(get("release/$id", "inc" to "recordings+artist-credits+genres+release-groups"))

    /**
     * Returns a usable front cover URL for the release (or, failing that, its release group),
     * or null if the Cover Art Archive has none.
     */
    suspend fun findCover(releaseId: String, releaseGroupId: String?, size: Int = 500): String? = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(coverUrl(releaseId, size), releaseGroupId?.let { "$CAA/release-group/$it/front-$size" })
        candidates.firstOrNull { url ->
            runCatching {
                http.newCall(Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    companion object {
        const val BASE = "https://musicbrainz.org/ws/2"
        const val CAA = "https://coverartarchive.org"
        const val USER_AGENT = "LightningDS-Modern/2.0 ( https://github.com/NonSpecific44/LightningDS-Modern-Android )"
        private const val MIN_INTERVAL_MS = 1100L

        fun coverUrl(releaseId: String, size: Int = 500) = "$CAA/release/$releaseId/front-$size"

        /** Builds a Lucene query, quoting and escaping each non-blank field. */
        fun buildQuery(vararg fields: Pair<String, String?>): String = fields
            .mapNotNull { (k, v) -> v?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }?.let { "$k:\"${escape(it)}\"" } }
            .joinToString(" AND ")

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun credit(j: JsonElement?): String = j.array().joinToString("") { c ->
            (c["name"].str() ?: c["artist"]["name"].str().orEmpty()) + c["joinphrase"].str().orEmpty()
        }

        private fun candidate(r: JsonElement, score: Int = r["score"].int()): ReleaseCandidate {
            val media = r["media"].array()
            return ReleaseCandidate(
                id = r["id"].str().orEmpty(),
                title = r["title"].str().orEmpty(),
                artist = credit(r["artist-credit"]),
                date = r["date"].str()?.ifBlank { null },
                country = r["country"].str(),
                trackCount = r["track-count"].int().takeIf { it > 0 } ?: media.sumOf { it["track-count"].int() },
                format = media.mapNotNull { it["format"].str() }.distinct().joinToString(" + ").ifBlank { null },
                score = score,
                releaseGroupId = r["release-group"]["id"].str(),
            )
        }

        fun parseReleaseSearch(j: JsonElement): List<ReleaseCandidate> =
            j["releases"].array().map { candidate(it) }.filter { it.id.isNotBlank() }

        fun parseRecordingSearch(j: JsonElement): List<ReleaseCandidate> =
            j["recordings"].array().flatMap { rec ->
                val score = rec["score"].int()
                val artist = credit(rec["artist-credit"])
                rec["releases"].array().map { r ->
                    candidate(r, score).let { c -> if (c.artist.isBlank()) c.copy(artist = artist) else c }
                }
            }.filter { it.id.isNotBlank() }.distinctBy { it.id }

        fun parseRelease(j: JsonElement): ReleaseDetail {
            val albumArtist = credit(j["artist-credit"])
            val genre = (j["genres"].array() + j["release-group"]["genres"].array())
                .maxByOrNull { it["count"].int() }?.get("name").str()?.replaceFirstChar { it.titlecase() }
            val tracks = j["media"].array().flatMapIndexed { mi, m ->
                val disc = m["position"].int().takeIf { it > 0 } ?: (mi + 1)
                m["tracks"].array().mapIndexed { ti, t ->
                    val artist = credit(t["artist-credit"]).ifBlank { credit(t["recording"]["artist-credit"]) }
                    ReleaseTrack(
                        disc = disc,
                        position = t["position"].int().takeIf { it > 0 } ?: (ti + 1),
                        title = t["title"].str() ?: t["recording"]["title"].str().orEmpty(),
                        artist = artist.ifBlank { null },
                        lengthSeconds = ((t["length"].int().takeIf { it > 0 } ?: t["recording"]["length"].int()) + 500) / 1000,
                    )
                }
            }
            return ReleaseDetail(
                id = j["id"].str().orEmpty(),
                title = j["title"].str().orEmpty(),
                artist = albumArtist,
                date = j["date"].str()?.ifBlank { null },
                genre = genre,
                releaseGroupId = j["release-group"]["id"].str(),
                tracks = tracks,
            )
        }
    }
}

/** Builds edits for [album] from a MusicBrainz [release]. Only fills what differs from the server. */
object ReleaseMatcher {
    data class Result(val album: com.lightningds.core.library.AlbumEdit, val tracks: Map<String, com.lightningds.core.library.TrackEdit>)

    fun match(album: com.lightningds.core.library.Album, release: ReleaseDetail, coverUrl: String?): Result {
        val albumEdit = com.lightningds.core.library.AlbumEdit(
            title = release.title.takeIf { it.isNotBlank() },
            artist = release.artist.takeIf { it.isNotBlank() },
            date = release.date,
            genre = release.genre,
            artUri = coverUrl,
            musicBrainzId = release.id,
        )
        val local = album.tracks
        val numbered = local.isNotEmpty() && local.all { it.trackNumber > 0 } &&
            local.map { (it.discNumber.coerceAtLeast(1)) to it.trackNumber }.toSet().size == local.size
        val pairs: List<Pair<com.lightningds.core.media.Track, ReleaseTrack>> = if (numbered) {
            val byPos = release.tracks.associateBy { it.disc to it.position }
            local.mapNotNull { t -> byPos[t.discNumber.coerceAtLeast(1) to t.trackNumber]?.let { t to it } }
        } else {
            local.zip(release.tracks)
        }
        val edits = pairs.associate { (t, r) ->
            t.id to com.lightningds.core.library.TrackEdit(
                title = r.title.takeIf { it.isNotBlank() && it != t.title },
                artist = r.artist?.takeIf { it != t.artist },
                trackNumber = r.position.takeIf { it != t.trackNumber },
                discNumber = r.disc.takeIf { it != t.discNumber && !(it == 1 && t.discNumber == 0) },
            )
        }.filterValues { it != com.lightningds.core.library.TrackEdit() }
        return Result(albumEdit, edits)
    }
}
