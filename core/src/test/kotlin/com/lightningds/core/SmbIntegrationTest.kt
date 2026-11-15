package com.lightningds.core

import com.lightningds.core.files.FileMatcher
import com.lightningds.core.files.SmbFileStore
import com.lightningds.core.files.TagSync
import com.lightningds.core.files.TagWriter
import com.lightningds.core.library.Album
import com.lightningds.core.library.AlbumEdit
import com.lightningds.core.library.LibraryEdits
import com.lightningds.core.media.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Talks to a real SMB server. Opt-in: set SMB_TEST_HOST, SMB_TEST_SHARE, SMB_TEST_USER and
 * SMB_TEST_PASSWORD, with "Music/Artist/Mystery Album" on the share holding the files from
 * src/test/resources/audio. Example server: impacket's smbserver.py -smb2support.
 */
class SmbIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun writesTagsOverSmb() = runBlocking {
        val host = System.getenv("SMB_TEST_HOST")
        assumeTrue("SMB_TEST_HOST not set", !host.isNullOrBlank())
        val share = System.getenv("SMB_TEST_SHARE") ?: "MUSIC"
        val user = System.getenv("SMB_TEST_USER").orEmpty()
        val pass = System.getenv("SMB_TEST_PASSWORD").orEmpty()

        val store = SmbFileStore(host, share, user, pass)
        store.test()
        val sizes = store.list("Music/Artist/Mystery Album").filter { TagWriter.isAudio(it.name) }.sortedBy { it.name }
        val album = Album("alb", "Mystery Album", tracks = sizes.mapIndexed { i, f -> Track(id = "t$i", parentId = "alb", title = "x", trackNumber = i + 1, size = f.size) })

        val folder = FileMatcher.findFolder(store, album)
        assertEquals("Music/Artist/Mystery Album", folder)
        val matches = FileMatcher.match(album.tracks, store.list(folder!!))
        val edits = LibraryEdits(albums = mapOf("alb" to AlbumEdit(title = "Over SMB", artist = "Network Artist")))
        val results = TagSync(store, tmp.newFolder()).write(folder, matches, "alb", edits, null, folderCover = null) { _, _, _ -> }
        assertTrue(results.joinToString { "${it.file?.name}: ${it.error}" }, results.all { it.ok })

        val check = File(tmp.root, "check.flac")
        store.download(matches.first { it.file!!.name.endsWith(".flac") }.file!!.path, check)
        assertEquals("Over SMB", TagWriter.read(check).album)
        assertTrue(store.list(folder).none { it.name.endsWith(".ldstmp") })
    }
}
