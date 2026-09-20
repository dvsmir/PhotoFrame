package app.framealt.protocol.wire

import app.framealt.protocol.ProtocolException
import java.io.ByteArrayOutputStream

/**
 * The protobuf subset this protocol needs.
 *
 * Hand-rolled on purpose: there are no `.proto` files for Frameo's messages, the field
 * meanings were recovered by reverse engineering, and a generated-code dependency would
 * buy nothing. We keep unknown fields rather than rejecting them, because the frame's
 * firmware sends fields we have not identified yet.
 *
 * ### Signedness
 *
 * The single easiest thing to get wrong here. Media IDs, capture/receive timestamps,
 * content IDs and multipart IDs are **sint64** (ZigZag). Receipt IDs, sizes, indices,
 * enums and booleans are **plain varints**. Mixing them produces values that look
 * plausible and are wrong — negative media IDs are legal and common.
 *
 * See `Spec/00 - Initial/01 - Protocol.md` §5.3.
 */
internal enum class WireType(val id: Int) {
    VARINT(0),
    FIXED64(1),
    LENGTH_DELIMITED(2),
    FIXED32(5),
    ;

    companion object {
        fun of(id: Int): WireType = entries.firstOrNull { it.id == id }
            ?: throw ProtocolException("unsupported protobuf wire type $id")
    }
}

/** One decoded field occurrence. [number] carries varint/fixed values; [bytes] the rest. */
internal class ProtoValue(
    val type: WireType,
    val number: Long = 0,
    val bytes: ByteArray? = null,
)

/** A decoded message: field number to every occurrence, in wire order. */
internal class Fields(private val byNumber: Map<Int, List<ProtoValue>>) {

    fun has(field: Int): Boolean = byNumber[field]?.isNotEmpty() == true

    fun all(field: Int): List<ProtoValue> = byNumber[field].orEmpty()

    /** The last occurrence, matching the reference client's "last one wins" behaviour. */
    private fun last(field: Int): ProtoValue? = byNumber[field]?.lastOrNull()

    /** Plain varint. Absent fields read as 0 — the protocol treats 0 as "not set". */
    fun uint(field: Int): Long = last(field)?.number ?: 0

    /** ZigZag-decoded signed varint. */
    fun sint(field: Int): Long = decodeZigZag(uint(field))

    fun bool(field: Int): Boolean = uint(field) != 0L

    fun blobOrNull(field: Int): ByteArray? = last(field)?.bytes

    fun blob(field: Int): ByteArray = blobOrNull(field) ?: ByteArray(0)

    fun text(field: Int): String = blob(field).decodeToString()

    fun float(field: Int): Float = Float.fromBits(uint(field).toInt())

    /** Parses a length-delimited field as a nested message. Null when the field is absent. */
    fun message(field: Int): Fields? = blobOrNull(field)?.let { Proto.parse(it) }

    fun fieldNumbers(): Set<Int> = byNumber.keys
}

internal object Proto {

    // --- decoding ---------------------------------------------------------------------

    fun parse(data: ByteArray): Fields {
        val out = LinkedHashMap<Int, MutableList<ProtoValue>>()
        val reader = Reader(data)
        while (reader.hasMore) {
            val tag = reader.varint()
            val number = (tag ushr 3).toInt()
            if (number < 1) throw ProtocolException("invalid protobuf field number $number")
            val type = WireType.of((tag and 0x7L).toInt())
            val value = when (type) {
                WireType.VARINT -> ProtoValue(type, number = reader.varint())
                WireType.FIXED64 -> ProtoValue(type, number = reader.fixed64())
                WireType.FIXED32 -> ProtoValue(type, number = reader.fixed32())
                WireType.LENGTH_DELIMITED -> ProtoValue(type, bytes = reader.lengthDelimited(number))
            }
            out.getOrPut(number) { mutableListOf() }.add(value)
        }
        return Fields(out)
    }

    /** Reads a run of concatenated ZigZag varints — the packed ID lists in kinds 33/34. */
    fun parsePackedIds(data: ByteArray): List<Long> {
        val reader = Reader(data)
        val ids = mutableListOf<Long>()
        while (reader.hasMore) ids.add(decodeZigZag(reader.varint()))
        return ids
    }

    // --- encoding ---------------------------------------------------------------------

    fun uint(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, field, WireType.VARINT)
        writeVarint(out, value)
        return out.toByteArray()
    }

    fun sint(field: Int, value: Long): ByteArray = uint(field, encodeZigZag(value))

    fun bool(field: Int, value: Boolean): ByteArray = uint(field, if (value) 1 else 0)

    fun blob(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        writeTag(out, field, WireType.LENGTH_DELIMITED)
        writeVarint(out, value.size.toLong())
        out.write(value)
        return out.toByteArray()
    }

    fun text(field: Int, value: String): ByteArray = blob(field, value.toByteArray(Charsets.UTF_8))

    fun float(field: Int, value: Float): ByteArray {
        val bits = value.toRawBits()
        val out = ByteArrayOutputStream(8)
        writeTag(out, field, WireType.FIXED32)
        // protobuf fixed32 is little-endian, unlike everything on the transport layer.
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        return out.toByteArray()
    }

    /** A packed run of ZigZag IDs, wrapped as length-delimited field [field]. */
    fun packedIds(field: Int, ids: List<Long>): ByteArray {
        val packed = ByteArrayOutputStream()
        for (id in ids) writeVarint(packed, encodeZigZag(id))
        return blob(field, packed.toByteArray())
    }

    fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    // --- internals --------------------------------------------------------------------

    private fun writeTag(out: ByteArrayOutputStream, field: Int, type: WireType) {
        require(field >= 1) { "protobuf field numbers start at 1" }
        writeVarint(out, (field.toLong() shl 3) or type.id.toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val chunk = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(chunk)
                return
            }
            out.write(chunk or 0x80)
        }
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        val hasMore: Boolean get() = offset < data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (offset >= data.size) throw ProtocolException("truncated protobuf varint")
                if (shift > 63) throw ProtocolException("protobuf varint exceeds 64 bits")
                val b = data[offset++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fixed32(): Long {
            if (offset + 4 > data.size) throw ProtocolException("truncated protobuf fixed32")
            var v = 0L
            for (i in 0 until 4) v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
            offset += 4
            return v
        }

        fun fixed64(): Long {
            if (offset + 8 > data.size) throw ProtocolException("truncated protobuf fixed64")
            var v = 0L
            for (i in 0 until 8) v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
            offset += 8
            return v
        }

        fun lengthDelimited(field: Int): ByteArray {
            val length = varint()
            if (length < 0 || length > Int.MAX_VALUE.toLong() || offset + length > data.size) {
                throw ProtocolException("truncated protobuf field $field (declared $length bytes)")
            }
            val end = offset + length.toInt()
            val slice = data.copyOfRange(offset, end)
            offset = end
            return slice
        }
    }
}

internal fun encodeZigZag(value: Long): Long = (value shl 1) xor (value shr 63)

internal fun decodeZigZag(value: Long): Long = (value ushr 1) xor -(value and 1)
