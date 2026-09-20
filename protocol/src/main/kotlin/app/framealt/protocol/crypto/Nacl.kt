package app.framealt.protocol.crypto

import app.framealt.protocol.util.ConstantTime
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/**
 * NaCl `secretbox` and `box`, composed from BouncyCastle primitives.
 *
 * These are not single BouncyCastle calls — they are a documented composition:
 *
 * ```
 * secretbox(k, n, m) = Poly1305(subkey, c) ‖ c
 *     where subkey = first 32 bytes of XSalsa20(k, n)
 *           c      = m XOR the XSalsa20 keystream from byte 32 onwards
 *
 * box(m, n, pk, sk) = secretbox(m, n, beforenm(pk, sk))
 * beforenm(pk, sk)  = HSalsa20(X25519(sk, pk), zeros(16))
 * ```
 *
 * Output is NaCl's "combined" form: the 16-byte MAC followed by the ciphertext.
 *
 * Correctness here is established by vectors from an *independent* implementation, never
 * by round-tripping our own output — a symmetric bug would pass a round-trip happily.
 * See `Spec/00 - Initial/04 - Testing.md` §2.
 */
internal object Nacl {

    const val KEY_SIZE: Int = 32
    const val NONCE_SIZE: Int = 24
    const val MAC_SIZE: Int = 16

    private val ZERO_16 = ByteArray(16)

    // --- secretbox ----------------------------------------------------------------

    fun secretBoxSeal(key: ByteArray, nonce: ByteArray, message: ByteArray): ByteArray {
        val engine = engine(key, nonce)
        val subKey = keystream(engine, KEY_SIZE)

        val out = ByteArray(MAC_SIZE + message.size)
        engine.processBytes(message, 0, message.size, out, MAC_SIZE)
        mac(subKey, out, MAC_SIZE, message.size).copyInto(out, 0)

        ConstantTime.wipe(subKey)
        return out
    }

    /** Returns null when authentication fails. Never throws for a bad MAC. */
    fun secretBoxOpen(key: ByteArray, nonce: ByteArray, boxed: ByteArray): ByteArray? {
        if (boxed.size < MAC_SIZE) return null
        val engine = engine(key, nonce)
        val subKey = keystream(engine, KEY_SIZE)

        val cipherLength = boxed.size - MAC_SIZE
        val expected = mac(subKey, boxed, MAC_SIZE, cipherLength)
        val presented = boxed.copyOfRange(0, MAC_SIZE)
        ConstantTime.wipe(subKey)
        if (!ConstantTime.equals(expected, presented)) return null

        val plain = ByteArray(cipherLength)
        engine.processBytes(boxed, MAC_SIZE, cipherLength, plain, 0)
        return plain
    }

    // --- box ----------------------------------------------------------------------

    /** `crypto_box_beforenm` — the shared key for a public/private key pair. */
    fun beforeNm(publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        val point = Curve25519.scalarMult(privateKey, publicKey)
        val shared = HSalsa20.derive(point, ZERO_16)
        ConstantTime.wipe(point)
        return shared
    }

    fun boxSeal(
        message: ByteArray,
        nonce: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        val shared = beforeNm(publicKey, privateKey)
        try {
            return secretBoxSeal(shared, nonce, message)
        } finally {
            ConstantTime.wipe(shared)
        }
    }

    fun boxOpen(
        boxed: ByteArray,
        nonce: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? {
        val shared = beforeNm(publicKey, privateKey)
        try {
            return secretBoxOpen(shared, nonce, boxed)
        } finally {
            ConstantTime.wipe(shared)
        }
    }

    // --- raw XSalsa20 stream (PACE) -------------------------------------------------

    /** XSalsa20 keystream XOR, matching Go's `salsa20.XORKeyStream` with a 24-byte nonce. */
    fun xSalsa20Xor(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = engine(key, nonce)
        val out = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, out, 0)
        return out
    }

    // --- internals ------------------------------------------------------------------

    private fun engine(key: ByteArray, nonce: ByteArray): XSalsa20Engine {
        require(key.size == KEY_SIZE) { "key must be 32 bytes, was ${key.size}" }
        require(nonce.size == NONCE_SIZE) { "nonce must be 24 bytes, was ${nonce.size}" }
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return engine
    }

    private fun keystream(engine: XSalsa20Engine, length: Int): ByteArray {
        val out = ByteArray(length)
        engine.processBytes(ByteArray(length), 0, length, out, 0)
        return out
    }

    private fun mac(subKey: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val poly = Poly1305()
        poly.init(KeyParameter(clampPoly1305Key(subKey)))
        poly.update(data, offset, length)
        val tag = ByteArray(MAC_SIZE)
        poly.doFinal(tag, 0)
        return tag
    }

    /**
     * Clamp the `r` half of the Poly1305 key before handing it to BouncyCastle.
     *
     * Poly1305 clamps `r` internally as part of its own definition, so pre-clamping
     * changes nothing cryptographically — it is idempotent bit masking. It exists because
     * older BouncyCastle releases *validated* that `r` was already clamped and threw
     * otherwise, instead of masking. Doing it here makes the behaviour identical across
     * BouncyCastle versions.
     */
    private fun clampPoly1305Key(subKey: ByteArray): ByteArray {
        val k = subKey.copyOf()
        k[3] = (k[3].toInt() and 0x0F).toByte()
        k[7] = (k[7].toInt() and 0x0F).toByte()
        k[11] = (k[11].toInt() and 0x0F).toByte()
        k[15] = (k[15].toInt() and 0x0F).toByte()
        k[4] = (k[4].toInt() and 0xFC).toByte()
        k[8] = (k[8].toInt() and 0xFC).toByte()
        k[12] = (k[12].toInt() and 0xFC).toByte()
        return k
    }
}
