package app.framealt.ui.share

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedItemsTest {

    @Test
    fun `three images and a PDF keep the images and count one skipped`() {
        val shared = classifyShared(
            listOf(
                "a" to "image/jpeg",
                "b" to "image/heic",
                "doc" to "application/pdf",
                "c" to "image/png",
            ),
        )

        assertEquals(listOf("a", "b", "c"), shared.photos)
        assertEquals(4, shared.offered)
        assertEquals(1, shared.skipped)
    }

    @Test
    fun `videos in a mixed share are skipped too`() {
        val shared = classifyShared(listOf("a" to "image/jpeg", "clip" to "video/mp4"))

        assertEquals(listOf("a"), shared.photos)
        assertEquals(1, shared.skipped)
    }

    @Test
    fun `an item of unknown type is kept for the pipeline to judge`() {
        assertEquals(listOf("a", "mystery"), classifyShared(listOf("a" to "image/jpeg", "mystery" to null)).photos)
    }

    @Test
    fun `the same item shared twice counts once`() {
        val shared = classifyShared(listOf("a" to "image/jpeg", "a" to "image/jpeg"))

        assertEquals(listOf("a"), shared.photos)
        assertEquals(1, shared.offered)
    }

    @Test
    fun `type matching ignores case`() {
        assertEquals(listOf("a"), classifyShared(listOf("a" to "IMAGE/JPEG")).photos)
    }
}
