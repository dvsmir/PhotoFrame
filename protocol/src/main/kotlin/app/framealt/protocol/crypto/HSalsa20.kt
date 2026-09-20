package app.framealt.protocol.crypto

import org.bouncycastle.crypto.engines.Salsa20Engine

/**
 * HSalsa20 — the key-derivation half of NaCl's `crypto_box`.
 *
 * BouncyCastle computes this internally inside `XSalsa20Engine` but does not expose it,
 * so we build it from the public Salsa20 core.
 *
 * ### The trap
 *
 * [Salsa20Engine.salsaCore] is the *full* Salsa20 core: it runs the double-rounds and
 * then **adds the input words back in**. HSalsa20 is defined on the core *without* that
 * final addition. So we subtract the input words again for exactly the eight output
 * positions we read.
 *
 * Skip that subtraction and you get a plausible-looking 32-byte key that agrees with
 * nothing, and the failure surfaces three layers away as "handshake failed". This is the
 * single most likely place for the whole port to go wrong — which is why
 * [app.framealt.protocol.crypto] is covered by external vectors before anything else is
 * written. See `Spec/00 - Initial/02 - Architecture.md` §3.2.
 *
 * Structure follows codahale/xsalsa20poly1305 (Apache-2.0), which solves it the same way.
 */
internal object HSalsa20 {

    /** Little-endian words of the ASCII constant "expand 32-byte k". */
    private const val SIGMA_0 = 0x6170_7865
    private const val SIGMA_5 = 0x3320_646E
    private const val SIGMA_10 = 0x7962_2D32
    private const val SIGMA_15 = 0x6B20_6574

    private const val ROUNDS = 20

    /**
     * @param key 32 bytes — for `crypto_box_beforenm`, the raw X25519 shared point.
     * @param input 16 bytes — for `crypto_box_beforenm`, all zeroes.
     * @return the 32-byte derived key.
     */
    fun derive(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "HSalsa20 key must be 32 bytes, was ${key.size}" }
        require(input.size == 16) { "HSalsa20 input must be 16 bytes, was ${input.size}" }

        val state = IntArray(16)
        state[0] = SIGMA_0
        state[1] = readLeInt(key, 0)
        state[2] = readLeInt(key, 4)
        state[3] = readLeInt(key, 8)
        state[4] = readLeInt(key, 12)
        state[5] = SIGMA_5
        state[6] = readLeInt(input, 0)
        state[7] = readLeInt(input, 4)
        state[8] = readLeInt(input, 8)
        state[9] = readLeInt(input, 12)
        state[10] = SIGMA_10
        state[11] = readLeInt(key, 16)
        state[12] = readLeInt(key, 20)
        state[13] = readLeInt(key, 24)
        state[14] = readLeInt(key, 28)
        state[15] = SIGMA_15

        val core = IntArray(16)
        Salsa20Engine.salsaCore(ROUNDS, state, core)

        // Undo salsaCore's final `+= input`, for the eight words HSalsa20 actually reads.
        core[0] -= state[0]
        core[5] -= state[5]
        core[10] -= state[10]
        core[15] -= state[15]
        core[6] -= state[6]
        core[7] -= state[7]
        core[8] -= state[8]
        core[9] -= state[9]

        val out = ByteArray(32)
        writeLeInt(out, 0, core[0])
        writeLeInt(out, 4, core[5])
        writeLeInt(out, 8, core[10])
        writeLeInt(out, 12, core[15])
        writeLeInt(out, 16, core[6])
        writeLeInt(out, 20, core[7])
        writeLeInt(out, 24, core[8])
        writeLeInt(out, 28, core[9])

        state.fill(0)
        core.fill(0)
        return out
    }
}
