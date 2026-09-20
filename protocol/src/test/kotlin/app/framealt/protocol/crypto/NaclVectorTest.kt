package app.framealt.protocol.crypto

import app.framealt.protocol.Vector
import app.framealt.protocol.Vectors
import app.framealt.protocol.util.Hex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate for the whole project.
 *
 * Every vector here was produced by tweetnacl-js, an implementation sharing no lineage
 * with our Kotlin/BouncyCastle composition (and cross-checked against OpenSSL for X25519
 * and Ed25519). Round-tripping our own output would prove nothing: a symmetric bug passes
 * that happily. See `Spec/00 - Initial/04 - Testing.md` §2.
 */
class NaclVectorTest {

    private fun eachVector(file: String, body: (Vector) -> Unit) {
        val vectors = Vectors.load(file)
        for (vector in vectors) {
            try {
                body(vector)
            } catch (failure: AssertionError) {
                throw AssertionError("$vector: ${failure.message}", failure)
            }
        }
    }

    @Test
    @DisplayName("HSalsa20 matches tweetnacl - the input-subtraction step is correct")
    fun hsalsa20() = eachVector("hsalsa20.json") { v ->
        assertContentEquals(
            v.bytes("output"),
            HSalsa20.derive(v.bytes("key"), v.bytes("input")),
        )
    }

    @Test
    @DisplayName("XSalsa20 keystream matches tweetnacl across block boundaries")
    fun xsalsa20() = eachVector("xsalsa20.json") { v ->
        assertContentEquals(
            v.bytes("output"),
            Nacl.xSalsa20Xor(v.bytes("key"), v.bytes("nonce"), v.bytes("input")),
        )
    }

    @Test
    @DisplayName("secretbox seals to the same bytes as tweetnacl")
    fun secretBoxSeal() = eachVector("secretbox.json") { v ->
        assertContentEquals(
            v.bytes("boxed"),
            Nacl.secretBoxSeal(v.bytes("key"), v.bytes("nonce"), v.bytes("message")),
        )
    }

    @Test
    @DisplayName("secretbox opens tweetnacl's output")
    fun secretBoxOpen() = eachVector("secretbox.json") { v ->
        val opened = Nacl.secretBoxOpen(v.bytes("key"), v.bytes("nonce"), v.bytes("boxed"))
        assertNotNull(opened, "authentication failed on a valid box")
        assertContentEquals(v.bytes("message"), opened)
    }

    @Test
    @DisplayName("secretbox rejects a tampered MAC, a tampered ciphertext and a wrong nonce")
    fun secretBoxRejectsTampering() = eachVector("secretbox.json") { v ->
        val key = v.bytes("key")
        val nonce = v.bytes("nonce")
        val boxed = v.bytes("boxed")

        val flippedMac = boxed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(Nacl.secretBoxOpen(key, nonce, flippedMac), "a flipped MAC bit was accepted")

        if (boxed.size > Nacl.MAC_SIZE) {
            val flippedCipher = boxed.copyOf()
                .also { it[Nacl.MAC_SIZE] = (it[Nacl.MAC_SIZE].toInt() xor 1).toByte() }
            assertNull(Nacl.secretBoxOpen(key, nonce, flippedCipher), "a flipped ciphertext bit was accepted")
        }

        val wrongNonce = nonce.copyOf().also { it[23] = (it[23].toInt() xor 1).toByte() }
        assertNull(Nacl.secretBoxOpen(key, wrongNonce, boxed), "the wrong nonce was accepted")

        val truncated = boxed.copyOfRange(0, Nacl.MAC_SIZE - 1)
        assertNull(Nacl.secretBoxOpen(key, nonce, truncated), "a truncated box was accepted")
    }

    @Test
    @DisplayName("crypto_box_beforenm matches tweetnacl")
    fun beforeNm() = eachVector("box.json") { v ->
        assertContentEquals(
            v.bytes("sharedKey"),
            Nacl.beforeNm(v.bytes("recipientPublicKey"), v.bytes("senderSecretKey")),
        )
        // The recipient must derive the same key from the other direction.
        assertContentEquals(
            v.bytes("sharedKey"),
            Nacl.beforeNm(v.bytes("senderPublicKey"), v.bytes("recipientSecretKey")),
        )
    }

    @Test
    @DisplayName("box seals to the same bytes as tweetnacl and opens from the other side")
    fun box() = eachVector("box.json") { v ->
        val sealed = Nacl.boxSeal(
            message = v.bytes("message"),
            nonce = v.bytes("nonce"),
            publicKey = v.bytes("recipientPublicKey"),
            privateKey = v.bytes("senderSecretKey"),
        )
        assertContentEquals(v.bytes("boxed"), sealed)

        val opened = Nacl.boxOpen(
            boxed = v.bytes("boxed"),
            nonce = v.bytes("nonce"),
            publicKey = v.bytes("senderPublicKey"),
            privateKey = v.bytes("recipientSecretKey"),
        )
        assertNotNull(opened, "recipient could not open the box")
        assertContentEquals(v.bytes("message"), opened)
    }

    @Test
    @DisplayName("X25519 agrees with tweetnacl and OpenSSL")
    fun x25519() = eachVector("x25519.json") { v ->
        assertContentEquals(
            v.bytes("shared"),
            Curve25519.scalarMult(v.bytes("scalar"), v.bytes("point")),
        )
        assertContentEquals(
            v.bytes("scalarMultBase"),
            Curve25519.scalarMultBase(v.bytes("scalar")),
        )
    }

    @Test
    @DisplayName("X25519 rejects small-order points instead of returning a constant")
    fun x25519RejectsSmallOrder() {
        // The canonical small-order points: every scalar maps them to zero.
        val smallOrder = listOf(
            ByteArray(32),
            ByteArray(32).also { it[0] = 1 },
        )
        val scalar = Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        for (point in smallOrder) {
            val failure = runCatching { Curve25519.scalarMult(scalar, point) }.exceptionOrNull()
            assertTrue(
                failure is CryptoException,
                "small-order point ${Hex.encode(point)} produced ${failure ?: "a usable result"}",
            )
        }
    }

    @Test
    @DisplayName("Ed25519 accepts valid signatures and rejects tampered ones")
    fun ed25519() = eachVector("ed25519.json") { v ->
        val actual = Ed25519.verify(v.bytes("signature"), v.bytes("publicKey"), v.bytes("message"))
        assertEquals(v.flag("valid"), actual)
    }

    @Test
    @DisplayName("Ed25519 returns false rather than throwing on malformed input")
    fun ed25519Malformed() {
        assertFalse(Ed25519.verify(ByteArray(0), ByteArray(32), ByteArray(4)))
        assertFalse(Ed25519.verify(ByteArray(64), ByteArray(0), ByteArray(4)))
        assertFalse(Ed25519.verify(ByteArray(63), ByteArray(32), ByteArray(4)))
        assertFalse(Ed25519.verify(ByteArray(64), ByteArray(32), ByteArray(0)))
    }
}
