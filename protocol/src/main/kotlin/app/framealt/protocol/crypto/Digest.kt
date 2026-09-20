package app.framealt.protocol.crypto

import java.security.MessageDigest

/**
 * Hashing over a concatenation of parts.
 *
 * The protocol hashes joined byte runs constantly (`SHA512(code ‖ clientPub ‖ framePub)`
 * and friends). Taking varargs keeps the call sites reading like the specification and
 * avoids a trail of intermediate arrays holding key material.
 */
internal object Digest {

    fun sha512(vararg parts: ByteArray): ByteArray = digest("SHA-512", parts)

    fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)

    private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        for (part in parts) md.update(part)
        return md.digest()
    }
}
