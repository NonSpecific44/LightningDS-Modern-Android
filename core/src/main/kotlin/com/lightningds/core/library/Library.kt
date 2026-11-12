package com.lightningds.core.library

import com.lightningds.core.media.Container
import com.lightningds.core.media.Track
import com.lightningds.core.media.formatRate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val artUri: String? = null,
    val tracks: List<Track> = emptyList(),
    /** When this app first imported the album (used for "date added" when the server has none). */
    val addedAt: Long = 0,
) {
    /** When the album arrived in the library: the server's import date, else when the app first saw it. */
    val importedAt: Long get() = tracks.maxOfOrNull { parseDateMillis(it.importDate) } ?.takeIf { it > 0 } ?: addedAt
    val modifiedAt: Long get() = tracks.maxOfOrNull { parseDateMillis(it.modifyDate) } ?: 0L
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    val durationSeconds: Int get() = tracks.sumOf { it.durationSeconds }
    val displayArtist: String get() = artist ?: tracks.firstNotNullOfOrNull { it.albumArtist ?: it.artist } ?: ""
}

@Serializable
data class Library(
    val serverUdn: String,
    val serverName: String,
    val albums: List<Album>,
    val scanVersion: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** Cache format version; older caches are re-imported in the background. */
    val schema: Int = 1,
) {
    val tracks: List<Track> get() = albums.flatMap { it.tracks }

    companion object {
        /** Bumped when imports gain new fields (2: import/modify dates and date added). */
        const val SCHEMA = 2
    }
}

/** Parses the date formats servers use (epoch seconds/ms, ISO 8601, "yyyy-MM-dd HH:mm:ss"); 0 if unknown. */
fun parseDateMillis(text: String?): Long {
    val s = text?.trim().orEmpty()
    if (s.isEmpty()) return 0
    s.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) n else n * 1000 }
    val iso = s.replace(' ', 'T').replace('/', '-')
    runCatching { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
    runCatching { return java.time.Instant.parse(iso).toEpochMilli() }
    runCatching { return java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
    runCatching { return java.time.LocalDate.parse(iso.take(10)).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
    return 0
}

/** Library sort orders. Date and length sorts read naturally newest/longest first. */
enum class LibrarySort { TITLE, ARTIST, YEAR, DATE_ADDED, DATE_MODIFIED, DURATION, TRACK_COUNT;

    /** Whether "descending" is the natural default for this sort. */
    val prefersDescending: Boolean get() = this != TITLE && this != ARTIST
}

/** The grouping categories offered by the library view (same as the original app). */
enum class LibraryCategory { ALBUMS, ARTISTS, COMPOSERS, GENRES, DATE, FILE_TYPE, SAMPLING_RATE, SONGS }

data class LibraryGroup(val key: String, val albums: List<Album>) {
    val artUri: String? get() = albums.firstNotNullOfOrNull { it.artUri }
}

object LibraryIndex {
    fun group(library: Library, category: LibraryCategory, descending: Boolean = false, sort: LibrarySort = LibrarySort.TITLE): List<LibraryGroup> {
        val map = LinkedHashMap<String, MutableSet<Album>>()
        fun add(key: String?, album: Album) {
            val k = key?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN
            map.getOrPut(k) { LinkedHashSet() }.add(album)
        }
        for (album in library.albums) {
            when (category) {
                LibraryCategory.ARTISTS -> {
                    val names = (album.tracks.mapNotNull { it.artist } + listOfNotNull(album.artist)).flatMap(::splitNames).distinct()
                    if (names.isEmpty()) add(null, album) else names.forEach { add(it, album) }
                }
                LibraryCategory.COMPOSERS -> {
                    val names = album.tracks.mapNotNull { it.composer }.flatMap(::splitNames).distinct()
                    if (names.isEmpty()) add(null, album) else names.forEach { add(it, album) }
                }
                LibraryCategory.GENRES -> {
                    val g = (album.tracks.mapNotNull { it.genre } + listOfNotNull(album.genre)).distinct()
                    if (g.isEmpty()) add(null, album) else g.forEach { add(it, album) }
                }
                LibraryCategory.DATE -> add(album.year ?: album.tracks.firstNotNullOfOrNull { t -> t.date?.take(4) }, album)
                LibraryCategory.FILE_TYPE -> album.tracks.mapNotNull { it.fileType?.uppercase() }.distinct().ifEmpty { listOf(UNKNOWN) }.forEach { add(it, album) }
                LibraryCategory.SAMPLING_RATE -> album.tracks.map { rateKey(it) }.distinct().forEach { add(it, album) }
                LibraryCategory.ALBUMS, LibraryCategory.SONGS -> add(album.title, album)
            }
        }
        val byName: Comparator<LibraryGroup> = if (category == LibraryCategory.SAMPLING_RATE) {
            compareBy { rateSortKey(it.key) }
        } else {
            compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.key) }
        }
        // Groups follow the chosen sort through their albums (e.g. most recently added first).
        val primary: Comparator<LibraryGroup> = when (sort) {
            LibrarySort.TITLE, LibrarySort.ARTIST -> byName
            LibrarySort.YEAR -> compareBy { g -> g.albums.maxOfOrNull { yearOf(it) } ?: 0 }
            LibrarySort.DATE_ADDED -> compareBy { g -> g.albums.maxOfOrNull { it.importedAt } ?: 0L }
            LibrarySort.DATE_MODIFIED -> compareBy { g -> g.albums.maxOfOrNull { it.modifiedAt } ?: 0L }
            LibrarySort.DURATION -> compareBy { g -> g.albums.sumOf { it.durationSeconds } }
            LibrarySort.TRACK_COUNT -> compareBy { g -> g.albums.size }
        }
        val albumOrder = albumComparator(sort, descending)
        val groups = map.map { (k, v) -> LibraryGroup(k, v.sortedWith(albumOrder)) }
        return groups.sortedWith((if (descending) primary.reversed() else primary).then(byName))
    }

    private fun yearOf(a: Album): Int = (a.year ?: a.tracks.firstNotNullOfOrNull { t -> t.date?.take(4) })?.toIntOrNull() ?: 0

    private val byTitle: Comparator<Album> = compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) }

    /** Album order for [sort]; ties fall back to the title, always A to Z. */
    fun albumComparator(sort: LibrarySort, descending: Boolean): Comparator<Album> {
        val primary: Comparator<Album> = when (sort) {
            LibrarySort.TITLE -> byTitle
            LibrarySort.ARTIST -> compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { sortKey(it.displayArtist) }.thenBy { yearOf(it) }
            LibrarySort.YEAR -> compareBy { yearOf(it) }
            LibrarySort.DATE_ADDED -> compareBy { it.importedAt }
            LibrarySort.DATE_MODIFIED -> compareBy { it.modifiedAt }
            LibrarySort.DURATION -> compareBy { it.durationSeconds }
            LibrarySort.TRACK_COUNT -> compareBy { it.tracks.size }
        }
        return (if (descending) primary.reversed() else primary).then(byTitle)
    }

    fun sortedAlbums(library: Library, descending: Boolean, sort: LibrarySort = LibrarySort.TITLE): List<Album> =
        library.albums.sortedWith(albumComparator(sort, descending))

    fun sortedTracks(library: Library, descending: Boolean, sort: LibrarySort = LibrarySort.TITLE): List<Track> {
        val pairs = library.albums.flatMap { a -> a.tracks.map { a to it } }
        val title = compareBy<Pair<Album, Track>, String>(String.CASE_INSENSITIVE_ORDER) { sortKey(it.second.title) }
        val inAlbum = compareBy<Pair<Album, Track>>({ it.second.discNumber }, { it.second.trackNumber })
        val primary: Comparator<Pair<Album, Track>> = when (sort) {
            LibrarySort.TITLE -> title
            LibrarySort.ARTIST -> compareBy<Pair<Album, Track>, String>(String.CASE_INSENSITIVE_ORDER) { sortKey(it.second.displayArtist) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }.then(inAlbum)
            LibrarySort.YEAR -> compareBy { (it.second.date?.take(4)?.toIntOrNull() ?: yearOf(it.first)) }
            LibrarySort.DATE_ADDED -> compareBy { parseDateMillis(it.second.importDate).takeIf { d -> d > 0 } ?: it.first.addedAt }
            LibrarySort.DATE_MODIFIED -> compareBy { parseDateMillis(it.second.modifyDate) }
            LibrarySort.DURATION -> compareBy { it.second.durationSeconds }
            // Not meaningful per song: keep album order.
            LibrarySort.TRACK_COUNT -> compareBy<Pair<Album, Track>, String>(String.CASE_INSENSITIVE_ORDER) { it.first.title }.then(inAlbum)
        }
        return pairs.sortedWith((if (descending) primary.reversed() else primary).then(title)).map { it.second }
    }


    fun search(library: Library, query: String): SearchHits {
        val q = query.trim()
        if (q.isEmpty()) return SearchHits(emptyList(), emptyList(), emptyList())
        val albums = library.albums.filter { it.title.contains(q, true) || it.displayArtist.contains(q, true) }
        val tracks = library.tracks.filter { it.title.contains(q, true) }
        val artists = group(library, LibraryCategory.ARTISTS).filter { it.key.contains(q, true) && it.key != UNKNOWN }
        return SearchHits(artists, albums, tracks)
    }

    data class SearchHits(val artists: List<LibraryGroup>, val albums: List<Album>, val tracks: List<Track>)

    const val UNKNOWN = "Unknown"

    private fun splitNames(s: String): List<String> = s.split(';', '/').map { it.trim() }.filter { it.isNotEmpty() }

    /** Ignore a leading "The " / "A " the way most music apps do. */
    fun sortKey(s: String): String {
        val t = s.trim()
        for (p in listOf("the ", "a ")) if (t.length > p.length && t.startsWith(p, ignoreCase = true)) return t.substring(p.length)
        return t
    }

    private fun rateKey(t: Track): String = when {
        t.sampleFrequency <= 0 -> UNKNOWN
        t.bitsPerSample == 1 || t.fileType?.lowercase() in setOf("dsf", "dff") -> "DSD ${formatRate(t.sampleFrequency)}"
        else -> formatRate(t.sampleFrequency)
    }

    private fun rateSortKey(k: String): Double {
        if (k == UNKNOWN) return Double.MAX_VALUE
        val dsd = k.startsWith("DSD")
        val num = Regex("[0-9.]+").find(k)?.value?.toDoubleOrNull() ?: 0.0
        val hz = if (k.contains("MHz")) num * 1_000_000 else num * 1000
        return if (dsd) hz + 1e9 else hz
    }
}

sealed interface SyncProgress {
    data class Searching(val message: String) : SyncProgress
    data class Albums(val done: Int, val total: Int) : SyncProgress
    data class Tracks(val done: Int, val total: Int, val album: String) : SyncProgress
}

/**
 * Imports a media server's music library: locates the "all albums" container, then fetches every
 * album with its tracks. This is how the original app built its local library index.
 */
class LibrarySync(
    private val cd: ContentDirectory,
    private val serverName: String,
    /** The previous import of the same server, so albums keep the date the app first saw them. */
    private val previous: Library? = null,
) {

    suspend fun run(progress: (SyncProgress) -> Unit): Library {
        progress(SyncProgress.Searching("Searching albums…"))
        val albumDir = findAlbumContainer()
        val albumContainers: List<Container> = if (albumDir != null) {
            val all = ArrayList<Container>()
            cd.browseAll(albumDir) { page, done ->
                all += page.containers
                progress(SyncProgress.Albums(done.coerceAtMost(page.totalMatches.coerceAtLeast(done)), page.totalMatches))
            }
            all.filter { it.isAlbum }.ifEmpty { all }
        } else {
            emptyList()
        }
        val albums = if (albumContainers.isNotEmpty()) {
            fetchTracks(albumContainers, progress)
        } else {
            albumsFromTrackSearch(progress)
        }
        val scan = ""
        val now = System.currentTimeMillis()
        val seen = previous?.albums?.associate { it.id to it.addedAt }.orEmpty()
        val dated = albums.filter { it.tracks.isNotEmpty() }.map { a -> a.copy(addedAt = seen[a.id]?.takeIf { it > 0 } ?: now) }
        return Library(cd.serverUdn, serverName, dated, scan, schema = Library.SCHEMA)
    }

    private suspend fun fetchTracks(containers: List<Container>, progress: (SyncProgress) -> Unit): List<Album> = coroutineScope {
        val sem = Semaphore(4)
        var done = 0
        val jobs = containers.map { c ->
            async {
                sem.withPermit {
                    coroutineContext.ensureActive()
                    val tracks = try {
                        cd.browseAll(c.id).items.map { t ->
                            t.copy(
                                album = t.album ?: c.title,
                                albumArtUri = t.albumArtUri ?: c.albumArtUri,
                                parentId = c.id,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    synchronized(this@LibrarySync) {
                        done++
                        progress(SyncProgress.Tracks(done, containers.size, c.title))
                    }
                    Album(
                        id = c.id,
                        title = c.title,
                        artist = c.artist ?: tracks.firstNotNullOfOrNull { it.albumArtist },
                        genre = c.genre ?: tracks.firstNotNullOfOrNull { it.genre },
                        date = c.date ?: tracks.firstNotNullOfOrNull { it.date },
                        artUri = c.albumArtUri ?: tracks.firstNotNullOfOrNull { it.albumArtUri },
                        tracks = tracks.sortedWith(compareBy({ it.discNumber }, { it.trackNumber })),
                    )
                }
            }
        }
        jobs.map { it.await() }
    }

    /** Fallback for servers without an album container: search all tracks and group by album. */
    private suspend fun albumsFromTrackSearch(progress: (SyncProgress) -> Unit): List<Album> {
        val tracks = ArrayList<Track>()
        var start = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = try {
                cd.search(ContentDirectory.ROOT, "upnp:class derivedfrom \"object.item.audioItem\"", start, 200)
            } catch (_: Exception) {
                break
            }
            tracks += page.items
            start += page.items.size
            progress(SyncProgress.Tracks(start, page.totalMatches, ""))
            if (page.items.isEmpty() || start >= page.totalMatches) break
        }
        return tracks.groupBy { (it.album ?: LibraryIndex.UNKNOWN) + "\u0000" + (it.albumArtist ?: "") }.map { (_, ts) ->
            val f = ts.first()
            Album(
                id = "search:" + (f.album ?: "") + ":" + (f.albumArtist ?: ""),
                title = f.album ?: LibraryIndex.UNKNOWN,
                artist = f.albumArtist ?: f.artist,
                genre = f.genre,
                date = f.date,
                artUri = ts.firstNotNullOfOrNull { it.albumArtUri },
                tracks = ts.sortedWith(compareBy({ it.discNumber }, { it.trackNumber })),
            )
        }
    }

    suspend fun findAlbumContainer(): String? {
        // 1. Ask the server directly (Lightning Server and most DLNA servers support Search).
        try {
            val r = cd.search(ContentDirectory.ROOT, "upnp:class derivedfrom \"object.container.album.musicAlbum\"", 0, 1)
            val parent = r.containers.firstOrNull()?.parentId
            if (!parent.isNullOrBlank()) {
                val count = cd.browse(parent, 0, 1).totalMatches
                if (count > 0 && count >= r.totalMatches / 2) return parent
            }
        } catch (_: Exception) {
        }
        // 2. Walk the tree a few levels down, looking for the container with the most albums.
        data class Candidate(val id: String, val total: Int, val title: String)
        val candidates = ArrayList<Candidate>()
        suspend fun walk(id: String, title: String, level: Int) {
            if (level > 3) return
            val page = try { cd.browse(id, 0, 30) } catch (_: Exception) { return }
            if (page.containers.any { it.isAlbum }) {
                candidates += Candidate(id, page.totalMatches, title)
                return
            }
            for (c in page.containers.take(12)) walk(c.id, c.title, level + 1)
        }
        walk(ContentDirectory.ROOT, "", 0)
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.total }.thenBy { if (it.title.contains("album", true)) 1 else 0 }
        )?.id
    }
}
