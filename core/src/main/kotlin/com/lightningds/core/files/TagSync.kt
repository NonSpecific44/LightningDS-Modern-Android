package com.lightningds.core.files

import com.lightningds.core.library.Album
import com.lightningds.core.library.AlbumEdit
import com.lightningds.core.library.LibraryEdits
import com.lightningds.core.library.TrackEdit
import com.lightningds.core.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.coroutineContext

/** Tag values to write; null fields are left as they are in the file. */
data class TagFields(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
) {
    val isEmpty: Boolean get() = this == TagFields()
}

data class Cover(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is Cover && other.mimeType == mimeType && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode() * 31 + mimeType.hashCode()
}

object TagWriter {
    /** Formats jaudiotagger can write. (DFF has no tag support.) */
    val SUPPORTED = setOf("flac", "mp3", "m4a", "mp4", "m4b", "aac", "alac", "ogg", "oga", "opus", "wav", "aif", "aiff", "aifc", "dsf", "wma")
    val AUDIO = SUPPORTED + setOf("dff", "ape", "wv")

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.WARNING
        // Avoid javax.imageio (missing on Android) when handling artwork.
        TagOptionSingleton.getInstance().apply {
            isAndroid = true
            // WAV: keep the INFO and ID3 chunks in sync (ID3 carries artwork; servers read either).
            wavOptions = org.jaudiotagger.audio.wav.WavOptions.READ_ID3_ONLY_AND_SYNC
            wavSaveOptions = org.jaudiotagger.audio.wav.WavSaveOptions.SAVE_BOTH_AND_SYNC
        }
    }

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in SUPPORTED
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO

    /** Writes [fields] (and [cover]); returns a note if the artwork couldn't be embedded. */
    fun write(file: File, fields: TagFields, cover: Cover?): String? {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String?) {
            if (value != null) {
                if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value)
            }
        }
        set(FieldKey.TITLE, fields.title)
        set(FieldKey.ARTIST, fields.artist)
        set(FieldKey.ALBUM, fields.album)
        set(FieldKey.ALBUM_ARTIST, fields.albumArtist)
        set(FieldKey.YEAR, fields.year)
        set(FieldKey.GENRE, fields.genre)
        set(FieldKey.COMPOSER, fields.composer)
        set(FieldKey.TRACK, fields.trackNumber?.takeIf { it > 0 }?.toString())
        set(FieldKey.DISC_NO, fields.discNumber?.takeIf { it > 0 }?.toString())
        var note: String? = null
        if (cover != null) {
            try {
                tag.deleteArtworkField()
                if (tag is org.jaudiotagger.tag.flac.FlacTag) {
                    // The generic path decodes the image (javax.imageio), which Android lacks.
                    tag.setField(tag.createArtworkField(cover.bytes, 3, cover.mimeType, "", 0, 0, 0, 0))
                } else {
                    val art = ArtworkFactory.getNew().apply {
                        binaryData = cover.bytes
                        mimeType = cover.mimeType
                        pictureType = 3 // front cover
                    }
                    tag.setField(art)
                }
            } catch (e: Exception) {
                note = "Cover not embedded (${e.message ?: e.javaClass.simpleName})"
            }
        }
        audio.commit()
        return note
    }

    data class ReadTags(val title: String?, val artist: String?, val album: String?, val albumArtist: String?, val year: String?, val track: String?, val hasArtwork: Boolean)

    fun read(file: File): ReadTags {
        val tag = AudioFileIO.read(file).tag
        fun get(k: FieldKey) = tag?.getFirst(k)?.ifBlank { null }
        return ReadTags(get(FieldKey.TITLE), get(FieldKey.ARTIST), get(FieldKey.ALBUM), get(FieldKey.ALBUM_ARTIST), get(FieldKey.YEAR), get(FieldKey.TRACK), tag?.firstArtwork != null)
    }
}

/** Pairs an album's tracks with the audio files in a folder. */
object FileMatcher {
    data class Match(val track: Track, val file: FileEntry?)

    private val leadingNumber = Regex("^(?:(\\d)[-._ ])?(\\d{1,3})\\b")

    fun match(tracks: List<Track>, files: List<FileEntry>): List<Match> {
        val audio = files.filter { !it.isDirectory && TagWriter.isAudio(it.name) }.sortedBy { it.name.lowercase() }
        val used = HashSet<String>()
        val result = LinkedHashMap<String, FileEntry?>()
        // 1. Exact file size (the server reports it): reliable whenever sizes are unique.
        val bySize = audio.groupBy { it.size }
        for (t in tracks) {
            val candidates = bySize[t.size]?.filter { it.path !in used }
            if (t.size > 0 && candidates?.size == 1) {
                result[t.id] = candidates[0]
                used += candidates[0].path
            }
        }
        // 2. Track number at the start of the file name ("01 Title.flac", "1-01 Title.flac").
        val numbered = audio.filter { it.path !in used }.mapNotNull { f ->
            leadingNumber.find(f.name)?.let { m -> Triple(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toInt(), f) }
        }
        for (t in tracks) {
            if (t.id in result || t.trackNumber <= 0) continue
            val hit = numbered.filter { it.third.path !in used && it.second == t.trackNumber && (it.first == 0 || it.first == t.discNumber.coerceAtLeast(1)) }
            if (hit.size == 1) {
                result[t.id] = hit[0].third
                used += hit[0].third.path
            }
        }
        // 3. Whatever is left, in order, when the counts line up.
        val restTracks = tracks.filter { it.id !in result }
        val restFiles = audio.filter { it.path !in used }
        if (restTracks.isNotEmpty() && restTracks.size == restFiles.size) {
            restTracks.zip(restFiles).forEach { (t, f) -> result[t.id] = f }
        }
        return tracks.map { Match(it, result[it.id]) }
    }

    /**
     * Looks for the folder holding [album]: first folders whose name mentions the album title,
     * then any folder containing a file with the size of one of its tracks.
     */
    suspend fun findFolder(store: FileStore, album: Album, root: String = "", maxFolders: Int = 1500): String? {
        val sizes = album.tracks.map { it.size }.filter { it > 0 }.toSet()
        val title = album.title.lowercase().filter { it.isLetterOrDigit() }
        val queue = ArrayDeque<String>().apply { add(root) }
        var visited = 0
        var fallback: String? = null
        while (queue.isNotEmpty() && visited < maxFolders) {
            coroutineContext.ensureActive()
            val dir = queue.removeFirst()
            visited++
            val entries = runCatching { store.list(dir) }.getOrNull() ?: continue
            val files = entries.filter { !it.isDirectory }
            val hits = files.count { it.size in sizes }
            if (hits > 0 && (hits >= minOf(2, sizes.size))) return dir
            if (hits > 0 && fallback == null) fallback = dir
            val subdirs = entries.filter { it.isDirectory }
            // Folders named like the album are searched first.
            val (likely, other) = subdirs.partition { title.isNotEmpty() && it.name.lowercase().filter(Char::isLetterOrDigit).contains(title) }
            likely.forEach { queue.addFirst(it.path) }
            other.forEach { queue.addLast(it.path) }
        }
        return fallback
    }
}

/** What a write did for one track. */
data class WriteResult(val track: Track, val file: FileEntry?, val error: String?, val note: String? = null) {
    val ok: Boolean get() = file != null && error == null
}

/** Writes the user's edits for an album into its files. */
class TagSync(private val store: FileStore, private val workDir: File) {

    companion object {
        /** Tag values for [track] from the user's edits. Only edited fields are written. */
        fun fieldsFor(track: Track, albumEdit: AlbumEdit?, trackEdit: TrackEdit?): TagFields = TagFields(
            title = trackEdit?.title,
            artist = trackEdit?.artist ?: albumEdit?.artist?.takeIf { track.artist == null },
            album = albumEdit?.title,
            albumArtist = albumEdit?.artist,
            year = albumEdit?.date,
            genre = albumEdit?.genre,
            composer = trackEdit?.composer,
            trackNumber = trackEdit?.trackNumber,
            discNumber = trackEdit?.discNumber,
        )
    }

    /**
     * Writes edits from [edits] for [matches] (tracks as the server reported them).
     * [cover] is embedded when given; [folderCover] is saved as folder.jpg (or .png) in [folder].
     */
    suspend fun write(
        folder: String,
        matches: List<FileMatcher.Match>,
        albumId: String,
        edits: LibraryEdits,
        cover: Cover?,
        folderCover: Cover?,
        progress: (done: Int, total: Int, current: String) -> Unit,
    ): List<WriteResult> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val albumEdit = edits.albums[albumId]
        val results = ArrayList<WriteResult>()
        val notes = HashMap<String, String?>()
        val todo = matches.map { m -> m to fieldsFor(m.track, albumEdit, edits.tracks[m.track.id]) }
        todo.forEachIndexed { i, (m, fields) ->
            coroutineContext.ensureActive()
            val f = m.file
            progress(i, todo.size, f?.name ?: m.track.title)
            val error = when {
                f == null -> "No matching file"
                fields.isEmpty && cover == null -> null
                !TagWriter.isSupported(f.name) -> "Tags can't be written to .${f.name.substringAfterLast('.')} files"
                else -> try {
                    val local = File(workDir, "edit_${System.nanoTime()}.${f.name.substringAfterLast('.')}")
                    try {
                        store.download(f.path, local)
                        if (local.length() != f.size && f.size > 0) error("Download of ${f.name} was incomplete")
                        val note = TagWriter.write(local, fields, cover)
                        store.replace(f.path, local)
                        notes[m.track.id] = note
                    } finally {
                        local.delete()
                    }
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            results += WriteResult(m.track, f, error, notes[m.track.id])
        }
        if (folderCover != null) {
            val name = if (folderCover.mimeType.contains("png")) "folder.png" else "folder.jpg"
            runCatching { store.write(joinPath(folder, name), folderCover.bytes) }
        }
        progress(todo.size, todo.size, "")
        results
    }
}
