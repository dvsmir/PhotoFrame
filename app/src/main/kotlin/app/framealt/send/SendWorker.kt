package app.framealt.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.framealt.FrameAltApp
import app.framealt.data.QueueState
import app.framealt.device.NotPairedException
import app.framealt.protocol.ProtocolException
import app.framealt.protocol.client.FrameErrorCode
import app.framealt.protocol.client.PhotoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "queue"

/** Minimum gap between notification updates, so a fast drain does not spam the shade. */
private const val NOTIFY_INTERVAL_MILLIS = 500L

/**
 * Drains the send queue in one session with the frame. Architecture §7.2.
 *
 * Runs as a `dataSync` foreground service when it can. Android refuses a foreground service
 * started from the background (a backoff retry with the app closed); the drain then runs as
 * ordinary background work, which is enough for a batch of photos.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as FrameAltApp).container
    private val notifications = container.sendNotifications
    private val eventLog = container.eventLog

    override suspend fun doWork(): Result {
        val dao = container.database.queue()
        dao.resetInterrupted()

        val frame = container.frameStore.current()
        if (frame == null) {
            eventLog.warn(TAG, "photos queued but no frame is paired; nothing to do")
            return Result.success()
        }
        val pending = dao.sendable(frame.peerId)
        if (pending.isEmpty()) return Result.success()

        val batchTotal = pending.size
        tryForeground(frame.displayName, 0, batchTotal)
        eventLog.info(TAG, "sending ${pending.size} photo(s), attempt ${runAttemptCount + 1}")

        var lastNotify = 0L
        val drainer = QueueDrainer(
            queue = dao,
            ledger = container.database.ledger(),
            readFile = { java.io.File(it).readBytes() },
            deleteFile = { java.io.File(it).delete() },
            log = { eventLog.debug(TAG, it) },
        )

        val outcome = try {
            container.connections.withSession { session ->
                drainer.drain(
                    peerId = frame.peerId,
                    uploader = { item, data, onProgress ->
                        session.upload(
                            webp = data,
                            caption = item.caption,
                            scale = if (item.fit) PhotoScale.FIT else PhotoScale.CROP,
                            capturedAtMillis = item.capturedAtMs,
                            mediaId = item.mediaId,
                            contentId = item.contentId,
                            onProgress = { sent, _ -> onProgress(sent.toLong()) },
                        )
                    },
                    onItemStart = { item, sentSoFar ->
                        container.sendQueue.reportLive(LiveSend(item.id, 0))
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= NOTIFY_INTERVAL_MILLIS) {
                            lastNotify = now
                            updateProgress(frame.displayName, sentSoFar, maxOf(batchTotal, sentSoFar + 1))
                        }
                    },
                    onProgress = { item, bytes -> container.sendQueue.reportLive(LiveSend(item.id, bytes)) },
                )
            }
        } catch (failure: CancellationException) {
            container.sendQueue.reportLive(null)
            throw failure
        } catch (failure: NotPairedException) {
            container.sendQueue.reportLive(null)
            return Result.success()
        } catch (failure: ProtocolException) {
            // The frame at that address is not the paired one, or its certificate changed.
            // Retrying cannot fix that; Home shows the reason on its next check.
            container.sendQueue.reportLive(null)
            eventLog.error(TAG, "stopped: ${failure.message}")
            notifications.stopped("FrameAlt couldn't verify ${frame.displayName}. Open the app for details.")
            return Result.failure()
        } catch (failure: Exception) {
            // Could not connect at all. No photo was attempted, so none is charged an attempt;
            // a sleeping frame just means trying again later (Testing §6 question 6).
            container.sendQueue.reportLive(null)
            eventLog.warn(TAG, "frame not reachable (${failure.javaClass.simpleName}); will retry")
            notifications.waiting(dao.countUnfinished())
            return Result.retry()
        }
        container.sendQueue.reportLive(null)

        return when (outcome) {
            is DrainOutcome.Finished -> {
                val failed = pending.count { dao.get(it.id)?.state == QueueState.FAILED }
                eventLog.info(TAG, "drain finished: ${outcome.sent} sent, $failed failed")
                if (outcome.sent > 0 || failed > 0) notifications.finished(frame.displayName, outcome.sent, failed)
                Result.success()
            }
            is DrainOutcome.Interrupted -> {
                eventLog.warn(TAG, "interrupted after ${outcome.sent} sent; will retry")
                if (dao.countUnfinished() > 0) notifications.waiting(dao.countUnfinished())
                Result.retry()
            }
            is DrainOutcome.Stopped -> {
                eventLog.error(TAG, "frame refused: ${outcome.code.name}")
                notifications.stopped(
                    when (outcome.code) {
                        FrameErrorCode.LIMIT_REACHED ->
                            "The frame is full. Delete some photos on the frame, then try again."
                        else ->
                            "The frame declined the photos. You may need to pair again — open Add friend on the frame."
                    },
                )
                Result.failure()
            }
        }
    }

    /** False once Android has refused the foreground service for this run. */
    private var inForeground = false

    private suspend fun tryForeground(frameName: String, done: Int, total: Int) {
        inForeground = try {
            setForeground(foregroundInfo(frameName, done, total))
            true
        } catch (failure: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            eventLog.debug(TAG, "running without a foreground service (${failure.javaClass.simpleName})")
            false
        }
    }

    /** Called from the upload thread; fire-and-forget, the drain must not wait on the UI. */
    private fun updateProgress(frameName: String, done: Int, total: Int) {
        if (inForeground) setForegroundAsync(foregroundInfo(frameName, done, total))
    }

    private fun foregroundInfo(frameName: String, done: Int, total: Int) = ForegroundInfo(
        SendNotifications.ID_PROGRESS,
        notifications.progress(frameName, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = container.frameStore.frame.first()?.displayName ?: "your frame"
        return foregroundInfo(name, 0, 0)
    }
}

/** The notification's Cancel action: stop sending and drop what has not gone yet. */
class CancelSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as FrameAltApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.sendQueue.cancelAll()
                container.sendNotifications.clearStatus()
            } finally {
                pending.finish()
            }
        }
    }
}
