package dev.dsmirnov.photoframe.send

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.dsmirnov.photoframe.data.QueueDao
import dev.dsmirnov.photoframe.data.QueueItem
import dev.dsmirnov.photoframe.data.QueueState
import dev.dsmirnov.photoframe.diag.EventLog
import dev.dsmirnov.photoframe.media.PreparedPhoto
import dev.dsmirnov.photoframe.protocol.Frameo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "queue"
private const val WORK_NAME = "send-queue"

/** Finished items are kept this long so Home can show what was sent recently. */
private const val KEEP_FINISHED_MILLIS = 7L * 24 * 60 * 60 * 1000

/** The photo in flight right now, for live progress. Deliberately not persisted. */
data class LiveSend(val itemId: String, val sentBytes: Long)

/**
 * The app's view of the send queue: what the UI adds, retries and removes, and what starts
 * the worker. The worker itself is [SendWorker]; the sending rules are [QueueDrainer].
 */
class SendQueue(
    private val context: Context,
    private val queue: QueueDao,
    private val outbox: () -> File,
    private val eventLog: EventLog,
) {

    val items: Flow<List<QueueItem>> = queue.observeAll()

    private val _live = MutableStateFlow<LiveSend?>(null)
    val live: StateFlow<LiveSend?> = _live.asStateFlow()

    internal fun reportLive(value: LiveSend?) {
        _live.value = value
    }

    /** Queues prepared photos as one batch and starts sending. */
    suspend fun enqueue(
        peerId: String,
        photos: List<PreparedPhoto>,
        caption: String,
        fit: Boolean,
    ) {
        if (photos.isEmpty()) return
        val batchId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val items = photos.mapIndexed { index, photo ->
            QueueItem(
                id = UUID.randomUUID().toString(),
                peerId = peerId,
                batchId = batchId,
                displayName = photo.displayName,
                preparedPath = photo.file.absolutePath,
                preparedBytes = photo.bytes,
                caption = caption,
                fit = fit,
                capturedAtMs = photo.capturedAtMs,
                // Generated once, here, and reused on every retry. Architecture §7.3.
                mediaId = Frameo.newId(),
                contentId = photo.contentId,
                state = QueueState.PREPARED,
                // Keeps the pick order within a batch even when the clock does not tick.
                createdAt = now + index,
            )
        }
        queue.insertAll(items)
        eventLog.info(TAG, "queued ${items.size} photo(s)")
        kick()
    }

    suspend fun retry(id: String) {
        queue.retry(id)
        kick()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val item = queue.get(id) ?: return@withContext
        if (item.state == QueueState.SENDING) return@withContext
        File(item.preparedPath).delete()
        queue.delete(id)
    }

    /** Stops sending and drops everything not yet sent — used by Cancel and Remove frame. */
    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        queue.resetInterrupted()
        val waiting = queue.livePreparedPaths()
        queue.cancelAllWaiting(System.currentTimeMillis())
        waiting.forEach { File(it).delete() }
        _live.value = null
        eventLog.info(TAG, "cancelled ${waiting.size} waiting photo(s)")
    }

    /**
     * Housekeeping at process start: forget old finished items, and delete prepared files no
     * queue item points at — left behind when the app died on the review screen.
     */
    suspend fun tidy() = withContext(Dispatchers.IO) {
        queue.pruneFinished(System.currentTimeMillis() - KEEP_FINISHED_MILLIS)
        val live = queue.livePreparedPaths().toSet()
        val orphans = outbox().listFiles().orEmpty().filter { it.absolutePath !in live }
        orphans.forEach { it.delete() }
        if (orphans.isNotEmpty()) eventLog.debug(TAG, "removed ${orphans.size} orphaned prepared file(s)")
        if (queue.countUnfinished() > 0) kick()
    }

    /**
     * Makes sure the worker will run soon.
     *
     * A worker waiting out a backoff is replaced so it runs now — this is called when the
     * user taps Send, when the app starts, and when Wi-Fi comes back, all moments where
     * waiting would be wrong. A running worker is left alone and a follow-up is appended, so
     * photos added in the last moments of a drain are still picked up.
     */
    suspend fun kick(): Unit = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        val infos = runCatching { workManager.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull().orEmpty()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val policy = if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(WORK_NAME, policy, request())
    }

    private fun request() = OneTimeWorkRequestBuilder<SendWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkRequest(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        // Home Wi-Fi whose internet is down still reaches the frame.
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    NetworkType.UNMETERED,
                )
                .build(),
        )
        // WorkManager caps exponential backoff at 5 h, not the 15 min the spec asks for;
        // kick() on Wi-Fi return and app start covers the cases where that would matter.
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
}
