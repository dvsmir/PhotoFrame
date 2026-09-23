package dev.dsmirnov.photoframe.send

import dev.dsmirnov.photoframe.data.QueueDao
import dev.dsmirnov.photoframe.data.QueueItem
import dev.dsmirnov.photoframe.data.QueueState
import dev.dsmirnov.photoframe.data.SentLedgerDao
import dev.dsmirnov.photoframe.data.SentPhoto
import dev.dsmirnov.photoframe.protocol.client.FrameErrorCode
import dev.dsmirnov.photoframe.protocol.client.FrameException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PEER = "640ff2e0"

class QueueDrainerTest {

    private val dao = FakeQueueDao()
    private val ledger = FakeLedger()
    private val deleted = mutableListOf<String>()
    private val files = mutableMapOf<String, ByteArray>()

    private val drainer = QueueDrainer(
        queue = dao,
        ledger = ledger,
        readFile = { files[it] ?: throw IOException("gone") },
        deleteFile = { deleted += it },
        clock = { 1_000 },
    )

    private fun queue(vararg ids: String) = ids.forEachIndexed { index, id ->
        files["/outbox/$id.webp"] = byteArrayOf(index.toByte())
        dao.rows[id] = QueueItem(
            id = id, peerId = PEER, batchId = "b", displayName = "$id.jpg",
            preparedPath = "/outbox/$id.webp", preparedBytes = 100, caption = "", fit = true,
            capturedAtMs = 0, mediaId = 1000L + index, contentId = 2000L + index,
            state = QueueState.PREPARED, createdAt = index.toLong(),
        )
    }

    @Test
    fun `sends everything in order, records it and deletes the prepared files`() = runBlocking {
        queue("a", "b", "c")
        val order = mutableListOf<String>()

        val outcome = drainer.drain(PEER, { item, _, _ -> order += item.id })

        assertEquals(DrainOutcome.Finished(3), outcome)
        assertEquals(listOf("a", "b", "c"), order)
        assertTrue(dao.rows.values.all { it.state == QueueState.SENT })
        assertEquals(setOf(2000L, 2001L, 2002L), ledger.rows.map { it.contentId }.toSet())
        assertEquals(setOf(1000L, 1001L, 1002L), ledger.rows.mapNotNull { it.mediaId }.toSet(), "the gallery finds our photos by these")
        assertEquals(3, deleted.size)
    }

    @Test
    fun `a dropped connection puts the photo back in line and stops the drain`() = runBlocking {
        queue("a", "b")

        val outcome = drainer.drain(PEER, { item, _, _ -> if (item.id == "a") throw IOException("reset") })

        assertIs<DrainOutcome.Interrupted>(outcome)
        assertEquals(QueueState.PREPARED, dao.rows.getValue("a").state)
        assertEquals(1, dao.rows.getValue("a").attempts)
        assertEquals(QueueState.PREPARED, dao.rows.getValue("b").state, "the rest must not be touched")
        assertEquals(0, dao.rows.getValue("b").attempts)
        assertTrue(deleted.isEmpty(), "the prepared file is the only copy; it must survive a retry")
    }

    @Test
    fun `retries reuse the same media and content IDs`() = runBlocking {
        queue("a")
        val seen = mutableListOf<Pair<Long, Long>>()
        var calls = 0
        val flaky = PhotoUploader { item, _, _ ->
            seen += item.mediaId to item.contentId
            if (calls++ < 2) throw IOException("receipt never arrived")
        }

        repeat(3) { drainer.drain(PEER, flaky) }

        assertEquals(3, seen.size)
        assertEquals(1, seen.toSet().size, "every attempt must carry identical identifiers")
        assertEquals(QueueState.SENT, dao.rows.getValue("a").state)
    }

    @Test
    fun `gives up after the tenth interrupted attempt`() = runBlocking {
        queue("a")
        val broken = PhotoUploader { _, _, _ -> throw IOException("reset") }

        repeat(MAX_ATTEMPTS) { drainer.drain(PEER, broken) }

        val row = dao.rows.getValue("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(MAX_ATTEMPTS, row.attempts)
        assertTrue(row.lastError.orEmpty().contains("reset"))
    }

    @Test
    fun `a full frame fails the photo and stops`() = runBlocking {
        queue("a", "b")

        val outcome = drainer.drain(PEER, { _, _, _ -> throw FrameException(FrameErrorCode.LIMIT_REACHED, 12) })

        assertEquals(DrainOutcome.Stopped(0, FrameErrorCode.LIMIT_REACHED), outcome)
        assertEquals(QueueState.FAILED, dao.rows.getValue("a").state)
        assertEquals(12, dao.rows.getValue("a").frameErrorCode)
        assertEquals(QueueState.PREPARED, dao.rows.getValue("b").state)
    }

    @Test
    fun `an unauthorised frame stops the drain`() = runBlocking {
        queue("a", "b")

        val outcome = drainer.drain(PEER, { _, _, _ -> throw FrameException(FrameErrorCode.UNAUTHORIZED, 1) })

        assertEquals(DrainOutcome.Stopped(0, FrameErrorCode.UNAUTHORIZED), outcome)
        assertEquals(QueueState.PREPARED, dao.rows.getValue("b").state)
    }

    @Test
    fun `a photo the frame rejects on its own is skipped, the rest still go`() = runBlocking {
        queue("a", "b")

        val outcome = drainer.drain(PEER, { item, _, _ ->
            if (item.id == "a") throw FrameException(FrameErrorCode.BAD_REQUEST, 3)
        })

        assertEquals(DrainOutcome.Finished(1), outcome)
        assertEquals(QueueState.FAILED, dao.rows.getValue("a").state)
        assertEquals(QueueState.SENT, dao.rows.getValue("b").state)
    }

    @Test
    fun `a missing prepared file fails that photo without stopping`() = runBlocking {
        queue("a", "b")
        files.remove("/outbox/a.webp")

        val outcome = drainer.drain(PEER, { _, _, _ -> })

        assertEquals(DrainOutcome.Finished(1), outcome)
        assertEquals(MISSING_FILE, dao.rows.getValue("a").lastError)
        assertEquals(QueueState.SENT, dao.rows.getValue("b").state)
    }

    @Test
    fun `photos queued during a drain go out in the same session`() = runBlocking {
        queue("a")
        var added = false

        val outcome = drainer.drain(PEER, { _, _, _ ->
            if (!added) {
                added = true
                files["/outbox/late.webp"] = byteArrayOf(9)
                dao.rows["late"] = dao.rows.getValue("a").copy(
                    id = "late", preparedPath = "/outbox/late.webp", state = QueueState.PREPARED, createdAt = 99,
                )
            }
        })

        assertEquals(DrainOutcome.Finished(2), outcome)
    }

    @Test
    fun `photos for another frame are left alone`() = runBlocking {
        queue("a")
        dao.rows["a"] = dao.rows.getValue("a").copy(peerId = "someone-else")

        assertEquals(DrainOutcome.Finished(0), drainer.drain(PEER, { _, _, _ -> error("must not send") }))
    }
}

/** Just enough of Room's behaviour for the drain rules: a map with the DAO's semantics. */
private class FakeQueueDao : QueueDao {
    val rows = linkedMapOf<String, QueueItem>()

    private fun update(id: String, change: (QueueItem) -> QueueItem) {
        rows[id]?.let { rows[id] = change(it) }
    }

    override suspend fun insertAll(items: List<QueueItem>) = items.forEach { rows[it.id] = it }
    override fun observeAll(): Flow<List<QueueItem>> = flowOf(rows.values.toList())
    override suspend fun get(id: String) = rows[id]
    override suspend fun sendable(peerId: String) =
        rows.values.filter { it.state == QueueState.PREPARED && it.peerId == peerId }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    override suspend fun countUnfinished() = unfinished().size
    override suspend fun unfinished() = rows.values.filter { it.state == QueueState.PREPARED || it.state == QueueState.SENDING }
    override suspend fun livePreparedPaths() =
        rows.values.filter { it.state in setOf(QueueState.PREPARED, QueueState.SENDING, QueueState.FAILED) }.map { it.preparedPath }
    override suspend fun markSending(id: String) = update(id) { it.copy(state = QueueState.SENDING, sentBytes = 0) }
    override suspend fun updateProgress(id: String, sentBytes: Long) = update(id) { it.copy(sentBytes = sentBytes) }
    override suspend fun markSent(id: String, at: Long) =
        update(id) { it.copy(state = QueueState.SENT, sentBytes = it.preparedBytes, completedAt = at) }
    override suspend fun markRetry(id: String, error: String) =
        update(id) { it.copy(state = QueueState.PREPARED, sentBytes = 0, attempts = it.attempts + 1, lastError = error) }
    override suspend fun markFailed(id: String, attempts: Int, error: String, frameErrorCode: Int?, at: Long) =
        update(id) {
            it.copy(state = QueueState.FAILED, attempts = attempts, lastError = error, frameErrorCode = frameErrorCode, completedAt = at)
        }
    override suspend fun resetInterrupted() =
        rows.keys.toList().forEach { id -> if (rows[id]?.state == QueueState.SENDING) update(id) { it.copy(state = QueueState.PREPARED) } }
    override suspend fun retry(id: String) = update(id) {
        if (it.state == QueueState.FAILED) it.copy(state = QueueState.PREPARED, attempts = 0, lastError = null, frameErrorCode = null) else it
    }
    override suspend fun delete(id: String) { rows.remove(id) }
    override suspend fun cancelAllWaiting(at: Long) = Unit
    override suspend fun sentMediaIds(peerId: String) =
        rows.values.filter { it.peerId == peerId && it.state == QueueState.SENT }.map { it.mediaId }
    override suspend fun pruneFinished(before: Long) = Unit
}

private class FakeLedger : SentLedgerDao {
    val rows = mutableListOf<SentPhoto>()
    override suspend fun record(photo: SentPhoto) { rows += photo }
    override suspend fun alreadySent(peerId: String, contentIds: List<Long>) =
        rows.filter { it.peerId == peerId && it.contentId in contentIds }.map { it.contentId }
    override suspend fun sentMediaIds(peerId: String) = rows.filter { it.peerId == peerId }.mapNotNull { it.mediaId }
}
