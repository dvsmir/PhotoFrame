package dev.dsmirnov.photoframe.protocol.pairing

import dev.dsmirnov.photoframe.protocol.crypto.Curve25519
import dev.dsmirnov.photoframe.protocol.crypto.Digest
import dev.dsmirnov.photoframe.protocol.crypto.Nacl

/**
 * PACE v1 — turning the friend code shown on the frame into a trusted long-term pairing.
 *
 * The maths lives here as a pure function so it can be driven by the reference vectors
 * with no sockets involved. The connection half is in [PaceExchange] (Phase 1).
 *
 * See `Spec/00 - Initial/01 - Protocol.md` §4.
 */
internal object Pace {

    const val CHALLENGE_SIZE: Int = 97
    const val CHALLENGE_TAG: Byte = 3
    const val RESPONSE_TAG: Byte = 4
    const val CONFIRM_TAG: Byte = 5

    private const val MIN_CODE_LENGTH = 7
    private const val MAX_CODE_LENGTH = 20

    /** The X25519 base point, u = 9. */
    private val BASE_POINT = ByteArray(Curve25519.KEY_SIZE).also { it[0] = 9 }

    /**
     * Strips the formatting the frame adds for readability.
     *
     * The frame displays codes like `12 34 56 78 90`; users also type hyphens. Both are
     * cosmetic.
     */
    fun normalizeFriendCode(raw: String): String {
        val code = raw.filterNot { it == ' ' || it == '-' }
        if (code.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH) {
            throw PairingException(
                PairingException.Reason.CODE_LENGTH,
                "friend code must contain $MIN_CODE_LENGTH-$MAX_CODE_LENGTH digits, got ${code.length}",
            )
        }
        if (!code.all { it in '0'..'9' }) {
            throw PairingException(PairingException.Reason.CODE_DIGITS, "friend code must contain digits only")
        }
        return code
    }

    /**
     * Computes our response to the frame's challenge, and the reply we must see back.
     *
     * Two details look like mistakes and are not:
     *  - [clientPublicKey] and [framePublicKey] are the **identity** keys, not the
     *    connection's ephemeral ones.
     *  - [sessionKey] is the live MDG session key used directly as an X25519 scalar. That
     *    is the channel binding that stops a relay attack, so it must be the real session
     *    key and not a fresh one.
     *
     * @param scalar 32 fresh random bytes, unique per pairing attempt.
     */
    fun response(
        challenge: ByteArray,
        friendCode: String,
        clientPublicKey: ByteArray,
        framePublicKey: ByteArray,
        sessionKey: ByteArray,
        scalar: ByteArray,
    ): PaceResponse {
        val code = normalizeFriendCode(friendCode)

        if (challenge.size != CHALLENGE_SIZE || challenge[0] != CHALLENGE_TAG) {
            throw PairingException(
                PairingException.Reason.MALFORMED_CHALLENGE,
                "expected a $CHALLENGE_SIZE-byte challenge tagged $CHALLENGE_TAG, " +
                    "got ${challenge.size} bytes tagged ${challenge.firstOrNull()}",
            )
        }
        require(clientPublicKey.size == Curve25519.KEY_SIZE) { "client public key must be 32 bytes" }
        require(framePublicKey.size == Curve25519.KEY_SIZE) { "frame public key must be 32 bytes" }
        require(sessionKey.size == Curve25519.KEY_SIZE) { "session key must be 32 bytes" }
        require(scalar.size == Curve25519.KEY_SIZE) { "scalar must be 32 bytes" }

        val framePoint = challenge.copyOfRange(1, 33)
        val salt = challenge.copyOfRange(33, 65)
        val saltNonce = challenge.copyOfRange(33, 57)
        val encryptedMapping = challenge.copyOfRange(65, 97)

        val password = Digest.sha512(code.toByteArray(Charsets.US_ASCII), clientPublicKey, framePublicKey)
        val stream = Digest.sha512(password, salt)
        val mappingKey = stream.copyOfRange(0, 32)

        val mapping = Nacl.xSalsa20Xor(mappingKey, saltNonce, encryptedMapping)

        val base = Curve25519.scalarMult(sessionKey, BASE_POINT)
        val mapped = Curve25519.scalarMult(mapping, base)
        val public = Curve25519.scalarMult(scalar, mapped)
        val shared = Curve25519.scalarMult(scalar, framePoint)

        val proof = Digest.sha512(Digest.sha512(framePoint), shared)
        val expected = Digest.sha512(Digest.sha512(public), shared)

        return PaceResponse(
            response = byteArrayOf(RESPONSE_TAG) + public + proof.copyOfRange(0, 32),
            expectedReply = byteArrayOf(CONFIRM_TAG) + expected.copyOfRange(0, 32),
        )
    }
}

/** What we send to the frame, and the exact bytes it must send back. */
internal class PaceResponse(
    val response: ByteArray,
    val expectedReply: ByteArray,
)

/**
 * A pairing failure with a typed reason, so the UI can choose its wording without
 * matching on message strings.
 */
public class PairingException(
    public val reason: Reason,
    message: String,
) : Exception(message) {

    public enum class Reason {
        /** The friend code is too short or too long. */
        CODE_LENGTH,

        /** The friend code contains something other than digits. */
        CODE_DIGITS,

        /** The frame's challenge was not the shape PACE v1 defines. */
        MALFORMED_CHALLENGE,

        /** The frame's confirmation did not match: wrong or expired code. */
        REJECTED,

        /** The frame advertised PACE versions that do not include v1. */
        UNSUPPORTED_PACE_VERSION,
    }
}
