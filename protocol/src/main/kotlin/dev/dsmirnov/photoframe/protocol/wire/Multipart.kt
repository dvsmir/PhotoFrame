package dev.dsmirnov.photoframe.protocol.wire

import dev.dsmirnov.photoframe.protocol.ProtocolException

/**
 * The kind-30 wrapper that carries application messages larger than a single record.
 *
 * Fields: 1 = transfer ID, 2 = total size of the wrapped envelope, 3 = zero-based part
 * index, 4 = the slice itself.
 *
 * > Quirk worth knowing: the reference client *writes* field 1 as sint64 but *reads* it
 * > as a plain varint. It works because the ID is only ever compared with itself across
 * > the parts of one transfer, so it is effectively an opaque token. We match that
 * > behaviour byte-for-byte rather than "fixing" it, because the frame is on the other
 * > end of this.
 */
internal object Multipart {

    const val KIND: Int = 30

    /** Transport ceiling for one application message. */
    const val MAX_APPLICATION_MESSAGE: Int = 16416

    /** Payload per part, leaving room for the kind-30 framing. */
    const val CHUNK: Int = 16316

    const val MAX_TOTAL: Int = 32 shl 20

    /** True when [envelope] has to be split. */
    fun isRequired(envelope: ByteArray): Boolean = envelope.size > MAX_APPLICATION_MESSAGE

    /** Splits a wrapped envelope into ready-to-send kind-30 envelopes. */
    fun split(envelope: ByteArray, transferId: Long): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0L
        while (offset < envelope.size) {
            val end = minOf(offset + CHUNK, envelope.size)
            parts.add(
                Envelopes.wrap(
                    KIND,
                    Proto.join(
                        Proto.sint(1, transferId),
                        Proto.uint(2, envelope.size.toLong()),
                        Proto.uint(3, index),
                        Proto.blob(4, envelope.copyOfRange(offset, end)),
                    ),
                ),
            )
            offset = end
            index++
        }
        return parts
    }
}

/**
 * Reassembles inbound kind-30 parts.
 *
 * Strict by design: a part that is out of order, or that changes the transfer ID or the
 * declared total, drops the connection rather than producing a subtly corrupt message.
 * One transfer at a time — the protocol has no stream multiplexing.
 */
internal class MultipartAssembler {

    private var buffer = ByteArray(0)
    private var transferId = 0L
    private var declaredSize = 0
    private var nextIndex = 0L

    /** @return the complete inner message once the final part lands, otherwise null. */
    fun accept(fields: Fields): ByteArray? {
        val id = fields.uint(1)
        val size = fields.uint(2)
        val index = fields.uint(3)

        if (size < Envelopes.HEADER_SIZE.toLong() || size > Multipart.MAX_TOTAL.toLong()) {
            throw ProtocolException("invalid multipart size $size")
        }

        if (index == 0L) {
            buffer = ByteArray(0)
            transferId = id
            declaredSize = size.toInt()
            nextIndex = 0
        }

        if (id != transferId || size.toInt() != declaredSize || index != nextIndex) {
            throw ProtocolException(
                "out-of-order multipart segment (index $index, expected $nextIndex)",
            )
        }

        buffer += fields.blob(4)
        nextIndex++

        if (buffer.size > declaredSize) {
            reset()
            throw ProtocolException("multipart transfer overflowed its announced size")
        }
        if (buffer.size < declaredSize) return null

        val complete = buffer
        reset()
        return complete
    }

    fun reset() {
        buffer = ByteArray(0)
        transferId = 0
        declaredSize = 0
        nextIndex = 0
    }
}
