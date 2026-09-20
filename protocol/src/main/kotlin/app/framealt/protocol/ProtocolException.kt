package app.framealt.protocol

/**
 * Something on the wire was not what the protocol defines.
 *
 * Distinct from [app.framealt.protocol.crypto.CryptoException] (a key or point was
 * degenerate), from [app.framealt.protocol.pairing.PairingException] (the friend code
 * path), and from plain [java.io.IOException] (the socket died). Keeping them apart lets
 * the UI say "the connection was interrupted" rather than "something went wrong".
 */
public class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
