package app.framealt.protocol.wire

import app.framealt.protocol.ProtocolException
import app.framealt.protocol.crypto.readBeInt
import app.framealt.protocol.crypto.writeBeInt

/** An application message: protocol version, message kind, and the protobuf body. */
internal class Envelope(
    val version: Int,
    val kind: Int,
    val body: ByteArray,
)

internal object Envelopes {

    /** The version we always write. The frame's own version is read off inbound envelopes. */
    const val CLIENT_VERSION: Int = 18

    const val HEADER_SIZE: Int = 8

    /** The frame's media gallery needs at least this protocol version. */
    const val MIN_GALLERY_VERSION: Int = 13

    /** Below this version the frame only accepts small upload chunks. */
    const val SMALL_CHUNK_VERSION: Int = 4

    fun wrap(kind: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(HEADER_SIZE + body.size)
        writeBeInt(out, 0, CLIENT_VERSION)
        writeBeInt(out, 4, kind)
        body.copyInto(out, HEADER_SIZE)
        return out
    }

    fun unwrap(message: ByteArray): Envelope {
        if (message.size < HEADER_SIZE) {
            throw ProtocolException("truncated Frameo message (${message.size} bytes)")
        }
        return Envelope(
            version = readBeInt(message, 0),
            kind = readBeInt(message, 4),
            body = message.copyOfRange(HEADER_SIZE, message.size),
        )
    }
}
