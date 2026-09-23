package dev.dsmirnov.photoframe.protocol.util

/** Lowercase hex, because peer IDs and issuer keys travel as hex everywhere in this protocol. */
public object Hex {

    private const val DIGITS = "0123456789abcdef"

    public fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    public fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[i * 2])
            val lo = digit(text[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
