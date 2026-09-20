package app.framealt.protocol.crypto

/**
 * The two nonce shapes this protocol uses. Both are 24 bytes; they differ in how the
 * bytes after the ASCII tag are filled.
 */
internal object Nonce {

    const val SIZE: Int = 24

    /**
     * Session nonce: a 16-byte ASCII prefix followed by the big-endian sequence number.
     *
     * Every prefix in this protocol is exactly 16 characters
     * (`CurveCP-client-H`, `CurveCP-client-I`, `CurveCP-client-M`, `CurveCP-server-R`,
     * `CurveCP-server-M`). Shorter prefixes are zero-padded, matching the reference.
     */
    fun sequenced(prefix: String, sequence: Long): ByteArray {
        val bytes = prefix.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 16) { "nonce prefix must be at most 16 bytes, was ${bytes.size}" }
        val nonce = ByteArray(SIZE)
        bytes.copyInto(nonce, 0)
        writeBeLong(nonce, 16, sequence)
        return nonce
    }

    /**
     * Handshake box nonce: an 8-byte ASCII tag (`CurveCPK`, `CurveCPV`) followed by
     * 16 bytes carried in the record itself.
     */
    fun tagged(tag: String, suffix: ByteArray): ByteArray {
        val bytes = tag.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 8) { "nonce tag must be at most 8 bytes, was ${bytes.size}" }
        require(suffix.size == 16) { "nonce suffix must be 16 bytes, was ${suffix.size}" }
        val nonce = ByteArray(SIZE)
        bytes.copyInto(nonce, 0)
        suffix.copyInto(nonce, 8)
        return nonce
    }

    const val CLIENT_HELLO: String = "CurveCP-client-H"
    const val CLIENT_INITIATE: String = "CurveCP-client-I"
    const val CLIENT_MESSAGE: String = "CurveCP-client-M"
    const val SERVER_READY: String = "CurveCP-server-R"
    const val SERVER_MESSAGE: String = "CurveCP-server-M"
    const val COOKIE_TAG: String = "CurveCPK"
    const val VOUCH_TAG: String = "CurveCPV"
}
