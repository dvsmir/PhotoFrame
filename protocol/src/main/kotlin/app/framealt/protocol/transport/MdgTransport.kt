package app.framealt.protocol.transport

import app.framealt.protocol.ProtocolException
import app.framealt.protocol.crypto.CryptoRandom
import app.framealt.protocol.crypto.Curve25519
import app.framealt.protocol.crypto.Nacl
import app.framealt.protocol.crypto.Nonce
import app.framealt.protocol.crypto.readBeLong
import app.framealt.protocol.crypto.readBeShort
import app.framealt.protocol.crypto.writeBeLong
import app.framealt.protocol.crypto.writeBeShort
import app.framealt.protocol.net.FrameSocket
import app.framealt.protocol.net.FrameSocketFactory
import app.framealt.protocol.util.ConstantTime
import app.framealt.protocol.util.Hex
import java.io.EOFException
import java.io.InputStream

/** Timeouts for one connection, in milliseconds. */
public class TransportTimeouts(
    public val connectMillis: Int = 8_000,
    public val handshakeMillis: Int = 12_000,
)

/**
 * The MDG session: a CurveCP-shaped handshake followed by NaCl secretbox records.
 *
 * ```
 * C→F  🐟TELL
 * F→C  🐟WELC  framePublicKey
 * C→F  🐟HELO  ephemeralPublicKey, seq 0, box(zeros)
 * F→C  🐟COOK  nonce, box(serverEphemeral ‖ cookie)
 * C→F  🐟VOCH  cookie, seq 1, secretbox(identity ‖ vouch ‖ metadata)
 * F→C  🐟REDY  seq, secretbox(metadata incl. certificate)
 * both 🐟MESG  seq, secretbox(u16be length ‖ application message)
 * ```
 *
 * See `Spec/00 - Initial/01 - Protocol.md` §3.
 *
 * Not thread-safe: sequence numbers are per-connection state. One operation at a time,
 * serialised by the caller.
 */
internal class MdgTransport private constructor(
    private val socket: FrameSocket,
    val clientPublicKey: ByteArray,
    val framePublicKey: ByteArray,
    val sessionKey: ByteArray,
    val certificate: FrameCertificate,
    val metadata: Map<String, ByteArray>,
    private var tx: Long,
    private var rx: Long,
) : AutoCloseable {

    val framePublicKeyHex: String get() = Hex.encode(framePublicKey)

    val issuerHex: String get() = certificate.issuerHex

    /** Sends one application message. */
    fun send(message: ByteArray) {
        if (message.size > MAX_APPLICATION_MESSAGE) {
            throw ProtocolException("application message of ${message.size} bytes exceeds $MAX_APPLICATION_MESSAGE")
        }
        val payload = ByteArray(2 + message.size)
        writeBeShort(payload, 0, message.size)
        message.copyInto(payload, 2)

        val sealed = Nacl.secretBoxSeal(sessionKey, Nonce.sequenced(Nonce.CLIENT_MESSAGE, tx), payload)
        writeRecord(tag(MESG) + beLong(tx) + sealed)
        tx++
    }

    /** Receives one application message, waiting at most [timeoutMillis]. */
    fun receive(timeoutMillis: Int): ByteArray {
        socket.setReadTimeout(timeoutMillis)
        val record = readRecord()
        val (sequence, plain) = decryptRecord(sessionKey, record, MESG, Nonce.SERVER_MESSAGE)
        if (sequence <= rx) throw ProtocolException("replayed MDG record (sequence $sequence)")
        rx = sequence

        if (plain.size < 2) throw ProtocolException("truncated application message")
        val declared = readBeShort(plain, 0)
        if (declared != plain.size - 2) {
            throw ProtocolException("application message declares $declared bytes but carries ${plain.size - 2}")
        }
        return plain.copyOfRange(2, plain.size)
    }

    override fun close() {
        ConstantTime.wipe(sessionKey)
        runCatching { socket.close() }
    }

    // --- record framing ---------------------------------------------------------------

    private fun writeRecord(record: ByteArray) {
        if (record.size < MIN_RECORD || record.size > MAX_RECORD) {
            throw ProtocolException("invalid MDG record length ${record.size}")
        }
        val framed = ByteArray(2 + record.size)
        writeBeShort(framed, 0, record.size)
        record.copyInto(framed, 2)
        socket.output.write(framed)
        socket.output.flush()
    }

    private fun readRecord(): ByteArray = readRecord(socket.input)

    companion object {

        /** The 🐟 prefix every record carries, spelled out so no encoding step can change it. */
        private val MAGIC = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x90.toByte(), 0x9F.toByte())

        private const val TELL = "TELL"
        private const val WELC = "WELC"
        private const val HELO = "HELO"
        private const val COOK = "COOK"
        private const val VOCH = "VOCH"
        private const val REDY = "REDY"
        private const val MESG = "MESG"

        private const val MIN_RECORD = 8
        private const val MAX_RECORD = 65535
        const val MAX_APPLICATION_MESSAGE: Int = 16416

        private const val WELCOME_SIZE = 40
        private const val COOKIE_SIZE = 168

        /**
         * Runs the handshake and returns a live session.
         *
         * @param protocol [Metadata.PROTOCOL_SESSION] normally, [Metadata.PROTOCOL_PAIRING]
         *   while pairing.
         * @param expectedPeerHex the frame's public key from a saved pairing, or null when
         *   pairing for the first time.
         * @param expectedIssuerHex the issuer pinned at pairing time. When null, the issuer
         *   must instead appear in [trustedIssuers].
         */
        @Suppress("LongParameterList")
        fun connect(
            socketFactory: FrameSocketFactory,
            host: String,
            port: Int,
            identityPrivateKey: ByteArray,
            protocol: String,
            expectedPeerHex: String? = null,
            expectedIssuerHex: String? = null,
            trustedIssuers: Set<String> = IssuerTrust.trusted,
            timeouts: TransportTimeouts = TransportTimeouts(),
        ): MdgTransport {
            val socket = socketFactory.connect(host, port, timeouts.connectMillis)
            try {
                return handshake(
                    socket = socket,
                    identityPrivateKey = identityPrivateKey,
                    protocol = protocol,
                    expectedPeerHex = expectedPeerHex,
                    expectedIssuerHex = expectedIssuerHex,
                    trustedIssuers = trustedIssuers,
                    handshakeMillis = timeouts.handshakeMillis,
                )
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }

        @Suppress("LongParameterList")
        private fun handshake(
            socket: FrameSocket,
            identityPrivateKey: ByteArray,
            protocol: String,
            expectedPeerHex: String?,
            expectedIssuerHex: String?,
            trustedIssuers: Set<String>,
            handshakeMillis: Int,
        ): MdgTransport {
            socket.setReadTimeout(handshakeMillis)
            val clientPublicKey = Curve25519.scalarMultBase(identityPrivateKey)

            fun write(record: ByteArray) {
                if (record.size < MIN_RECORD || record.size > MAX_RECORD) {
                    throw ProtocolException("invalid MDG record length ${record.size}")
                }
                val framed = ByteArray(2 + record.size)
                writeBeShort(framed, 0, record.size)
                record.copyInto(framed, 2)
                socket.output.write(framed)
                socket.output.flush()
            }

            // 1/2 — who are you?
            write(tag(TELL))
            val welcome = readRecord(socket.input)
            if (welcome.size != WELCOME_SIZE || !startsWithTag(welcome, WELC)) {
                throw ProtocolException("invalid frame identity response")
            }
            val framePublicKey = welcome.copyOfRange(8, WELCOME_SIZE)
            if (expectedPeerHex != null && !Hex.encode(framePublicKey).equals(expectedPeerHex, ignoreCase = true)) {
                throw ProtocolException("frame identity differs from the saved pairing")
            }

            // 3 — ephemeral hello, sequence 0.
            val ephemeralPrivate = CryptoRandom.x25519PrivateKey()
            val ephemeralPublic = Curve25519.scalarMultBase(ephemeralPrivate)
            write(
                tag(HELO) + ephemeralPublic + beLong(0) +
                    Nacl.boxSeal(
                        message = ByteArray(64),
                        nonce = Nonce.sequenced(Nonce.CLIENT_HELLO, 0),
                        publicKey = framePublicKey,
                        privateKey = ephemeralPrivate,
                    ),
            )

            // 4 — cookie carrying the frame's ephemeral key.
            val cookieRecord = readRecord(socket.input)
            if (cookieRecord.size != COOKIE_SIZE || !startsWithTag(cookieRecord, COOK)) {
                throw ProtocolException("invalid cookie response")
            }
            val cookiePlain = Nacl.boxOpen(
                boxed = cookieRecord.copyOfRange(24, COOKIE_SIZE),
                nonce = Nonce.tagged(Nonce.COOKIE_TAG, cookieRecord.copyOfRange(8, 24)),
                publicKey = framePublicKey,
                privateKey = ephemeralPrivate,
            ) ?: throw ProtocolException("cookie authentication failed")
            if (cookiePlain.size != 128) throw ProtocolException("malformed cookie payload")

            val frameEphemeralPublic = cookiePlain.copyOfRange(0, 32)
            val cookie = cookiePlain.copyOfRange(32, 128)
            val sessionKey = Nacl.beforeNm(frameEphemeralPublic, ephemeralPrivate)

            // 5 — vouch for our long-term identity, sequence 1.
            val vouchNonceSuffix = CryptoRandom.bytes(16)
            val vouch = Nacl.boxSeal(
                message = ephemeralPublic,
                nonce = Nonce.tagged(Nonce.VOUCH_TAG, vouchNonceSuffix),
                publicKey = framePublicKey,
                privateKey = identityPrivateKey,
            )
            val entries = buildMap {
                put(Metadata.KEY_PROTOCOL, protocol.toByteArray(Charsets.US_ASCII))
                if (protocol == Metadata.PROTOCOL_PAIRING) {
                    put(Metadata.KEY_PACE_VERSIONS, "1".toByteArray(Charsets.US_ASCII))
                }
            }
            val body = clientPublicKey + vouchNonceSuffix + vouch + Metadata.encode(entries)
            write(
                tag(VOCH) + cookie + beLong(1) +
                    Nacl.secretBoxSeal(sessionKey, Nonce.sequenced(Nonce.CLIENT_INITIATE, 1), body),
            )

            // 6 — ready, with the frame's metadata and certificate.
            val readyRecord = readRecord(socket.input)
            val (readySequence, readyPlain) =
                decryptRecord(sessionKey, readyRecord, REDY, Nonce.SERVER_READY)
            val metadata = Metadata.decode(readyPlain)

            val certificate = FrameCertificate.verify(metadata[Metadata.KEY_CERTIFICATE], framePublicKey)
            if (expectedIssuerHex != null) {
                if (!certificate.issuerHex.equals(expectedIssuerHex, ignoreCase = true)) {
                    throw ProtocolException("the frame's certificate issuer changed since pairing")
                }
            } else if (!trustedIssuers.contains(certificate.issuerHex)) {
                throw ProtocolException("unrecognised frame certificate issuer")
            }

            socket.setReadTimeout(0)
            ConstantTime.wipe(ephemeralPrivate)

            return MdgTransport(
                socket = socket,
                clientPublicKey = clientPublicKey,
                framePublicKey = framePublicKey,
                sessionKey = sessionKey,
                certificate = certificate,
                metadata = metadata,
                tx = 2, // HELO took 0, VOCH took 1.
                rx = readySequence,
            )
        }

        private fun tag(name: String): ByteArray = MAGIC + name.toByteArray(Charsets.US_ASCII)

        private fun startsWithTag(record: ByteArray, name: String): Boolean {
            val expected = tag(name)
            if (record.size < expected.size) return false
            return ConstantTime.equals(record.copyOfRange(0, expected.size), expected)
        }

        private fun beLong(value: Long): ByteArray = ByteArray(8).also { writeBeLong(it, 0, value) }

        /** @return the record's sequence number and its decrypted contents. */
        private fun decryptRecord(
            sessionKey: ByteArray,
            record: ByteArray,
            marker: String,
            noncePrefix: String,
        ): Pair<Long, ByteArray> {
            if (record.size < 32 || !startsWithTag(record, marker)) {
                throw ProtocolException("expected a $marker record")
            }
            val sequence = readBeLong(record, 8)
            val plain = Nacl.secretBoxOpen(
                key = sessionKey,
                nonce = Nonce.sequenced(noncePrefix, sequence),
                boxed = record.copyOfRange(16, record.size),
            ) ?: throw ProtocolException("MDG authentication failed on a $marker record")
            return sequence to plain
        }

        private fun readRecord(input: InputStream): ByteArray {
            val header = readFully(input, 2)
            val size = readBeShort(header, 0)
            if (size < MIN_RECORD) throw ProtocolException("invalid MDG record length $size")
            return readFully(input, size)
        }

        private fun readFully(input: InputStream, length: Int): ByteArray {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) throw EOFException("frame closed the connection")
                read += n
            }
            return buffer
        }
    }
}
