package com.lightningds.core

import com.lightningds.core.library.Album
import com.lightningds.core.library.AlbumEdit
import com.lightningds.core.library.Library
import com.lightningds.core.library.LibraryEdits
import com.lightningds.core.library.MetadataEditor
import com.lightningds.core.library.TrackEdit
import com.lightningds.core.media.Track
import com.lightningds.core.metadata.MusicBrainz
import com.lightningds.core.metadata.ReleaseMatcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTest {
    private val album = Album(
        id = "alb1", title = "Unknown", artist = null, artUri = null,
        tracks = listOf(
            Track(id = "t1", parentId = "alb1", title = "Track01", trackNumber = 1, albumArtUri = "http://server/art.jpg"),
            Track(id = "t2", parentId = "alb1", title = "Track02", trackNumber = 2),
        ),
    )

    @Test
    fun editsOverrideServerData() {
        val edits = LibraryEdits(
            albums = mapOf("alb1" to AlbumEdit(title = "Abbey Road", artist = "The Beatles", artUri = "https://img/cover.jpg")),
            tracks = mapOf("t2" to TrackEdit(title = "Something", trackNumber = 2)),
        )
        val lib = MetadataEditor.apply(Library("uuid:s", "S", listOf(album)), edits)
        val a = lib.albums.single()
        assertEquals("Abbey Road", a.title)
        assertEquals("The Beatles", a.displayArtist)
        assertEquals("https://img/cover.jpg", a.artUri)
        assertEquals("Abbey Road", a.tracks[0].album)
        assertEquals("https://img/cover.jpg", a.tracks[0].albumArtUri)
        assertEquals("Something", a.tracks[1].title)
        assertEquals("The Beatles", a.tracks[1].artist)
    }

    @Test
    fun localCoverStaysInAppOnly() {
        val edits = LibraryEdits(albums = mapOf("alb1" to AlbumEdit(artUri = "file:///data/cover.jpg")))
        val a = MetadataEditor.apply(album, edits)
        assertEquals("file:///data/cover.jpg", a.artUri)
        // The player can't fetch a file on the phone, so tracks keep the server's artwork.
        assertEquals("http://server/art.jpg", a.tracks[0].albumArtUri)
    }

    @Test
    fun needsInfo() {
        assertTrue(MetadataEditor.needsInfo(album))
        assertFalse(MetadataEditor.needsInfo(album.copy(title = "X", artist = "Y", artUri = "http://a")))
    }

    @Test
    fun queryBuilding() {
        assertEquals("release:\"Abbey Road\" AND artist:\"The \\\"Fab\\\" Four\"", MusicBrainz.buildQuery("release" to "Abbey Road", "artist" to "The \"Fab\" Four"))
        assertEquals("release:\"X\"", MusicBrainz.buildQuery("release" to "X", "artist" to "Unknown"))
    }

    @Test
    fun parseSearchAndReleaseAndMatch() {
        val search = MusicBrainz.parseReleaseSearch(Json.parseToJsonElement(SEARCH))
        assertEquals(1, search.size)
        val c = search[0]
        assertEquals("b84ee12a-09ef-421b-82de-0441a926375b", c.id)
        assertEquals("The Beatles", c.artist)
        assertEquals("1969", c.year)
        assertEquals(17, c.trackCount)
        assertEquals("CD", c.format)

        val rel = MusicBrainz.parseRelease(Json.parseToJsonElement(RELEASE))
        assertEquals("Abbey Road", rel.title)
        assertEquals("Rock", rel.genre)
        assertEquals(2, rel.tracks.size)
        assertEquals("Something", rel.tracks[1].title)
        assertEquals("George Harrison", rel.tracks[1].artist)
        assertEquals(183, rel.tracks[1].lengthSeconds)

        val m = ReleaseMatcher.match(album, rel, "https://coverartarchive.org/release/x/front-500")
        assertEquals("Abbey Road", m.album.title)
        assertEquals("The Beatles", m.album.artist)
        assertEquals("Come Together", m.tracks["t1"]?.title)
        assertNull(m.tracks["t1"]?.trackNumber)
        assertEquals("Something", m.tracks["t2"]?.title)
    }

    private val SEARCH = """{"created":"x","count":1,"offset":0,"releases":[{"id":"b84ee12a-09ef-421b-82de-0441a926375b","score":100,"title":"Abbey Road","status":"Official","date":"1969-09-26","country":"GB","track-count":17,
        "artist-credit":[{"name":"The Beatles","artist":{"id":"b10bbbfc","name":"The Beatles"}}],
        "release-group":{"id":"9162580e","primary-type":"Album"},"media":[{"format":"CD","disc-count":1,"track-count":17}]}]}"""

    private val RELEASE = """{"id":"b84ee12a","title":"Abbey Road","date":"1969-09-26",
        "artist-credit":[{"name":"The Beatles","joinphrase":"","artist":{"name":"The Beatles"}}],
        "genres":[{"name":"rock","count":9},{"name":"pop","count":3}],
        "release-group":{"id":"9162580e"},
        "media":[{"position":1,"format":"CD","tracks":[
          {"position":1,"number":"1","title":"Come Together","length":259946,"recording":{"title":"Come Together"}},
          {"position":2,"number":"2","title":"Something","length":182600,"artist-credit":[{"name":"George Harrison","joinphrase":""}],"recording":{"title":"Something"}}
        ]}]}"""
}
