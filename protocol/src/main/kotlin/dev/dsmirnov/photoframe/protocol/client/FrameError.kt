package dev.dsmirnov.photoframe.protocol.client

import dev.dsmirnov.photoframe.protocol.wire.Fields

/**
 * Error codes the frame returns inside an error submessage.
 *
 * The UI maps these to sentences; the numeric code belongs only in the diagnostic log.
 * See `Spec/00 - Initial/03 - UX.md` §7.
 */
public enum class FrameErrorCode(public val code: Int) {
    UNSPECIFIED(0),
    UNAUTHORIZED(1),
    FRAME_ERROR(2),
    BAD_REQUEST(3),
    OPERATION_FAILED(4),
    PERMISSION_REQUIRED(5),
    PHOTO_GONE(6),
    SEND_FAILED(7),
    NOT_FOUND(8),
    DECLINED(9),
    LIMIT_REACHED(12),

    /** A code this client does not know about — the frame's firmware is ahead of us. */
    UNKNOWN(-1),
    ;

    internal companion object {
        fun of(code: Long): FrameErrorCode = entries.firstOrNull { it.code.toLong() == code } ?: UNKNOWN
    }
}

/** The frame refused or failed an operation. */
public class FrameException(
    public val errorCode: FrameErrorCode,
    public val rawCode: Long,
) : Exception("frame reported ${errorCode.name} (code $rawCode)")

internal object FrameError {

    /**
     * Throws if [fields] carries an error submessage at [field].
     *
     * Error submessages are optional: absence means success, which is why every response
     * handler must call this explicitly rather than relying on a status field.
     */
    fun check(fields: Fields, field: Int) {
        val error = fields.message(field) ?: return
        val raw = error.uint(1)
        throw FrameException(FrameErrorCode.of(raw), raw)
    }
}
