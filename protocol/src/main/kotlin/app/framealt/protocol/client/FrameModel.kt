package app.framealt.protocol.client

/** What the frame reports about itself, from a kind-2 message. */
public class FrameInfo(
    public val name: String,
    public val placement: String,
    public val width: Int,
    public val height: Int,
    public val protocolVersion: Int,
    public val permissions: FramePermissions,
) {
    public companion object {
        /** Before the first successful connection. Panel size matches the reference default. */
        public val UNKNOWN: FrameInfo = FrameInfo(
            name = "",
            placement = "",
            width = 1280,
            height = 800,
            protocolVersion = 0,
            permissions = FramePermissions(),
        )
    }
}

/**
 * Permission bits the frame owner grants on the frame itself.
 *
 * Pairing alone allows sending. [view] is needed for the gallery, [manage] for the
 * hide/delete/display operations that v1 does not implement.
 */
public class FramePermissions(
    public val sharePairing: Boolean = false,
    public val backup: Boolean = false,
    public val view: Boolean = false,
    public val manage: Boolean = false,
)

public enum class MediaType {
    PHOTO,
    VIDEO,
    GREETING,
    UNKNOWN,
    ;

    internal companion object {
        fun of(value: Long): MediaType = when (value) {
            0L -> PHOTO
            1L -> VIDEO
            2L -> GREETING
            else -> UNKNOWN
        }
    }
}

/**
 * One item on the frame.
 *
 * [id] is a signed 64-bit value and **negative IDs are normal**. Never round-trip one
 * through a float or a JavaScript number.
 */
public class MediaItem(
    public val id: Long,
    public val type: MediaType,
    public val visible: Boolean,
    public val capturedAtMillis: Long,
    public val receivedAtMillis: Long,
)

/** A photo fetched back off the frame. */
public class MediaDownload(
    public val bytes: ByteArray,
    public val extension: String,
    public val caption: String,
)

/** How the frame should fit a photo to its panel. */
public enum class PhotoScale(internal val wireValue: Long) {
    /** Show the whole photo, letterboxed if needed. */
    FIT(1),

    /** Centre-crop to fill the panel. */
    CROP(2),
}
