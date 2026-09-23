package dev.dsmirnov.photoframe.protocol.pairing

import dev.dsmirnov.photoframe.protocol.crypto.CryptoRandom
import dev.dsmirnov.photoframe.protocol.crypto.Curve25519
import dev.dsmirnov.photoframe.protocol.transport.MdgTransport
import dev.dsmirnov.photoframe.protocol.transport.Metadata
import dev.dsmirnov.photoframe.protocol.util.ConstantTime

/**
 * Drives PACE v1 over a transport dialled with [Metadata.PROTOCOL_PAIRING].
 *
 * On success the frame has stored our identity public key and the caller should persist
 * the pairing, close this connection, and reconnect with [Metadata.PROTOCOL_SESSION].
 */
internal object PaceExchange {

    private const val EXCHANGE_TIMEOUT_MS = 12_000

    fun pair(transport: MdgTransport, friendCode: String, timeoutMillis: Int = EXCHANGE_TIMEOUT_MS) {
        val advertised = transport.metadata[Metadata.KEY_PACE_VERSIONS]
        if (advertised != null && !advertised.contains('1'.code.toByte())) {
            throw PairingException(
                PairingException.Reason.UNSUPPORTED_PACE_VERSION,
                "frame advertises PACE versions '${advertised.decodeToString()}', which does not include v1",
            )
        }

        val challenge = transport.receive(timeoutMillis)
        val scalar = CryptoRandom.bytes(Curve25519.KEY_SIZE)
        val result = Pace.response(
            challenge = challenge,
            friendCode = friendCode,
            clientPublicKey = transport.clientPublicKey,
            framePublicKey = transport.framePublicKey,
            sessionKey = transport.sessionKey,
            scalar = scalar,
        )

        transport.send(result.response)
        val reply = transport.receive(timeoutMillis)

        if (!ConstantTime.equals(reply, result.expectedReply)) {
            throw PairingException(
                PairingException.Reason.REJECTED,
                "pairing rejected; check the friend code on the frame",
            )
        }
        ConstantTime.wipe(scalar)
    }
}
