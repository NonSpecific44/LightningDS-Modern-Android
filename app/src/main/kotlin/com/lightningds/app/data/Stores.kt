package com.lightningds.app.data

import com.lightningds.core.library.Album
import com.lightningds.core.library.Library
import com.lightningds.core.media.Track
import com.lightningds.core.radio.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/** A small JSON-file backed value with an observable copy in memory. */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _value = MutableStateFlow(load() ?: default)
    val value: StateFlow<T> = _value.asStateFlow()

    private fun load(): T? = try {
        if (file.exists()) storeJson.decodeFromString(serializer, file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun update(transform: (T) -> T) {
        val next = transform(_value.value)
        _value.value = next
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(storeJson.encodeToString(serializer, next))
                tmp.renameTo(file)
            }
        }
    }
}

@Serializable
data class LocalPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<Track> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val durationSeconds: Int get() = tracks.sumOf { it.durationSeconds }
}

class PlaylistStore(dir: File, scope: CoroutineScope) {
    private val store = JsonFileStore(File(dir, "playlists.json"), ListSerializer(LocalPlaylist.serializer()), emptyList(), scope)
    val playlists: StateFlow<List<LocalPlaylist>> = store.value

    fun create(name: String, tracks: List<Track> = emptyList()): LocalPlaylist {
        val p = LocalPlaylist(name = name.trim(), tracks = tracks)
        store.update { it + p }
        return p
    }

    fun rename(id: String, name: String) = store.update { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    fun delete(id: String) = store.update { l -> l.filterNot { it.id == id } }
    fun addTracks(id: String, tracks: List<Track>) = store.update { l -> l.map { if (it.id == id) it.copy(tracks = it.tracks + tracks) else it } }
    fun removeTrack(id: String, index: Int) = store.update { l ->
        l.map { if (it.id == id) it.copy(tracks = it.tracks.filterIndexed { i, _ -> i != index }) else it }
    }

    fun moveTrack(id: String, from: Int, to: Int) = store.update { l ->
        l.map { p ->
            if (p.id != id || from !in p.tracks.indices || to !in p.tracks.indices) p
            else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
        }
    }
}

/** Albums the user starred ("Collection" in the original app). */
class CollectionStore(dir: File, scope: CoroutineScope) {
    private val store = JsonFileStore(File(dir, "collection.json"), ListSerializer(Album.serializer()), emptyList(), scope)
    val albums: StateFlow<List<Album>> = store.value

    fun contains(album: Album) = albums.value.any { it.id == album.id && it.title == album.title }
    fun toggle(album: Album) = store.update { l -> if (l.any { it.id == album.id && it.title == album.title }) l.filterNot { it.id == album.id && it.title == album.title } else l + album }
}

class RadioStore(dir: File, scope: CoroutineScope) {
    private val favStore = JsonFileStore(File(dir, "radio_favorites.json"), ListSerializer(RadioStation.serializer()), emptyList(), scope)
    private val recentStore = JsonFileStore(File(dir, "radio_recent.json"), ListSerializer(RadioStation.serializer()), emptyList(), scope)
    val favorites: StateFlow<List<RadioStation>> = favStore.value
    val recent: StateFlow<List<RadioStation>> = recentStore.value

    fun isFavorite(s: RadioStation) = favorites.value.any { it.id == s.id }
    fun toggleFavorite(s: RadioStation) = favStore.update { l -> if (l.any { it.id == s.id }) l.filterNot { it.id == s.id } else listOf(s) + l }
    fun played(s: RadioStation) = recentStore.update { l -> (listOf(s) + l.filterNot { it.id == s.id }).take(30) }
}

class SearchHistoryStore(dir: File, scope: CoroutineScope) {
    private val store = JsonFileStore(File(dir, "search_history.json"), ListSerializer(String.serializer()), emptyList(), scope)
    val history: StateFlow<List<String>> = store.value

    fun add(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        store.update { l -> (listOf(t) + l.filterNot { it.equals(t, true) }).take(20) }
    }

    fun clear() = store.update { emptyList() }
}

/** Caches imported libraries on disk, one file per server. */
class LibraryCache(private val dir: File) {
    private fun fileFor(udn: String): File {
        val hash = MessageDigest.getInstance("MD5").digest(udn.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "library_$hash.json")
    }

    suspend fun load(udn: String): Library? = withContext(Dispatchers.IO) {
        val f = fileFor(udn)
        if (!f.exists()) null else runCatching { storeJson.decodeFromString(Library.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun save(library: Library) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val f = fileFor(library.serverUdn)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(storeJson.encodeToString(Library.serializer(), library))
        tmp.renameTo(f)
    }

    suspend fun clear(udn: String?) = withContext(Dispatchers.IO) {
        if (udn == null) dir.listFiles()?.filter { it.name.startsWith("library_") }?.forEach { it.delete() }
        else fileFor(udn).delete()
    }

    fun sizeBytes(): Long = dir.listFiles()?.filter { it.name.startsWith("library_") }?.sumOf { it.length() } ?: 0L
}

/** Metadata corrections, per media server (servers don't accept writes, so they live here). */
class MetadataStore(dir: File, scope: CoroutineScope) {
    private val store = JsonFileStore(
        File(dir, "metadata_edits.json"),
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), com.lightningds.core.library.LibraryEdits.serializer()),
        emptyMap(),
        scope,
    )
    val edits: StateFlow<Map<String, com.lightningds.core.library.LibraryEdits>> = store.value

    fun forServer(udn: String): com.lightningds.core.library.LibraryEdits = edits.value[udn] ?: com.lightningds.core.library.LibraryEdits()

    /** Replaces the album edit (null removes it) and merges track edits (null value removes one). */
    fun update(
        udn: String,
        albumId: String,
        album: com.lightningds.core.library.AlbumEdit?,
        tracks: Map<String, com.lightningds.core.library.TrackEdit?>,
    ) = store.update { all ->
        val cur = all[udn] ?: com.lightningds.core.library.LibraryEdits()
        val albums = if (album == null || album == com.lightningds.core.library.AlbumEdit()) cur.albums - albumId else cur.albums + (albumId to album)
        val trackMap = cur.tracks.toMutableMap()
        for ((id, e) in tracks) {
            if (e == null || e == com.lightningds.core.library.TrackEdit()) trackMap.remove(id) else trackMap[id] = e
        }
        all + (udn to com.lightningds.core.library.LibraryEdits(albums, trackMap))
    }
}

/** Remembers which folder on the SMB share holds each album ("serverUdn|albumId" → path). */
class AlbumFolderStore(dir: File, scope: CoroutineScope) {
    private val store = JsonFileStore(
        File(dir, "album_folders.json"),
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()),
        emptyMap(),
        scope,
    )

    fun get(udn: String, albumId: String): String? = store.value.value["$udn|$albumId"]
    fun set(udn: String, albumId: String, path: String) = store.update { it + ("$udn|$albumId" to path) }
}
