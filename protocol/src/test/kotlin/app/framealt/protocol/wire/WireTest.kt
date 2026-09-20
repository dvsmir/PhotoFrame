package app.framealt.protocol.wire

import app.framealt.protocol.ProtocolException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {

    // --- signedness -------------------------------------------------------------------

    @Test
    @DisplayName("sint64 uses ZigZag: sint(1, -1) encodes to 08 01")
    fun zigZagCanary() {
        assertContentEquals(byteArrayOf(0x08, 0x01), Proto.sint(1, -1))
    }

    @Test
    @DisplayName("signed values survive a round trip, including the extremes")
    fun signedRoundTrip() {
        val values = listOf(0L, 1L, -1L, 42L, -42L, 9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (value in values) {
            val fields = Proto.parse(Proto.sint(1, value))
            assertEquals(value, fields.sint(1), "sint64 round trip failed for $value")
        }
    }

    @Test
    @DisplayName("a signed media ID and an unsigned receipt in one body decode independently")
    fun signednessIsNotContagious() {
        val body = Proto.join(
            Proto.sint(1, -42),
            Proto.sint(4, 1_700_000_000_000),
            Proto.uint(16, 150),
        )
        val fields = Proto.parse(body)
        assertEquals(-42, fields.sint(1))
        assertEquals(1_700_000_000_000, fields.sint(4))
        assertEquals(150, fields.uint(16))
        // Reading the receipt as signed would silently halve it — the classic bug.
        assertEquals(75, fields.sint(16))
    }

    // --- field handling ---------------------------------------------------------------

    @Test
    @DisplayName("repeated fields keep every occurrence, and scalar reads take the last")
    fun repeatedFields() {
        val body = Proto.join(Proto.uint(1, 10), Proto.uint(1, 20), Proto.uint(1, 30))
        val fields = Proto.parse(body)
        assertEquals(3, fields.all(1).size)
        assertEquals(30, fields.uint(1))
        assertContentEquals(listOf(10L, 20L, 30L), fields.all(1).map { it.number })
    }

    @Test
    @DisplayName("unknown fields are preserved, not rejected")
    fun unknownFields() {
        val body = Proto.join(
            Proto.uint(1, 7),
            Proto.text(99, "a field we have not identified"),
            Proto.float(5, 0.25f),
        )
        val fields = Proto.parse(body)
        assertEquals(7, fields.uint(1))
        assertEquals("a field we have not identified", fields.text(99))
        assertTrue(fields.fieldNumbers().containsAll(setOf(1, 5, 99)))
    }

    @Test
    @DisplayName("absent fields read as zero / empty rather than throwing")
    fun absentFields() {
        val fields = Proto.parse(ByteArray(0))
        assertEquals(0, fields.uint(1))
        assertEquals(0, fields.sint(1))
        assertEquals(false, fields.bool(1))
        assertEquals("", fields.text(1))
        assertEquals(0, fields.blob(1).size)
        assertNull(fields.blobOrNull(1))
        assertNull(fields.message(1))
        assertTrue(!fields.has(1))
    }

    @Test
    @DisplayName("floats round trip through fixed32 little-endian")
    fun floats() {
        for (value in listOf(0f, 0.5f, 1f, -1f, 0.85f, Float.MAX_VALUE)) {
            assertEquals(value, Proto.parse(Proto.float(3, value)).float(3))
        }
        // The focus point the upload metadata always carries.
        assertContentEquals(
            byteArrayOf(0x1D, 0x00, 0x00, 0x00, 0x3F),
            Proto.float(3, 0.5f),
        )
    }

    @Test
    @DisplayName("nested messages parse through message()")
    fun nestedMessages() {
        val inner = Proto.join(Proto.uint(1, 12))
        val outer = Proto.join(Proto.uint(1, 1), Proto.blob(2, inner))
        assertEquals(12, Proto.parse(outer).message(2)?.uint(1))
    }

    @Test
    @DisplayName("packed ID lists round trip, negatives included")
    fun packedIds() {
        val ids = listOf(1L, -1L, 0L, Long.MAX_VALUE, -9_007_199_254_740_993L)
        val encoded = Proto.packedIds(1, ids)
        assertContentEquals(ids, Proto.parsePackedIds(Proto.parse(encoded).blob(1)))
    }

    // --- malformed input --------------------------------------------------------------

    @Test
    @DisplayName("malformed bodies are rejected rather than silently accepted")
    fun malformedBodies() {
        val cases = mapOf(
            "field number 0" to byteArrayOf(0x00),
            "truncated varint" to byteArrayOf(0x08, 0x80.toByte()),
            "length-delimited overrun" to byteArrayOf(0x0A, 0x05, 'a'.code.toByte()),
            "truncated fixed32" to byteArrayOf(0x0D, 0x01),
            "unsupported wire type 3" to byteArrayOf(0x0B),
            "unsupported wire type 4" to byteArrayOf(0x0C),
            "truncated fixed64" to byteArrayOf(0x09, 0x01, 0x02),
        )
        for ((name, bytes) in cases) {
            assertFailsWith<ProtocolException>("$name was accepted") { Proto.parse(bytes) }
        }
    }

    @Test
    @DisplayName("random field maps survive encode then decode")
    fun propertyRoundTrip() {
        val random = Random(20260920)
        repeat(200) {
            val field = random.nextInt(1, 17)
            val value = random.nextLong()
            assertEquals(value, Proto.parse(Proto.sint(field, value)).sint(field))

            val blob = random.nextBytes(random.nextInt(0, 64))
            assertContentEquals(blob, Proto.parse(Proto.blob(field, blob)).blob(field))
        }
    }

    // --- envelope ---------------------------------------------------------------------

    @Test
    @DisplayName("envelopes carry version 18 outbound and report the frame's version inbound")
    fun envelopeRoundTrip() {
        val body = Proto.uint(1, 99)
        val wrapped = Envelopes.wrap(kind = 31, body = body)
        assertEquals(Envelopes.HEADER_SIZE + body.size, wrapped.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 18, 0, 0, 0, 31), wrapped.copyOfRange(0, 8))

        val unwrapped = Envelopes.unwrap(wrapped)
        assertEquals(18, unwrapped.version)
        assertEquals(31, unwrapped.kind)
        assertContentEquals(body, unwrapped.body)
    }

    @Test
    @DisplayName("an envelope shorter than its header is rejected")
    fun envelopeTruncated() {
        assertFailsWith<ProtocolException> { Envelopes.unwrap(ByteArray(7)) }
        // Exactly the header with no body is valid: kind 1 and kind 31 have empty bodies.
        assertEquals(0, Envelopes.unwrap(Envelopes.wrap(1)).body.size)
    }

    // --- multipart --------------------------------------------------------------------

    @Test
    @DisplayName("messages at the size ceiling are not split; one byte over splits in two")
    fun multipartThreshold() {
        assertTrue(!Multipart.isRequired(ByteArray(Multipart.MAX_APPLICATION_MESSAGE)))
        assertTrue(Multipart.isRequired(ByteArray(Multipart.MAX_APPLICATION_MESSAGE + 1)))
        assertEquals(2, Multipart.split(ByteArray(Multipart.MAX_APPLICATION_MESSAGE + 1), 7).size)
    }

    @Test
    @DisplayName("a large message splits and reassembles to the identical bytes")
    fun multipartRoundTrip() {
        val original = Envelopes.wrap(32, Random(1).nextBytes(100_000))
        val parts = Multipart.split(original, transferId = 0x0123_4567_89ABL)
        assertEquals((original.size + Multipart.CHUNK - 1) / Multipart.CHUNK, parts.size)
        assertTrue(parts.size >= 3, "expected a multi-part transfer, got ${parts.size}")

        val assembler = MultipartAssembler()
        var result: ByteArray? = null
        for ((index, part) in parts.withIndex()) {
            val envelope = Envelopes.unwrap(part)
            assertEquals(Multipart.KIND, envelope.kind)
            val out = assembler.accept(Proto.parse(envelope.body))
            if (index < parts.lastIndex) {
                assertNull(out, "assembler completed early at part $index")
            } else {
                result = out
            }
        }
        assertContentEquals(original, result)
    }

    @Test
    @DisplayName("reassembly rejects out-of-order, mismatched and oversized parts")
    fun multipartRejectsCorruption() {
        val original = Envelopes.wrap(32, Random(2).nextBytes(40_000))
        val parts = Multipart.split(original, transferId = 99).map { Proto.parse(Envelopes.unwrap(it).body) }

        // A part out of order.
        MultipartAssembler().let { a ->
            a.accept(parts[0])
            assertFailsWith<ProtocolException> { a.accept(parts[2]) }
        }

        // The transfer ID changing mid-flight.
        MultipartAssembler().let { a ->
            a.accept(parts[0])
            val forged = Proto.parse(
                Proto.join(
                    Proto.sint(1, 12345),
                    Proto.uint(2, original.size.toLong()),
                    Proto.uint(3, 1),
                    Proto.blob(4, ByteArray(10)),
                ),
            )
            assertFailsWith<ProtocolException> { a.accept(forged) }
        }

        // A declared total outside the accepted range.
        for (size in listOf(7L, (Multipart.MAX_TOTAL + 1).toLong())) {
            val bad = Proto.parse(
                Proto.join(Proto.sint(1, 1), Proto.uint(2, size), Proto.uint(3, 0), Proto.blob(4, ByteArray(1))),
            )
            assertFailsWith<ProtocolException> { MultipartAssembler().accept(bad) }
        }

        // More data than announced.
        val overflowing = Proto.parse(
            Proto.join(Proto.sint(1, 1), Proto.uint(2, 16), Proto.uint(3, 0), Proto.blob(4, ByteArray(32))),
        )
        assertFailsWith<ProtocolException> { MultipartAssembler().accept(overflowing) }
    }
}
