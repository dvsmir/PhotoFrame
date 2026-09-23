package app.framealt.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where one photo is in its journey to the frame.
 *
 * ```
 * PREPARED ──▶ SENDING ──▶ SENT
 *     ▲           │
 *     └───────────┤  transport failure: back to PREPARED, attempts + 1
 *                 └──▶ FAILED (terminal)      CANCELLED (terminal, by the user)
 * ```
 *
 * `PENDING` and `PREPARING` from the architecture spec never reach the database: a photo is
 * prepared in the foreground, while its `content://` grant is still alive, and only a
 * prepared file is ever queued. See `Spec/00 - Initial/02 - Architecture.md` §6 and §8.5.
 */
enum class QueueState {
    PREPARED,
    SENDING,
    SENT,
    FAILED,
    CANCELLED,
    ;

    val isFinished: Boolean get() = this == SENT || this == FAILED || this == CANCELLED
}

/**
 * One photo in the send queue.
 *
 * [mediaId] and [contentId] are generated once, when the photo is queued, and reused on every
 * retry. If a receipt is lost after all the data went out, the frame may already hold the
 * photo; giving it identical identifiers is the only lever there is for the frame to notice.
 * See `Spec/00 - Initial/02 - Architecture.md` §7.3.
 */
@Entity(
    tableName = "queue_items",
    indices = [Index("state"), Index("batchId")],
)
data class QueueItem(
    @PrimaryKey val id: String,
    val peerId: String,
    /** Groups the photos from one pick or share. */
    val batchId: String,
    val displayName: String,
    /** App-private WebP. The durable source of truth once queued; deleted after SENT. */
    val preparedPath: String,
    val preparedBytes: Long,
    val caption: String,
    val fit: Boolean,
    val capturedAtMs: Long,
    val mediaId: Long,
    val contentId: Long,
    val state: QueueState,
    val sentBytes: Long = 0,
    val attempts: Int = 0,
    /** Diagnostic text for the log; the UI maps failures through `describeFailure`. */
    val lastError: String? = null,
    /** Set when the frame refused the photo, so the UI can show the right sentence. */
    val frameErrorCode: Int? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
)

/**
 * Content this phone has already delivered to a frame.
 *
 * Powers the "Already sent" hint on the review screen, and outlives the queue: clearing
 * finished items does not forget what was sent.
 */
@Entity(tableName = "sent_ledger", primaryKeys = ["peerId", "contentId"])
data class SentPhoto(
    val peerId: String,
    val contentId: Long,
    val sentAt: Long,
    val displayName: String,
    /**
     * The ID the frame holds the photo under, so the gallery can pick out what this phone
     * sent. Null for rows written before schema version 2.
     */
    val mediaId: Long? = null,
)
