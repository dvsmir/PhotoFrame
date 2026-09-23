package app.framealt.ui.home

import app.framealt.data.QueueItem
import app.framealt.data.QueueState
import app.framealt.send.LiveSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivitySummaryTest {

    private fun item(id: String, batch: String, state: QueueState, completedAt: Long? = null) = QueueItem(
        id = id, peerId = "p", batchId = batch, displayName = id, preparedPath = "/$id",
        preparedBytes = 1_000, caption = "", fit = true, capturedAtMs = 0, mediaId = 1, contentId = 2,
        state = state, createdAt = 0, completedAt = completedAt,
    )

    @Test
    fun `a running batch counts its sent photos and live bytes`() {
        val items = listOf(
            item("a", "b1", QueueState.SENT, 10),
            item("b", "b1", QueueState.SENDING),
            item("c", "b1", QueueState.PREPARED),
        )

        val summary = summarise(items, LiveSend("b", 250))

        assertEquals(3, summary.total)
        assertEquals(1, summary.done)
        assertEquals(3_000, summary.totalBytes)
        assertEquals(1_250, summary.sentBytes)
        assertEquals(2, summary.waiting)
        assertTrue(summary.sendingNow)
        assertTrue(summary.recent.isEmpty(), "a batch still going is not 'recent'")
    }

    @Test
    fun `finished batches become one line each, newest first, without cancelled photos`() {
        val items = listOf(
            item("a", "old", QueueState.SENT, 100),
            item("b", "new", QueueState.SENT, 200),
            item("c", "new", QueueState.SENT, 300),
            item("d", "new", QueueState.CANCELLED, 400),
            item("e", "gone", QueueState.CANCELLED, 500),
        )

        val summary = summarise(items, null)

        assertEquals(listOf(BatchSummary(2, 300), BatchSummary(1, 100)), summary.recent)
        assertEquals(0, summary.total)
        assertFalse(summary.sendingNow)
    }

    @Test
    fun `failed photos are listed on their own`() {
        val summary = summarise(listOf(item("a", "b", QueueState.FAILED, 1)), null)

        assertEquals(listOf("a"), summary.failed.map { it.id })
        assertFalse(summary.isEmpty)
    }
}
