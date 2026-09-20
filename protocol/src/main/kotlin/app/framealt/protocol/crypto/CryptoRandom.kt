package app.framealt.protocol.crypto

import java.security.SecureRandom

/**
 * The single source of randomness for the protocol layer.
 *
 * Tests substitute a deterministic source through [override] so that handshake and
 * upload flows can be asserted byte-for-byte. Production code never calls [override].
 */
internal object CryptoRandom {

    /** Supplies [length] random bytes. */
    fun interface Source {
        fun nextBytes(length: Int): ByteArray
    }

    private val secure = Source { length ->
        ByteArray(length).also { RANDOM.nextBytes(it) }
    }

    private val RANDOM = SecureRandom()

    @Volatile
    private var source: Source = secure

    fun bytes(length: Int): ByteArray = source.nextBytes(length)

    /**
     * An X25519 private key with the standard clamping applied.
     *
     * X25519 clamps on use as well, so this is belt-and-braces — but a stored key that is
     * already clamped means the public key derived from it is stable no matter which
     * implementation later reads it.
     */
    fun x25519PrivateKey(): ByteArray {
        val key = bytes(Curve25519.KEY_SIZE)
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127).toByte()
        key[31] = (key[31].toInt() or 64).toByte()
        return key
    }

    /**
     * A random identifier in `[1, 2^63 - 1]`.
     *
     * Media IDs, receipt IDs and multipart IDs all use this shape: the reference masks off
     * the sign bit and forces the low bit, so the value is always positive and non-zero —
     * which matters because zero is treated as "absent" on the wire.
     */
    fun id63(): Long {
        val b = bytes(8)
        return (readBeLong(b, 0) and 0x7FFF_FFFF_FFFF_FFFFL) or 1L
    }

    /** Test hook. Returns a handle that restores the previous source. */
    fun override(replacement: Source): AutoCloseable {
        val previous = source
        source = replacement
        return AutoCloseable { source = previous }
    }
}
