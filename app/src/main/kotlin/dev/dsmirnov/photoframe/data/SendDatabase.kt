package dev.dsmirnov.photoframe.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Insert
    suspend fun insertAll(items: List<QueueItem>)

    @Query("SELECT * FROM queue_items ORDER BY createdAt, id")
    fun observeAll(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_items WHERE id = :id")
    suspend fun get(id: String): QueueItem?

    /** What the worker should send next, oldest first. */
    @Query("SELECT * FROM queue_items WHERE state = 'PREPARED' AND peerId = :peerId ORDER BY createdAt, id")
    suspend fun sendable(peerId: String): List<QueueItem>

    @Query("SELECT COUNT(*) FROM queue_items WHERE state IN ('PREPARED', 'SENDING')")
    suspend fun countUnfinished(): Int

    @Query("SELECT * FROM queue_items WHERE state IN ('PREPARED', 'SENDING')")
    suspend fun unfinished(): List<QueueItem>

    @Query("SELECT preparedPath FROM queue_items WHERE state IN ('PREPARED', 'SENDING', 'FAILED')")
    suspend fun livePreparedPaths(): List<String>

    @Query("UPDATE queue_items SET state = 'SENDING', sentBytes = 0 WHERE id = :id")
    suspend fun markSending(id: String)

    @Query("UPDATE queue_items SET sentBytes = :sentBytes WHERE id = :id")
    suspend fun updateProgress(id: String, sentBytes: Long)

    @Query("UPDATE queue_items SET state = 'SENT', sentBytes = preparedBytes, completedAt = :at WHERE id = :id")
    suspend fun markSent(id: String, at: Long)

    /** A transport failure: the photo goes back in line and counts one more attempt. */
    @Query(
        "UPDATE queue_items SET state = 'PREPARED', sentBytes = 0, attempts = attempts + 1, " +
            "lastError = :error WHERE id = :id",
    )
    suspend fun markRetry(id: String, error: String)

    @Query(
        "UPDATE queue_items SET state = 'FAILED', attempts = :attempts, lastError = :error, " +
            "frameErrorCode = :frameErrorCode, completedAt = :at WHERE id = :id",
    )
    suspend fun markFailed(id: String, attempts: Int, error: String, frameErrorCode: Int?, at: Long)

    /** A photo interrupted mid-send by process death is simply not yet sent. */
    @Query("UPDATE queue_items SET state = 'PREPARED', sentBytes = 0 WHERE state = 'SENDING'")
    suspend fun resetInterrupted()

    /** Puts a failed photo back in line with a fresh attempt budget. */
    @Query(
        "UPDATE queue_items SET state = 'PREPARED', attempts = 0, sentBytes = 0, lastError = NULL, " +
            "frameErrorCode = NULL, completedAt = NULL WHERE id = :id AND state = 'FAILED'",
    )
    suspend fun retry(id: String)

    @Query("DELETE FROM queue_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE queue_items SET state = 'CANCELLED', completedAt = :at WHERE state IN ('PREPARED', 'FAILED')")
    suspend fun cancelAllWaiting(at: Long)

    /** Also covers sends from before the ledger kept media IDs (schema 1). */
    @Query("SELECT mediaId FROM queue_items WHERE peerId = :peerId AND state = 'SENT'")
    suspend fun sentMediaIds(peerId: String): List<Long>

    @Query("DELETE FROM queue_items WHERE state IN ('SENT', 'CANCELLED') AND completedAt < :before")
    suspend fun pruneFinished(before: Long)
}

@Dao
interface SentLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(photo: SentPhoto)

    @Query("SELECT contentId FROM sent_ledger WHERE peerId = :peerId AND contentId IN (:contentIds)")
    suspend fun alreadySent(peerId: String, contentIds: List<Long>): List<Long>

    /** Media IDs this phone sent to the frame and still remembers, for the gallery. */
    @Query("SELECT mediaId FROM sent_ledger WHERE peerId = :peerId AND mediaId IS NOT NULL")
    suspend fun sentMediaIds(peerId: String): List<Long>
}

class QueueConverters {
    @TypeConverter
    fun fromState(state: QueueState): String = state.name

    @TypeConverter
    fun toState(value: String): QueueState = QueueState.valueOf(value)
}

@Database(
    entities = [QueueItem::class, SentPhoto::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // 2: sent_ledger.mediaId, for "Select photos sent from this phone".
        AutoMigration(from = 1, to = 2),
    ],
)
@TypeConverters(QueueConverters::class)
abstract class SendDatabase : RoomDatabase() {

    abstract fun queue(): QueueDao

    abstract fun ledger(): SentLedgerDao

    companion object {
        fun open(context: Context): SendDatabase =
            Room.databaseBuilder(context, SendDatabase::class.java, "photoframe_send.db").build()
    }
}
