package dev.dsmirnov.photoframe.protocol.util

/**
 * Comparisons that do not leak where two byte arrays first differ.
 *
 * Every MAC, proof and "expected reply" check in this protocol goes through here.
 * A plain `contentEquals` would leak the position of the first mismatch through timing.
 */
public object ConstantTime {

    /** True when [a] and [b] have the same length and contents. Runs in time proportional to the length. */
    public fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    /** True when every byte is zero. Used to reject degenerate X25519 outputs. */
    public fun isAllZero(a: ByteArray): Boolean {
        var acc = 0
        for (b in a) acc = acc or b.toInt()
        return acc == 0
    }

    /**
     * Best-effort erasure of key material. The JVM may have copied the array already,
     * so this is hygiene rather than a guarantee — but it costs nothing.
     */
    public fun wipe(a: ByteArray) {
        a.fill(0)
    }
}
