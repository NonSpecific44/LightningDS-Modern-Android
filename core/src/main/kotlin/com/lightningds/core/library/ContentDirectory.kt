package com.lightningds.core.library

import com.lightningds.core.media.Container
import com.lightningds.core.media.Didl
import com.lightningds.core.media.Track
import com.lightningds.core.media.TrackSource
import com.lightningds.core.upnp.ServiceTypes
import com.lightningds.core.upnp.UpnpDevice
import com.lightningds.core.upnp.UpnpService

data class BrowsePage(
    val containers: List<Container>,
    val items: List<Track>,
    val numberReturned: Int,
    val totalMatches: Int,
)

class ContentDirectory(private val svc: UpnpService, val serverUdn: String) {

    suspend fun browse(objectId: String, start: Int = 0, count: Int = 100, sort: String = ""): BrowsePage {
        val r = svc.call(
            "Browse",
            "ObjectID" to objectId,
            "BrowseFlag" to "BrowseDirectChildren",
            "Filter" to "*",
            "StartingIndex" to start,
            "RequestedCount" to count,
            "SortCriteria" to sort,
        )
        val didl = Didl.parse(r.get("Result", 0), TrackSource.LIBRARY, serverUdn)
        return BrowsePage(didl.containers, didl.items, r.int("NumberReturned", 1), r.int("TotalMatches", 2))
    }

    suspend fun metadata(objectId: String): BrowsePage {
        val r = svc.call(
            "Browse",
            "ObjectID" to objectId,
            "BrowseFlag" to "BrowseMetadata",
            "Filter" to "*",
            "StartingIndex" to 0,
            "RequestedCount" to 1,
            "SortCriteria" to "",
        )
        val didl = Didl.parse(r.get("Result", 0), TrackSource.LIBRARY, serverUdn)
        return BrowsePage(didl.containers, didl.items, r.int("NumberReturned", 1), r.int("TotalMatches", 2))
    }

    /** Browses every child of [objectId], following pagination. */
    suspend fun browseAll(objectId: String, pageSize: Int = 100, onPage: (BrowsePage, Int) -> Unit = { _, _ -> }): BrowsePage {
        val containers = ArrayList<Container>()
        val items = ArrayList<Track>()
        var start = 0
        var total: Int
        do {
            val page = browse(objectId, start, pageSize)
            containers += page.containers
            items += page.items
            total = page.totalMatches
            val got = page.containers.size + page.items.size
            start += if (got > 0) got else pageSize
            onPage(page, start)
            if (got == 0) break
        } while (total == 0 && got >= pageSize || start < total)
        return BrowsePage(containers, items, containers.size + items.size, maxOf(total, containers.size + items.size))
    }

    suspend fun search(containerId: String, criteria: String, start: Int = 0, count: Int = 100, sort: String = ""): BrowsePage {
        val r = svc.call(
            "Search",
            "ContainerID" to containerId,
            "SearchCriteria" to criteria,
            "Filter" to "*",
            "StartingIndex" to start,
            "RequestedCount" to count,
            "SortCriteria" to sort,
        )
        val didl = Didl.parse(r.get("Result", 0), TrackSource.LIBRARY, serverUdn)
        return BrowsePage(didl.containers, didl.items, r.int("NumberReturned", 1), r.int("TotalMatches", 2))
    }

    suspend fun searchCapabilities(): String = runCatching { svc.call("GetSearchCapabilities").get("SearchCaps", 0) }.getOrDefault("")

    suspend fun systemUpdateId(): Long = runCatching { svc.call("GetSystemUpdateID").long("Id", 0) }.getOrDefault(0L)

    companion object {
        const val ROOT = "0"

        fun of(device: UpnpDevice): ContentDirectory? =
            device.service("ContentDirectory", ServiceTypes.UPNP)?.let { ContentDirectory(it, device.udn) }
    }
}
