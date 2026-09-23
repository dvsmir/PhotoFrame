package app.framealt.media

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SizingTest {

    /** The panel the tested frame reports: portrait-native, 800 × 1280. */
    private val panel = PixelSize(800, 1280)

    @Test
    fun `a 12 MP landscape photo covers the panel's long side`() {
        assertEquals(PixelSize(1280, 960), targetSize(PixelSize(4000, 3000), panel))
    }

    @Test
    fun `portrait and landscape get the same treatment`() {
        assertEquals(PixelSize(960, 1280), targetSize(PixelSize(3000, 4000), panel))
    }

    @Test
    fun `the panel's orientation does not matter`() {
        val source = PixelSize(4000, 3000)
        assertEquals(targetSize(source, PixelSize(800, 1280)), targetSize(source, PixelSize(1280, 800)))
    }

    @Test
    fun `a panorama keeps enough height to fill the short side`() {
        // 10000 x 2000: long side alone would give 1280 x 256; the short side needs 800.
        assertEquals(PixelSize(4000, 800), targetSize(PixelSize(10_000, 2_000), panel))
    }

    @Test
    fun `small photos are never upscaled`() {
        assertEquals(PixelSize(640, 480), targetSize(PixelSize(640, 480), panel))
    }

    @Test
    fun `a sliver never collapses to zero pixels`() {
        assertEquals(1, targetSize(PixelSize(20_000, 1), panel).height)
    }

    @Test
    fun `EXIF dates use the offset tag when present`() {
        assertEquals(
            1_442_487_600_000, // 2015-09-17T11:00:00Z
            parseExifDate("2015:09:17 13:00:00", "+02:00", ZoneId.of("UTC")),
        )
    }

    @Test
    fun `EXIF dates without an offset are read in the given zone`() {
        assertEquals(1_442_494_800_000, parseExifDate("2015:09:17 13:00:00", null, ZoneOffset.UTC))
    }

    @Test
    fun `blank or zeroed EXIF dates are ignored`() {
        assertNull(parseExifDate(null, null))
        assertNull(parseExifDate("   ", null))
        assertNull(parseExifDate("0000:00:00 00:00:00", null))
    }
}
