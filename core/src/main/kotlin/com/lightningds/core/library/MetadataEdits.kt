package com.lightningds.core.library

import com.lightningds.core.media.Track
import kotlinx.serialization.Serializable

/**
 * User corrections for an album. Media servers (DLNA and Lightning Server) don't accept metadata
 * writes, so edits are kept by the app and layered over the imported library.
 * A null field means "keep what the server says".
 */
@Serializable
data class AlbumEdit(
    val title: String? = null,
    val artist: String? = null,
    val date: String? = null,
    val genre: String? = null,
    /** http(s) URL, or a file:// URL of an image chosen on this device. */
    val artUri: String? = null,
    /** MusicBrainz release the edit was filled from, if any. */
    val musicBrainzId: String? = null,
)

@Serializable
data class TrackEdit(
    val title: String? = null,
    val artist: String? = null,
    val composer: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
)

/** All edits for one media server. Keys are the server's object ids. */
@Serializable
data class LibraryEdits(
    val albums: Map<String, AlbumEdit> = emptyMap(),
    val tracks: Map<String, TrackEdit> = emptyMap(),
) {
    fun isEmpty(): Boolean = albums.isEmpty() && tracks.isEmpty()
}

fun String?.isRemoteUrl(): Boolean = this != null && (startsWith("http://", true) || startsWith("https://", true))

object MetadataEditor {
    fun apply(library: Library, edits: LibraryEdits?): Library {
        if (edits == null || edits.isEmpty()) return library
        return library.copy(albums = library.albums.map { apply(it, edits) })
    }

    fun apply(album: Album, edits: LibraryEdits): Album {
        val e = edits.albums[album.id]
        val tracks = album.tracks.map { apply(it, edits, e) }
        if (e == null) return if (tracks == album.tracks) album else album.copy(tracks = sortTracks(tracks))
        return album.copy(
            title = e.title ?: album.title,
            artist = e.artist ?: album.artist,
            date = e.date ?: album.date,
            genre = e.genre ?: album.genre,
            artUri = e.artUri ?: album.artUri,
            tracks = sortTracks(tracks),
        )
    }

    /** Applies track (and, if given, album) edits to a track. */
    fun apply(track: Track, edits: LibraryEdits, albumEdit: AlbumEdit? = edits.albums[track.parentId]): Track {
        val t = edits.tracks[track.id]
        var out = track
        if (albumEdit != null) {
            out = out.copy(
                album = albumEdit.title ?: out.album,
                albumArtist = albumEdit.artist ?: out.albumArtist,
                artist = out.artist ?: albumEdit.artist,
                date = albumEdit.date ?: out.date,
                genre = albumEdit.genre ?: out.genre,
                // The player fetches artwork itself, so only remote images replace the server's.
                albumArtUri = albumEdit.artUri?.takeIf { it.isRemoteUrl() } ?: out.albumArtUri,
            )
        }
        if (t != null) {
            out = out.copy(
                title = t.title ?: out.title,
                artist = t.artist ?: out.artist,
                composer = t.composer ?: out.composer,
                trackNumber = t.trackNumber ?: out.trackNumber,
                discNumber = t.discNumber ?: out.discNumber,
            )
        }
        return out
    }

    private fun sortTracks(tracks: List<Track>) = tracks.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))

    /** Albums that look like they're missing information (title, artist or cover art). */
    fun needsInfo(album: Album): Boolean =
        album.artUri.isNullOrBlank() || album.title.isBlank() || album.title.equals(LibraryIndex.UNKNOWN, true) ||
            album.displayArtist.isBlank() || album.displayArtist.equals(LibraryIndex.UNKNOWN, true) ||
            album.tracks.any { it.title.isBlank() }
}
