package app.framealt.send

import app.framealt.data.QueueDao
import app.framealt.data.QueueItem
import app.framealt.data.SentLedgerDao
import app.framealt.data.SentPhoto
import app.framealt.protocol.client.FrameErrorCode
import app.framealt.protocol.client.FrameException
import kotlin.coroutines.cancellation.CancellationException

/** After this many interrupted attempts a photo is given up on, keeping its last error. */
const val MAX_ATTEMPTS = 10

/** Stored as `lastError` when a queued photo's prepared file is gone; the UI keys off it. */
const val MISSING_FILE = "prepared file missing"

/** Uploads one photo over an open session and returns once the frame's receipt arrives. */
fun interface PhotoUploader {
    fun upload(item: QueueItem, data: ByteArray, onProgress: (sentBytes: Long) -> Unit)
}

/** How a drain ended. */
sealed interface DrainOutcome {
    val sent: Int

    /** Nothing sendable is left. */
    data class Finished(override val sent: Int) : DrainOutcome

    /** The connection broke mid-send. Photos are back in line; retry later. */
    data class Interrupted(override val sent: Int, val cause: Throwable) : DrainOutcome

    /** The frame refused in a way that will not change by retrying: full, or not authorised. */
    data class Stopped(override val sent: Int, val code: FrameErrorCode) : DrainOutcome
}

/**
 * Sends every queued photo for one frame over one open session, oldest first.
 *
 * Android-free on purpose, so the per-item outcome rules — the part that decides whether a
 * photo is duplicated or lost — are unit-tested on the JVM. The rules are
 * `Spec/00 - Initial/02 - Architecture.md` §7.2:
 *
 *  - receipt → SENT, recorded in the ledger, prepared file deleted.
 *  - frame full (12) → FAILED, stop.
 *  - unauthorised / permission required (1, 5) → FAILED, stop: every later photo would get
 *    the same answer.
 *  - any other frame refusal → FAILED for that photo, carry on with the next.
 *  - transport failure → back to PREPARED with one more attempt, stop; the session is gone.
 *    At [MAX_ATTEMPTS] the photo is FAILED instead.
 *
 * The queue is re-read before every photo, so photos added during a drain are sent in the
 * same session and photos the user removed are not.
 */
class QueueDrainer(
    private val queue: QueueDao,
    private val ledger: SentLedgerDao,
    private val readFile: (path: String) -> ByteArray,
    private val deleteFile: (path: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /**
     * @param onItemStart called before each photo with how many have been sent so far.
     * @param onProgress live byte progress for the photo in flight; not persisted.
     */
    suspend fun drain(
        peerId: String,
        uploader: PhotoUploader,
        onItemStart: (item: QueueItem, sentSoFar: Int) -> Unit = { _, _ -> },
        onProgress: (item: QueueItem, sentBytes: Long) -> Unit = { _, _ -> },
    ): DrainOutcome {
        var sent = 0
        while (true) {
            val item = queue.sendable(peerId).firstOrNull() ?: return DrainOutcome.Finished(sent)

            val data = try {
                readFile(item.preparedPath)
            } catch (failure: Exception) {
                // The prepared file is the only copy; without it there is nothing to retry.
                log("${item.id.take(8)}: prepared file unreadable (${failure.javaClass.simpleName})")
                queue.markFailed(item.id, item.attempts, MISSING_FILE, null, clock())
                continue
            }

            onItemStart(item, sent)
            queue.markSending(item.id)
            try {
                uploader.upload(item, data) { bytes -> onProgress(item, bytes) }
            } catch (failure: CancellationException) {
                // The worker is being stopped. The SENDING row is reset on the next start.
                throw failure
            } catch (failure: FrameException) {
                log("${item.id.take(8)}: frame refused (code ${failure.rawCode})")
                queue.markFailed(item.id, item.attempts + 1, failure.message.orEmpty(), failure.rawCode.toInt(), clock())
                when (failure.errorCode) {
                    FrameErrorCode.LIMIT_REACHED,
                    FrameErrorCode.UNAUTHORIZED,
                    FrameErrorCode.PERMISSION_REQUIRED,
                    -> return DrainOutcome.Stopped(sent, failure.errorCode)
                    else -> continue
                }
            } catch (failure: Exception) {
                val attempts = item.attempts + 1
                val reason = "${failure.javaClass.simpleName}: ${failure.message}"
                if (attempts >= MAX_ATTEMPTS) {
                    log("${item.id.take(8)}: giving up after $attempts attempts ($reason)")
                    queue.markFailed(item.id, attempts, reason, null, clock())
                } else {
                    log("${item.id.take(8)}: attempt $attempts interrupted ($reason)")
                    queue.markRetry(item.id, reason)
                }
                return DrainOutcome.Interrupted(sent, failure)
            }

            val now = clock()
            queue.markSent(item.id, now)
            ledger.record(SentPhoto(peerId, item.contentId, now, item.displayName, item.mediaId))
            runCatching { deleteFile(item.preparedPath) }
            sent++
        }
    }
}
