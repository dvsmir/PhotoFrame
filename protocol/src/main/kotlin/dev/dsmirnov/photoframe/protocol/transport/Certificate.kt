package dev.dsmirnov.photoframe.protocol.transport

import dev.dsmirnov.photoframe.protocol.ProtocolException
import dev.dsmirnov.photoframe.protocol.crypto.Ed25519
import dev.dsmirnov.photoframe.protocol.util.ConstantTime
import dev.dsmirnov.photoframe.protocol.util.Hex

/**
 * The frame's identity certificate, carried in the `REDY` metadata.
 *
 * ```
 * cert[0:32]    issuer Ed25519 public key
 * cert[32:96]   Ed25519 signature
 * cert[96:128]  subject — must equal the frame's public key from WELC
 * ```
 *
 * The signature is over the subject alone, so the certificate says only "this issuer
 * vouches for this frame key". Trust therefore rests on [IssuerTrust]: an issuer we do
 * not recognise means we have no reason to believe the device is a Frameo frame at all.
 */
internal class FrameCertificate(
    val issuerPublicKey: ByteArray,
    val signature: ByteArray,
    val subject: ByteArray,
) {

    val issuerHex: String = Hex.encode(issuerPublicKey)

    companion object {
        const val SIZE: Int = 128

        /**
         * @throws ProtocolException if the certificate is absent, malformed, signed for a
         *   different subject, or carries an invalid signature.
         */
        fun verify(raw: ByteArray?, framePublicKey: ByteArray): FrameCertificate {
            if (raw == null) throw ProtocolException("frame sent no certificate")
            if (raw.size != SIZE) {
                throw ProtocolException("frame certificate is ${raw.size} bytes, expected $SIZE")
            }

            val issuer = raw.copyOfRange(0, 32)
            val signature = raw.copyOfRange(32, 96)
            val subject = raw.copyOfRange(96, 128)

            if (!ConstantTime.equals(subject, framePublicKey)) {
                throw ProtocolException("frame certificate was issued for a different device")
            }
            if (!Ed25519.verify(signature, issuer, subject)) {
                throw ProtocolException("frame certificate signature is invalid")
            }
            return FrameCertificate(issuer, signature, subject)
        }
    }
}

/**
 * The set of Ed25519 issuer keys that sign genuine Frameo frame certificates.
 *
 * Loaded from a resource rather than compiled in, so refreshing it is a data change. The
 * file is a flat JSON array of 64-character hex strings; scanning for hex runs avoids
 * pulling a JSON parser into the production dependencies for a list of constants.
 */
internal object IssuerTrust {

    private const val RESOURCE = "/dev/dsmirnov/photoframe/protocol/issuers.json"

    val trusted: Set<String> by lazy { load() }

    fun isTrusted(issuerHex: String): Boolean = issuerHex in trusted

    private fun load(): Set<String> {
        val stream = IssuerTrust::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("issuer trust list is missing from the build: $RESOURCE")
        val text = stream.use { it.readBytes().decodeToString() }
        val keys = Regex("[0-9a-f]{64}").findAll(text).map { it.value }.toSet()
        check(keys.size > 200) { "issuer trust list looks truncated: ${keys.size} keys" }
        return keys
    }
}
