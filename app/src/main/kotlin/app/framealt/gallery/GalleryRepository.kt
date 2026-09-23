package app.framealt.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.framealt.data.QueueDao
import app.framealt.data.SentLedgerDao
import app.framealt.device.FrameConnectionManager
import app.framealt.diag.EventLog
import app.framealt.protocol.client.FrameException
import app.framealt.protocol.client.FrameInfo
import app.framealt.protocol.client.MediaDownload
import app.framealt.protocol.client.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "gallery"

/** The grid asks the frame for previews of this size. The reference client uses the same. */
const val THUMBNAIL_SIZE = 400

/** Bounded like the reference client's cache. Thumbnails are never written to disk. */
private const val THUMBNAIL_CACHE_BYTES = 64 * 1024 * 1024

/** How long to wait for the owner to walk to the frame and tap Allow (UX §6.1). */
private const val ACCESS_WAIT_MILLIS = 5L * 60 * 1000
private const val ACCESS_POLL_MILLIS = 2_000L

/**
 * What is on the frame, and everything the gallery does to it.
 *
 * One per process: the item list and thumbnail cache outlive a single screen, so going from
 * the grid to a preview and back does not refetch anything.
 */
class GalleryRepository(
    private val connections: FrameConnectionManager,
    private val queue: QueueDao,
    private val ledger: SentLedgerDao,
    private val scope: CoroutineScope,
    private val eventLog: EventLog,
) {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())

    /** Newest first by the time the frame received each item. */
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val cache = object : LruCache<Long, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    private val _thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())

    /** A snapshot of the cache, republished whenever a thumbnail arrives. */
    val thumbnails: StateFlow<Map<Long, Bitmap>> = _thumbnails.asStateFlow()

    private val pending = LinkedHashSet<Long>()
    private val failed = HashSet<Long>()
    private var loader: Job? = null

    /** Lists the frame, returning what it reported about itself so callers can check access. */
    suspend fun refresh(): FrameInfo = connections.withSession { session ->
        val info = session.info
        if (info.permissions.view && info.protocolVersion >= MIN_GALLERY_VERSION) {
            _items.value = session.listMedia().sortedByDescending { it.receivedAtMillis }
            eventLog.info(TAG, "listed ${_items.value.size} item(s)")
        }
        synchronized(pending) { failed.clear() }
        info
    }

    /**
     * Asks for view and manage access together, then waits for the owner to allow it on the
     * frame. There is no push answer, so this polls; each poll is a short separate session,
     * which keeps the send queue free to run in between.
     *
     * @return the frame's info once access is granted, or null after the 5-minute wait.
     */
    suspend fun requestAccess(): FrameInfo? {
        connections.withSession { it.requestPermission(manage = true) }
        eventLog.info(TAG, "asked the frame for view and manage access")
        return withTimeoutOrNull(ACCESS_WAIT_MILLIS) {
            var info: FrameInfo
            do {
                delay(ACCESS_POLL_MILLIS)
                info = connections.withSession { it.info }
            } while (!info.permissions.view)
            info
        }
    }

    /** Queues a thumbnail fetch. The newest request is served first: it is what is on screen. */
    fun requestThumbnail(id: Long) {
        synchronized(pending) {
            if (cache.get(id) != null || id in failed) return
            pending.remove(id)
            pending.add(id)
            if (loader?.isActive == true) return
            loader = scope.launch { loadPending() }
        }
    }

    private fun nextPending(): Long? = synchronized(pending) {
        pending.lastOrNull()?.also { pending.remove(it) }
    }

    /** Drains the requests in one session, reconnecting only if more arrive after it closes. */
    private suspend fun loadPending() {
        while (synchronized(pending) { pending.isNotEmpty() }) {
            try {
                connections.withSession { session ->
                    while (true) {
                        val id = nextPending() ?: break
                        try {
                            val download = session.fetchMedia(id, THUMBNAIL_SIZE)
                            val bitmap = BitmapFactory.decodeByteArray(download.bytes, 0, download.bytes.size)
                            if (bitmap == null) {
                                synchronized(pending) { failed.add(id) }
                            } else {
                                cache.put(id, bitmap)
                                _thumbnails.value = cache.snapshot()
                            }
                        } catch (failure: FrameException) {
                            // This one item has no preview (a video, say); the rest can continue.
                            synchronized(pending) { failed.add(id) }
                        }
                    }
                }
            } catch (failure: Exception) {
                eventLog.warn(TAG, "thumbnails stopped: ${failure.javaClass.simpleName}")
                synchronized(pending) { pending.clear() }
                return
            }
        }
    }

    suspend fun fetchFull(id: Long): MediaDownload = connections.withSession { it.fetchMedia(id) }

    suspend fun delete(ids: Set<Long>) {
        connections.withSession { it.delete(ids) }
        _items.value = _items.value.filterNot { it.id in ids }
        ids.forEach { cache.remove(it) }
        _thumbnails.value = cache.snapshot()
        eventLog.info(TAG, "deleted ${ids.size} item(s)")
    }

    suspend fun setVisibility(ids: Set<Long>, visible: Boolean) {
        connections.withSession { it.setVisibility(ids, visible) }
        _items.value = _items.value.map {
            if (it.id in ids) MediaItem(it.id, it.type, visible, it.capturedAtMillis, it.receivedAtMillis) else it
        }
        eventLog.info(TAG, "${if (visible) "showed" else "hid"} ${ids.size} item(s)")
    }

    suspend fun displayNow(id: Long) {
        connections.withSession { it.displayNow(id) }
    }

    /**
     * Media IDs this phone sent to [peerId], from the ledger (kept) and the queue (recent,
     * and the only record of sends made before the ledger stored IDs).
     */
    suspend fun sentFromThisPhone(peerId: String): Set<Long> =
        (ledger.sentMediaIds(peerId) + queue.sentMediaIds(peerId)).toSet()

    companion object {
        /** Frames older than this cannot list their media (protocol §8.4). */
        const val MIN_GALLERY_VERSION = 13
    }
}
