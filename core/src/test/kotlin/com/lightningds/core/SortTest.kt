package com.lightningds.core

import com.lightningds.core.library.Album
import com.lightningds.core.library.Library
import com.lightningds.core.library.LibraryCategory
import com.lightningds.core.library.LibraryIndex
import com.lightningds.core.library.LibrarySort
import com.lightningds.core.library.parseDateMillis
import com.lightningds.core.media.Didl
import com.lightningds.core.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortTest {
    private fun album(id: String, title: String, artist: String, imported: String?, addedAt: Long = 0, date: String? = null, tracks: Int = 1, secs: Int = 100) =
        Album(id, title, artist, date = date, addedAt = addedAt, tracks = List(tracks) { i ->
            Track(id = "$id-$i", parentId = id, title = "$title $i", artist = artist, importDate = imported, durationSeconds = secs, trackNumber = i + 1)
        })

    private val lib = Library("uuid:s", "S", listOf(
        album("a", "Alpha", "Zed", "2015-06-01 10:00:00", date = "1999", tracks = 3, secs = 300),
        album("b", "Beta", "Ann", "2024-01-15T08:30:00", date = "2010", tracks = 1, secs = 100),
        album("c", "Gamma", "Bob", null, addedAt = parseDateMillis("2020-05-05"), date = "1985", tracks = 2, secs = 200),
    ))

    @Test
    fun dateParsing() {
        assertEquals(1_700_000_000_000L, parseDateMillis("1700000000"))
        assertEquals(1_700_000_000_000L, parseDateMillis("1700000000000"))
        assertTrue(parseDateMillis("2015-06-01 10:00:00") > 0)
        assertTrue(parseDateMillis("2015/06/01") > 0)
        assertTrue(parseDateMillis("2024-01-15T08:30:00Z") > 0)
        assertEquals(0L, parseDateMillis("not a date"))
        assertEquals(parseDateMillis("2024-01-15T08:30:00Z"), parseDateMillis("2024-01-15T03:30:00-05:00"))
        assertEquals(parseDateMillis("2024-01-15T08:30:00Z"), parseDateMillis("2024-01-15T09:30:00+01:00"))
        assertEquals(parseDateMillis("2024-01-15T08:30:00Z"), parseDateMillis("2024-01-15 08:30:00"))
        assertEquals(parseDateMillis("2024-01-15T00:00:00"), parseDateMillis("2024-01-15"))
    }

    /** Date sorts on a big library must stay quick: they run whenever the library screen opens. */
    @Test
    fun dateSortsOnLargeLibraryAreFast() {
        val big = Library("uuid:big", "Big", List(6000) { a ->
            Album("al$a", "Album $a", "Artist ${a % 400}", tracks = List(12) { t ->
                Track(id = "al$a-$t", title = "Song $t", importDate = "20%02d-%02d-%02dT%02d:%02d:00".format(a % 25, a % 12 + 1, t + 1, a % 24, t), modifyDate = "2023-01-0${t % 9 + 1} 10:00:00")
            })
        })
        val start = System.nanoTime()
        LibraryIndex.sortedAlbums(big, true, LibrarySort.DATE_ADDED)
        LibraryIndex.sortedAlbums(big, true, LibrarySort.DATE_MODIFIED)
        LibraryIndex.sortedTracks(big, true, LibrarySort.DATE_ADDED)
        LibraryIndex.group(big, LibraryCategory.ARTISTS, true, LibrarySort.DATE_ADDED)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("date sorts on 6000 albums / 72000 tracks: ${ms}ms")
        assertTrue("took ${ms}ms", ms < 5_000)
    }

    @Test
    fun albumSorts() {
        fun ids(sort: LibrarySort, desc: Boolean) = LibraryIndex.sortedAlbums(lib, desc, sort).map { it.id }
        assertEquals(listOf("a", "b", "c"), ids(LibrarySort.TITLE, false))
        assertEquals(listOf("b", "c", "a"), ids(LibrarySort.ARTIST, false))
        // Newest first: server import date, falling back to when the app first saw the album.
        assertEquals(listOf("b", "c", "a"), ids(LibrarySort.DATE_ADDED, true))
        assertEquals(listOf("b", "a", "c"), ids(LibrarySort.YEAR, true))
        assertEquals(listOf("a", "c", "b"), ids(LibrarySort.DURATION, true))
        assertEquals(listOf("a", "c", "b"), ids(LibrarySort.TRACK_COUNT, true))
    }

    @Test
    fun groupsAndSongsFollowSort() {
        val g = LibraryIndex.group(lib, LibraryCategory.ARTISTS, descending = true, sort = LibrarySort.DATE_ADDED)
        assertEquals(listOf("Ann", "Bob", "Zed"), g.map { it.key })
        val songs = LibraryIndex.sortedTracks(lib, true, LibrarySort.DATE_ADDED)
        assertEquals("b-0", songs.first().id)
    }

    @Test
    fun lightningImportDateParsed() {
        val t = Didl.parseFirstItem(
            "<DIDL-Lite><item id=\"1\" parentID=\"a\"><dc:title>x</dc:title><res>http://x</res>" +
                "<lightning importDate=\"2016-02-03 04:05:06\" modifyDate=\"2016-03-01 00:00:00\" fileType=\"flac\"/></item></DIDL-Lite>"
        )!!
        assertEquals("2016-02-03 04:05:06", t.importDate)
        assertEquals("2016-03-01 00:00:00", t.modifyDate)
    }
}

class VersionsTest {
    @org.junit.Test
    fun compares() {
        val v = com.lightningds.core.update.Versions
        org.junit.Assert.assertTrue(v.isNewer("v2.1.2", "2.1.1"))
        org.junit.Assert.assertTrue(v.isNewer("v2.1.10", "2.1.9"))
        org.junit.Assert.assertTrue(v.isNewer("v3.0", "2.9.9"))
        org.junit.Assert.assertFalse(v.isNewer("v2.1.1", "2.1.1"))
        org.junit.Assert.assertFalse(v.isNewer("v2.1.0", "2.1.1"))
        org.junit.Assert.assertFalse(v.isNewer("2.1.1-beta", "2.1.1"))
    }
}
