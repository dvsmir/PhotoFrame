package dev.dsmirnov.photoframe.protocol.transport

import dev.dsmirnov.photoframe.protocol.ProtocolException
import dev.dsmirnov.photoframe.protocol.crypto.readBeShort
import dev.dsmirnov.photoframe.protocol.crypto.writeBeShort
import java.io.ByteArrayOutputStream

/**
 * The handshake metadata map: a length-prefixed list of key/value pairs exchanged inside
 * the `VOCH` and `REDY` records.
 *
 * ```
 * byte   entryCount
 * repeat entryCount times:
 *   byte    keyLength      (> 0)
 *   bytes   key            (ASCII)
 *   u16be   valueLength
 *   bytes   value
 * ```
 */
internal object Metadata {

    /** Identifies the application protocol for a normal session. */
    const val PROTOCOL_SESSION: String = "framedump_local"

    /** Identifies a pairing session. */
    const val PROTOCOL_PAIRING: String = "<pairing>"

    const val KEY_PROTOCOL: String = "protocol"
    const val KEY_PACE_VERSIONS: String = "pace_versions"
    const val KEY_CERTIFICATE: String = "certificate"

    fun encode(entries: Map<String, ByteArray>): ByteArray {
        require(entries.size <= 255) { "metadata map holds at most 255 entries" }
        val out = ByteArrayOutputStream()
        out.write(entries.size)
        for ((key, value) in entries) {
            val keyBytes = key.toByteArray(Charsets.US_ASCII)
            require(keyBytes.isNotEmpty() && keyBytes.size <= 255) { "invalid metadata key '$key'" }
            require(value.size <= 0xFFFF) { "metadata value for '$key' is too long" }
            out.write(keyBytes.size)
            out.write(keyBytes)
            val length = ByteArray(2)
            writeBeShort(length, 0, value.size)
            out.write(length)
            out.write(value)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<String, ByteArray> {
        if (data.isEmpty()) throw ProtocolException("missing handshake metadata")
        val count = data[0].toInt() and 0xFF
        var offset = 1
        val out = LinkedHashMap<String, ByteArray>(count)

        repeat(count) {
            if (offset >= data.size) throw ProtocolException("truncated handshake metadata")
            val keyLength = data[offset].toInt() and 0xFF
            offset++
            if (keyLength == 0 || offset + keyLength + 2 > data.size) {
                throw ProtocolException("truncated handshake metadata")
            }
            val key = String(data, offset, keyLength, Charsets.US_ASCII)
            offset += keyLength
            val valueLength = readBeShort(data, offset)
            offset += 2
            if (offset + valueLength > data.size) throw ProtocolException("truncated handshake metadata")
            if (out.containsKey(key)) throw ProtocolException("duplicate handshake metadata key '$key'")
            out[key] = data.copyOfRange(offset, offset + valueLength)
            offset += valueLength
        }

        if (offset != data.size) throw ProtocolException("trailing handshake metadata")
        return out
    }
}
