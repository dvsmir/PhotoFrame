package app.framealt.protocol.crypto

import app.framealt.protocol.util.ConstantTime
import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519
import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519

/** Raised when an X25519 operation produces a degenerate (all-zero) result. */
public class CryptoException(message: String) : Exception(message)

/**
 * X25519 scalar multiplication.
 *
 * PACE multiplies by points that are *not* the base point, so both variants are needed.
 * Note that X25519 clamps the scalar internally, which is why PACE's derived scalars are
 * passed through unmodified — the reference implementation does not pre-clamp them and
 * neither do we.
 */
internal object Curve25519 {

    const val KEY_SIZE: Int = 32

    fun scalarMultBase(scalar: ByteArray): ByteArray {
        require(scalar.size == KEY_SIZE) { "scalar must be 32 bytes, was ${scalar.size}" }
        val out = ByteArray(KEY_SIZE)
        BcX25519.scalarMultBase(scalar, 0, out, 0)
        rejectAllZero(out)
        return out
    }

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == KEY_SIZE) { "scalar must be 32 bytes, was ${scalar.size}" }
        require(point.size == KEY_SIZE) { "point must be 32 bytes, was ${point.size}" }
        val out = ByteArray(KEY_SIZE)
        BcX25519.scalarMult(scalar, 0, point, 0, out, 0)
        rejectAllZero(out)
        return out
    }

    /**
     * An all-zero result means the input was a small-order point, so the "shared secret"
     * would be a constant every peer could compute. The Go reference rejects this too.
     */
    private fun rejectAllZero(out: ByteArray) {
        if (ConstantTime.isAllZero(out)) {
            throw CryptoException("X25519 produced an all-zero result (small-order point)")
        }
    }
}

/** Ed25519 signature verification, used only for the frame's certificate. */
internal object Ed25519 {

    const val PUBLIC_KEY_SIZE: Int = 32
    const val SIGNATURE_SIZE: Int = 64

    /** Never throws: any malformed input is simply an invalid signature. */
    fun verify(signature: ByteArray, publicKey: ByteArray, message: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE || publicKey.size != PUBLIC_KEY_SIZE) return false
        return try {
            BcEd25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (_: RuntimeException) {
            false
        }
    }
}
