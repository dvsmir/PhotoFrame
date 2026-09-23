package app.framealt.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sources larger than this are refused before decoding, so a bad file cannot exhaust memory. */
const val MAX_SOURCE_PIXELS: Long = 50_000_000

/** The protocol's ceiling on one upload. Unreachable at panel resolutions; kept as a guard. */
const val MAX_PREPARED_BYTES: Long = 32L * 1024 * 1024

data class PixelSize(val width: Int, val height: Int)

/**
 * The size to decode a photo at so it still covers the frame's panel.
 *
 * Orientation-free on purpose: a frame reports its panel in its native orientation (the one
 * tested reports 800 × 1280), but it may stand either way and rotates photos itself. Matching
 * long side to long side keeps a landscape photo sharp on a landscape-mounted portrait panel
 * and vice versa, and never upscales.
 */
fun targetSize(source: PixelSize, panel: PixelSize): PixelSize {
    require(source.width > 0 && source.height > 0) { "source has no pixels" }
    val sourceLong = max(source.width, source.height).toDouble()
    val sourceShort = min(source.width, source.height).toDouble()
    val panelLong = max(panel.width, panel.height).toDouble()
    val panelShort = min(panel.width, panel.height).toDouble()

    val scale = min(1.0, max(panelLong / sourceLong, panelShort / sourceShort))
    return PixelSize(
        width = max(1, (source.width * scale).roundToInt()),
        height = max(1, (source.height * scale).roundToInt()),
    )
}
