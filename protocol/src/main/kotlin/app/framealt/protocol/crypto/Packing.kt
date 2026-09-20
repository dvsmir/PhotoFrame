package app.framealt.protocol.crypto

/**
 * Integer packing helpers.
 *
 * The rule for this codebase: the MDG transport is big-endian everywhere, Salsa20
 * internals are little-endian everywhere. Keeping the two in separate, explicitly named
 * functions is the cheapest way to stop them being mixed up.
 */

internal fun readLeInt(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)

internal fun writeLeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
}

internal fun writeBeLong(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}

internal fun readBeLong(source: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) {
        v = (v shl 8) or (source[offset + i].toLong() and 0xFF)
    }
    return v
}

internal fun writeBeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

internal fun readBeInt(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xFF) shl 24) or
        ((source[offset + 1].toInt() and 0xFF) shl 16) or
        ((source[offset + 2].toInt() and 0xFF) shl 8) or
        (source[offset + 3].toInt() and 0xFF)

internal fun readBeShort(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

internal fun writeBeShort(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 8).toByte()
    target[offset + 1] = value.toByte()
}
