package com.lightningds.core

import com.lightningds.core.files.Cover
import com.lightningds.core.files.FileEntry
import com.lightningds.core.files.FileMatcher
import com.lightningds.core.files.LocalFileStore
import com.lightningds.core.files.TagSync
import com.lightningds.core.files.TagWriter
import com.lightningds.core.library.Album
import com.lightningds.core.library.AlbumEdit
import com.lightningds.core.library.LibraryEdits
import com.lightningds.core.library.TrackEdit
import com.lightningds.core.media.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TagSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val names = listOf("01 First.flac", "02 Second.mp3", "03 Third.m4a", "04 Fourth.wav")

    /** A share with the album two folders deep, next to an unrelated folder. */
    private fun share(): File {
        val root = tmp.newFolder("share")
        File(root, "Other/Stuff").mkdirs()
        File(root, "Other/Stuff/readme.txt").writeText("x")
        val album = File(root, "Music/Unknown Artist/Mystery Album").apply { mkdirs() }
        for (n in names) javaClass.getResourceAsStream("/audio/$n")!!.use { i -> File(album, n).outputStream().use { i.copyTo(it) } }
        return root
    }

    private fun albumFor(root: File): Album {
        val dir = File(root, "Music/Unknown Artist/Mystery Album")
        return Album(
            id = "alb", title = "Mystery Album",
            tracks = names.mapIndexed { i, n ->
                Track(id = "t$i", parentId = "alb", title = "Track ${i + 1}", trackNumber = i + 1, size = File(dir, n).length())
            },
        )
    }

    @Test
    fun matchesBySizeThenNumberThenOrder() {
        val tracks = listOf(
            Track(id = "a", title = "A", size = 100, trackNumber = 2),
            Track(id = "b", title = "B", size = 0, trackNumber = 1),
            Track(id = "c", title = "C", size = 0, trackNumber = 0),
        )
        val files = listOf(
            FileEntry("01 x.flac", "d/01 x.flac", false, 50),
            FileEntry("02 y.flac", "d/02 y.flac", false, 100),
            FileEntry("zz.flac", "d/zz.flac", false, 70),
            FileEntry("cover.jpg", "d/cover.jpg", false, 100),
        )
        val m = FileMatcher.match(tracks, files).associate { it.track.id to it.file?.name }
        assertEquals("02 y.flac", m["a"]) // size
        assertEquals("01 x.flac", m["b"]) // leading number
        assertEquals("zz.flac", m["c"]) // remaining, in order
    }

    @Test
    fun findsFolderAndWritesTags() = runBlocking {
        val root = share()
        val store = LocalFileStore(root)
        val album = albumFor(root)
        val folder = FileMatcher.findFolder(store, album)
        assertEquals("Music/Unknown Artist/Mystery Album", folder)

        val matches = FileMatcher.match(album.tracks, store.list(folder!!))
        assertTrue(matches.all { it.file != null })

        val edits = LibraryEdits(
            albums = mapOf("alb" to AlbumEdit(title = "Real Album", artist = "Real Artist", date = "2001", genre = "Jazz")),
            tracks = album.tracks.associate { it.id to TrackEdit(title = "Song " + it.trackNumber) },
        )
        val cover = Cover(javaClass.getResourceAsStream("/audio/cover.jpg")!!.readBytes(), "image/jpeg")
        val results = TagSync(store, tmp.newFolder("work")).write(folder, matches, "alb", edits, cover, folderCover = cover) { _, _, _ -> }
        assertTrue(results.joinToString { "${it.file?.name}: ${it.error}" }, results.all { it.ok })

        val dir = File(root, folder)
        for ((i, n) in names.withIndex()) {
            val tags = TagWriter.read(File(dir, n))
            assertEquals(n, "Song ${i + 1}", tags.title)
            assertEquals(n, "Real Album", tags.album)
            assertEquals(n, "Real Artist", tags.albumArtist)
            assertTrue("$n artwork", tags.hasArtwork)
        }
        assertTrue(File(dir, "folder.jpg").exists())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".ldstmp") })
    }

    @Test
    fun onlyEditedFieldsAreWritten() {
        val f = TagSync.fieldsFor(Track(id = "x", artist = "Someone"), AlbumEdit(title = "T", artist = "AA"), null)
        assertEquals("T", f.album)
        assertEquals("AA", f.albumArtist)
        assertNull(f.artist) // the track already had an artist
        assertNull(f.title)
        assertTrue(TagSync.fieldsFor(Track(id = "y"), null, null).isEmpty)
    }
}
