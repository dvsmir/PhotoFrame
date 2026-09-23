package dev.dsmirnov.photoframe.protocol.pairing

import dev.dsmirnov.photoframe.protocol.Vectors
import dev.dsmirnov.photoframe.protocol.util.Hex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PACE v1 against the 20 reference vectors.
 *
 * These come from the Go reference client and were computed independently of this
 * implementation, so agreement here means the pairing maths is byte-exact — including the
 * two details that look wrong but are not: identity keys (not ephemeral) in the password
 * hash, and the live session key used directly as an X25519 scalar.
 */
class PaceTest {

    /** Every vector in the fixture was generated with this code. */
    private val friendCode = "12 34 56 78 90"

    @Test
    @DisplayName("all 20 reference vectors produce byte-exact responses and proofs")
    fun referenceVectors() {
        val vectors = Vectors.load("pace.json")
        assertEquals(20, vectors.size, "expected the full reference set")

        for (vector in vectors) {
            val result = Pace.response(
                challenge = vector.bytes("challenge"),
                friendCode = friendCode,
                clientPublicKey = vector.bytes("client"),
                framePublicKey = vector.bytes("frame"),
                sessionKey = vector.bytes("channel"),
                scalar = vector.bytes("scalar"),
            )
            assertContentEquals(
                vector.bytes("response"),
                result.response,
                "response differs for $vector",
            )
            // The reference calls the expected reply "proof"; it is the 0x05-tagged
            // confirmation the frame must send back.
            assertContentEquals(
                vector.bytes("proof"),
                result.expectedReply,
                "expected reply differs for $vector",
            )
        }
    }

    @Test
    @DisplayName("response is tagged 0x04 and the expected reply 0x05")
    fun tagsAndLengths() {
        val vector = Vectors.load("pace.json").first()
        val result = Pace.response(
            challenge = vector.bytes("challenge"),
            friendCode = friendCode,
            clientPublicKey = vector.bytes("client"),
            framePublicKey = vector.bytes("frame"),
            sessionKey = vector.bytes("channel"),
            scalar = vector.bytes("scalar"),
        )
        assertEquals(65, result.response.size)
        assertEquals(Pace.RESPONSE_TAG, result.response[0])
        assertEquals(33, result.expectedReply.size)
        assertEquals(Pace.CONFIRM_TAG, result.expectedReply[0])
    }

    @Test
    @DisplayName("a different friend code produces a different response")
    fun wrongCodeChangesEverything() {
        val vector = Vectors.load("pace.json").first()
        val result = Pace.response(
            challenge = vector.bytes("challenge"),
            friendCode = "12 34 56 78 91",
            clientPublicKey = vector.bytes("client"),
            framePublicKey = vector.bytes("frame"),
            sessionKey = vector.bytes("channel"),
            scalar = vector.bytes("scalar"),
        )
        assertTrue(
            !result.response.contentEquals(vector.bytes("response")),
            "a wrong friend code produced the correct response",
        )
    }

    @Test
    @DisplayName("friend codes are normalised, and bad ones rejected with a typed reason")
    fun friendCodeValidation() {
        assertEquals("1234567890", Pace.normalizeFriendCode("12 34 56 78 90"))
        assertEquals("1234567890", Pace.normalizeFriendCode("12-34-56-78-90"))
        assertEquals("1234567", Pace.normalizeFriendCode("1234567"))
        assertEquals("12345678901234567890", Pace.normalizeFriendCode("12345678901234567890"))

        assertEquals(
            PairingException.Reason.CODE_LENGTH,
            assertFailsWith<PairingException> { Pace.normalizeFriendCode("123456") }.reason,
        )
        assertEquals(
            PairingException.Reason.CODE_LENGTH,
            assertFailsWith<PairingException> { Pace.normalizeFriendCode("123456789012345678901") }.reason,
        )
        assertEquals(
            PairingException.Reason.CODE_DIGITS,
            assertFailsWith<PairingException> { Pace.normalizeFriendCode("12345abc") }.reason,
        )
        assertEquals(
            PairingException.Reason.CODE_DIGITS,
            assertFailsWith<PairingException> { Pace.normalizeFriendCode("1234 567+") }.reason,
        )
    }

    @Test
    @DisplayName("malformed challenges are rejected, not silently processed")
    fun challengeValidation() {
        val vector = Vectors.load("pace.json").first()
        val valid = vector.bytes("challenge")

        fun attempt(challenge: ByteArray) = assertFailsWith<PairingException> {
            Pace.response(
                challenge = challenge,
                friendCode = friendCode,
                clientPublicKey = vector.bytes("client"),
                framePublicKey = vector.bytes("frame"),
                sessionKey = vector.bytes("channel"),
                scalar = vector.bytes("scalar"),
            )
        }.reason

        assertEquals(PairingException.Reason.MALFORMED_CHALLENGE, attempt(ByteArray(0)))
        assertEquals(PairingException.Reason.MALFORMED_CHALLENGE, attempt(valid.copyOfRange(0, 96)))
        assertEquals(PairingException.Reason.MALFORMED_CHALLENGE, attempt(valid + 0))
        assertEquals(
            PairingException.Reason.MALFORMED_CHALLENGE,
            attempt(valid.copyOf().also { it[0] = 2 }),
        )
    }

    @Test
    @DisplayName("the issuer trust list loads and is well formed")
    fun issuerList() {
        val stream = Pace::class.java.getResourceAsStream("/dev/dsmirnov/photoframe/protocol/issuers.json")
        checkNotNull(stream) { "issuers.json is missing from the protocol module's resources" }
        val text = stream.use { it.readBytes().decodeToString() }
        val keys = Regex("[0-9a-f]{64}").findAll(text).map { it.value }.toList()

        assertTrue(keys.size > 200, "expected the full issuer list, found ${keys.size}")
        assertEquals(keys.size, keys.distinct().size, "the issuer list contains duplicates")
        for (key in keys) {
            assertEquals(32, Hex.decode(key).size)
        }
    }
}
