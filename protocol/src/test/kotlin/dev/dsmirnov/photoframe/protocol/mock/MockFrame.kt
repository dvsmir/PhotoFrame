package dev.dsmirnov.photoframe.protocol.mock

import dev.dsmirnov.photoframe.protocol.client.FrameErrorCode
import dev.dsmirnov.photoframe.protocol.client.FramePermissions
import dev.dsmirnov.photoframe.protocol.client.Kind
import dev.dsmirnov.photoframe.protocol.client.MediaItem
import dev.dsmirnov.photoframe.protocol.client.MediaType
import dev.dsmirnov.photoframe.protocol.crypto.CryptoRandom
import dev.dsmirnov.photoframe.protocol.crypto.Curve25519
import dev.dsmirnov.photoframe.protocol.crypto.Digest
import dev.dsmirnov.photoframe.protocol.crypto.Nacl
import dev.dsmirnov.photoframe.protocol.crypto.Nonce
import dev.dsmirnov.photoframe.protocol.crypto.readBeLong
import dev.dsmirnov.photoframe.protocol.crypto.readBeShort
import dev.dsmirnov.photoframe.protocol.crypto.writeBeInt
import dev.dsmirnov.photoframe.protocol.crypto.writeBeLong
import dev.dsmirnov.photoframe.protocol.crypto.writeBeShort
import dev.dsmirnov.photoframe.protocol.pairing.Pace
import dev.dsmirnov.photoframe.protocol.transport.Metadata
import dev.dsmirnov.photoframe.protocol.util.ConstantTime
import dev.dsmirnov.photoframe.protocol.util.Hex
import dev.dsmirnov.photoframe.protocol.wire.Envelopes
import dev.dsmirnov.photoframe.protocol.wire.Fields
import dev.dsmirnov.photoframe.protocol.wire.Multipart
import dev.dsmirnov.photoframe.protocol.wire.MultipartAssembler
import dev.dsmirnov.photoframe.protocol.wire.Proto
import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The frame's side of the protocol, over real loopback TCP.
 *
 * This exercises the handshake, the record framing, sequence discipline, the message state
 * machines, chunk boundaries and the multipart wrapper — everything the client does *with*
 * the protocol.
 *
 * **What it deliberately does not prove:** cryptographic correctness. The mock uses the
 * same [Nacl] code as the client, so a symmetric bug would sail through. That is covered
 * by `NaclVectorTest` (external vectors) and `PaceTest` (reference vectors), which is why
 * those run first. Nor does it prove the PACE *layout* is right — only that our two sides
 * agree on it; the 20 reference vectors pin the layout.
 */
internal class MockFrame(
    var frameName: String = "Mock frame",
    var placement: String = "Test bench",
    var width: Int = 1280,
    var height: Int = 800,
    var protocolVersion: Int = 18,
    var permissions: FramePermissions = FramePermissions(view = true),
    var friendCode: String = "1234567890",
) : AutoCloseable {

    // --- identity ---------------------------------------------------------------------

    private val identityPrivateKey = CryptoRandom.x25519PrivateKey()
    val identityPublicKey: ByteArray = Curve25519.scalarMultBase(identityPrivateKey)
    val peerHex: String = Hex.encode(identityPublicKey)

    private val issuerPrivateKey = CryptoRandom.bytes(32)
    private val issuerPublicKey = ByteArray(32).also {
        BcEd25519.generatePublicKey(issuerPrivateKey, 0, it, 0)
    }
    val issuerHex: String = Hex.encode(issuerPublicKey)
    val trustedIssuers: Set<String> = setOf(issuerHex)

    /** Overrides the certificate the frame presents. Used to test rejection paths. */
    var certificateOverride: ByteArray? = null

    /**
     * A certificate that is correct in every way except one flipped signature bit, so the
     * rejection can only come from the Ed25519 check.
     */
    fun certificateWithCorruptSignature(): ByteArray {
        val signature = ByteArray(64)
        BcEd25519.sign(issuerPrivateKey, 0, identityPublicKey, 0, identityPublicKey.size, signature, 0)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        return issuerPublicKey + signature + identityPublicKey
    }

    /** What the frame advertises in `pace_versions`. Set to something else to test the guard. */
    @Volatile var paceVersions: String = "1"

    // --- observable state ---------------------------------------------------------------

    val uploads: MutableList<ReceivedUpload> = CopyOnWriteArrayList()
    val clientNames: MutableList<String> = CopyOnWriteArrayList()
    val permissionRequests: MutableList<Long> = CopyOnWriteArrayList()
    val uploadChunkSizes: MutableList<Int> = CopyOnWriteArrayList()

    @Volatile var media: List<MediaItem> = emptyList()

    @Volatile var mediaBytes: ByteArray = ByteArray(0)

    @Volatile var mediaCaption: String = ""

    /** When set, the next upload receipt carries this error instead of success. */
    @Volatile var uploadReceiptError: FrameErrorCode? = null

    /** When set, list responses carry this error. */
    @Volatile var listError: FrameErrorCode? = null

    /** Every kind 33/34 request received, as (kind, IDs), in order. */
    val manageRequests: MutableList<Pair<Int, Set<Long>>> = CopyOnWriteArrayList()

    /** IDs asked to be shown now (kind 35), in order. */
    val displayedNow: MutableList<Long> = CopyOnWriteArrayList()

    /** When set, the next kind 33/34 receipt carries this error instead of success. */
    @Volatile var manageError: FrameErrorCode? = null

    /** When true, a permission request immediately flips the view bit. */
    @Volatile var grantViewOnRequest: Boolean = false

    /** When true, the frame opens with an unsolicited kind-1 asking who the client is. */
    @Volatile var askForClientName: Boolean = true

    /**
     * When true, the frame re-sends its next *application* record once, to exercise replay
     * rejection.
     *
     * Deliberately scoped to `MESG`: a test can only arm this after the handshake has
     * returned, and the frame thread may still be inside the `REDY` write at that moment.
     * Letting it replay `REDY` would make the test race against the handshake and fail with
     * a tag mismatch instead of the replay rejection it is actually checking.
     */
    @Volatile var replayNextRecord: Boolean = false

    /** Whatever went wrong on the frame thread, for assertions after a client-side failure. */
    @Volatile var failure: Throwable? = null

    val pairingSucceeded: CountDownLatch = CountDownLatch(1)

    private val clientNameReceived = CountDownLatch(1)

    /**
     * Waits for the client's unprompted kind-3 reply.
     *
     * Needed because that reply is fire-and-forget: the client sends it and moves on, so a
     * test that closed the connection immediately afterwards would race the frame's read.
     */
    fun awaitClientName(seconds: Long = 5): Boolean = clientNameReceived.await(seconds, TimeUnit.SECONDS)

    /** Set when the client's PACE proof did not verify — i.e. the friend code was wrong. */
    @Volatile var pairingRejected: Boolean = false

    // --- lifecycle ----------------------------------------------------------------------

    private val server = ServerSocket(0, 4, InetAddress.getByName(HOST))
    private val sessions = CopyOnWriteArrayList<Socket>()

    @Volatile private var running = true

    val host: String = HOST
    val port: Int = server.localPort

    fun start(): MockFrame {
        Thread({ acceptLoop() }, "mock-frame").apply { isDaemon = true }.start()
        return this
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            sessions.add(socket)
            Thread({
                try {
                    socket.use { Session(it).serve() }
                } catch (_: EOFException) {
                    // The client hung up; normal at the end of a test.
                } catch (thrown: Throwable) {
                    failure = thrown
                }
            }, "mock-frame-session").apply { isDaemon = true }.start()
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        sessions.forEach { runCatching { it.close() } }
    }

    /** Fails the calling test if the frame thread recorded a problem. */
    fun assertHealthy() {
        failure?.let { throw AssertionError("mock frame failed: ${it.message}", it) }
    }

    fun awaitPairing(seconds: Long = 5): Boolean = pairingSucceeded.await(seconds, TimeUnit.SECONDS)

    // --- one connection -------------------------------------------------------------------

    private inner class Session(socket: Socket) {

        private val input: InputStream = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val assembler = MultipartAssembler()

        private lateinit var sessionKey: ByteArray
        private lateinit var clientPublicKey: ByteArray
        private var protocolName = ""
        private var tx = 1L
        private var rx = 0L

        private var pendingUpload: PendingUpload? = null

        fun serve() {
            handshake()
            if (protocolName == Metadata.PROTOCOL_PAIRING) {
                runPairing()
                return
            }
            if (askForClientName) sendEnvelope(Kind.REQUEST_INFO)
            while (true) {
                val (kind, fields) = receiveEnvelope()
                handle(kind, fields)
            }
        }

        // --- handshake ---------------------------------------------------------------

        private fun handshake() {
            val tell = readRecord()
            check(tell.size == 8 && hasTag(tell, "TELL")) { "expected TELL, got ${tell.size} bytes" }
            writeRecord(tag("WELC") + identityPublicKey)

            val hello = readRecord()
            check(hello.size == 128 && hasTag(hello, "HELO")) { "expected a 128-byte HELO" }
            val clientEphemeral = hello.copyOfRange(8, 40)
            val helloSequence = readBeLong(hello, 40)
            check(helloSequence == 0L) { "HELO must use sequence 0, got $helloSequence" }
            val helloPlain = Nacl.boxOpen(
                boxed = hello.copyOfRange(48, 128),
                nonce = Nonce.sequenced(Nonce.CLIENT_HELLO, helloSequence),
                publicKey = clientEphemeral,
                privateKey = identityPrivateKey,
            )
            checkNotNull(helloPlain) { "HELO box failed to authenticate" }
            check(helloPlain.size == 64 && ConstantTime.isAllZero(helloPlain)) { "HELO payload must be 64 zero bytes" }

            val serverEphemeralPrivate = CryptoRandom.x25519PrivateKey()
            val serverEphemeralPublic = Curve25519.scalarMultBase(serverEphemeralPrivate)
            val cookie = CryptoRandom.bytes(96)
            val cookieNonceSuffix = CryptoRandom.bytes(16)
            writeRecord(
                tag("COOK") + cookieNonceSuffix +
                    Nacl.boxSeal(
                        message = serverEphemeralPublic + cookie,
                        nonce = Nonce.tagged(Nonce.COOKIE_TAG, cookieNonceSuffix),
                        publicKey = clientEphemeral,
                        privateKey = identityPrivateKey,
                    ),
            )
            sessionKey = Nacl.beforeNm(clientEphemeral, serverEphemeralPrivate)

            val voucher = readRecord()
            check(hasTag(voucher, "VOCH")) { "expected VOCH" }
            check(voucher.copyOfRange(8, 104).contentEquals(cookie)) { "VOCH echoed the wrong cookie" }
            val vouchSequence = readBeLong(voucher, 104)
            check(vouchSequence == 1L) { "VOCH must use sequence 1, got $vouchSequence" }
            val body = Nacl.secretBoxOpen(
                key = sessionKey,
                nonce = Nonce.sequenced(Nonce.CLIENT_INITIATE, vouchSequence),
                boxed = voucher.copyOfRange(112, voucher.size),
            )
            checkNotNull(body) { "VOCH failed to authenticate" }

            clientPublicKey = body.copyOfRange(0, 32)
            val vouchNonceSuffix = body.copyOfRange(32, 48)
            val vouched = Nacl.boxOpen(
                boxed = body.copyOfRange(48, 96),
                nonce = Nonce.tagged(Nonce.VOUCH_TAG, vouchNonceSuffix),
                publicKey = clientPublicKey,
                privateKey = identityPrivateKey,
            )
            checkNotNull(vouched) { "vouch box failed to authenticate" }
            check(vouched.contentEquals(clientEphemeral)) { "vouch does not bind the ephemeral key" }

            val metadata = Metadata.decode(body.copyOfRange(96, body.size))
            protocolName = metadata.getValue(Metadata.KEY_PROTOCOL).decodeToString()
            rx = vouchSequence

            val certificate = certificateOverride ?: buildCertificate()
            val ready = buildMap {
                put(Metadata.KEY_CERTIFICATE, certificate)
                if (protocolName == Metadata.PROTOCOL_PAIRING) {
                    put(Metadata.KEY_PACE_VERSIONS, paceVersions.toByteArray(Charsets.US_ASCII))
                }
            }
            writeSealed("REDY", Nonce.SERVER_READY, Metadata.encode(ready))
        }

        private fun buildCertificate(): ByteArray {
            val signature = ByteArray(64)
            BcEd25519.sign(issuerPrivateKey, 0, identityPublicKey, 0, identityPublicKey.size, signature, 0)
            return issuerPublicKey + signature + identityPublicKey
        }

        // --- PACE, frame side ---------------------------------------------------------

        private fun runPairing() {
            val mappingScalar = CryptoRandom.x25519PrivateKey()
            val framePrivate = CryptoRandom.x25519PrivateKey()
            val salt = CryptoRandom.bytes(32)

            val base = Curve25519.scalarMult(sessionKey, BASE_POINT)
            val mapped = Curve25519.scalarMult(mappingScalar, base)
            val framePoint = Curve25519.scalarMult(framePrivate, mapped)

            val password = Digest.sha512(
                Pace.normalizeFriendCode(friendCode).toByteArray(Charsets.US_ASCII),
                clientPublicKey,
                identityPublicKey,
            )
            val stream = Digest.sha512(password, salt)
            val encryptedMapping =
                Nacl.xSalsa20Xor(stream.copyOfRange(0, 32), salt.copyOfRange(0, 24), mappingScalar)

            sendApp(byteArrayOf(Pace.CHALLENGE_TAG) + framePoint + salt + encryptedMapping)

            val answer = receiveApp()
            check(answer.size == 65 && answer[0] == Pace.RESPONSE_TAG) { "malformed PACE response" }
            val clientPoint = answer.copyOfRange(1, 33)
            val presentedProof = answer.copyOfRange(33, 65)

            val shared = Curve25519.scalarMult(framePrivate, clientPoint)
            val expectedProof = Digest.sha512(Digest.sha512(framePoint), shared).copyOfRange(0, 32)

            if (!ConstantTime.equals(expectedProof, presentedProof)) {
                // A wrong friend code. Answer with a confirmation the client cannot match,
                // rather than hanging up, so the client's own rejection path is exercised.
                pairingRejected = true
                sendApp(byteArrayOf(Pace.CONFIRM_TAG) + CryptoRandom.bytes(32))
                return
            }

            val confirmation = Digest.sha512(Digest.sha512(clientPoint), shared).copyOfRange(0, 32)
            sendApp(byteArrayOf(Pace.CONFIRM_TAG) + confirmation)
            pairingSucceeded.countDown()
        }

        // --- message handling ---------------------------------------------------------

        private fun handle(kind: Int, fields: Fields) {
            when (kind) {
                Kind.REQUEST_INFO -> sendInfo()
                Kind.CLIENT_INFO -> {
                    clientNames.add(fields.text(1))
                    clientNameReceived.countDown()
                }
                Kind.LIST_REQUEST -> sendMediaList()
                Kind.GET_MEDIA -> sendMedia(fields.sint(1))
                Kind.MEDIA_METADATA -> startUpload(fields)
                Kind.MEDIA_DATA -> continueUpload(fields)
                Kind.REQUEST_PERMISSION -> handlePermissionRequest(fields.uint(1))
                Kind.SET_VISIBILITY -> handleManage(kind, fields) { ids ->
                    val visible = fields.uint(2) == 1L
                    media = media.map { if (it.id in ids) MediaItem(it.id, it.type, visible, it.capturedAtMillis, it.receivedAtMillis) else it }
                }
                Kind.DELETE -> handleManage(kind, fields) { ids -> media = media.filterNot { it.id in ids } }
                Kind.DISPLAY_NOW -> displayedNow.add(fields.sint(1))
                Kind.RECEIPT -> Unit // the client acknowledging one of our events
                else -> Unit
            }
        }

        private fun sendInfo() {
            sendEnvelope(
                Kind.INFO,
                Proto.join(
                    Proto.text(1, frameName),
                    Proto.text(2, placement),
                    Proto.uint(3, width.toLong()),
                    Proto.uint(4, height.toLong()),
                    Proto.bool(5, permissions.sharePairing),
                    Proto.bool(6, permissions.backup),
                    Proto.bool(8, permissions.view),
                    Proto.bool(9, permissions.manage),
                ),
            )
        }

        private fun sendMediaList() {
            listError?.let {
                sendEnvelope(Kind.LIST_RESPONSE, Proto.blob(2, Proto.uint(1, it.code.toLong())))
                return
            }
            val items = media.map { item ->
                Proto.blob(
                    1,
                    Proto.join(
                        Proto.sint(1, item.id),
                        Proto.uint(2, item.type.ordinal.toLong()),
                        Proto.bool(3, item.visible),
                        Proto.sint(4, item.capturedAtMillis),
                        Proto.sint(5, item.receivedAtMillis),
                    ),
                )
            }
            sendEnvelope(Kind.LIST_RESPONSE, Proto.join(*items.toTypedArray()))
        }

        private fun sendMedia(id: Long) {
            val payload = mediaBytes
            sendEnvelope(
                Kind.MEDIA_METADATA,
                Proto.join(
                    Proto.uint(1, payload.size.toLong()),
                    Proto.text(2, mediaCaption),
                    Proto.text(5, "webp"),
                    Proto.sint(6, id),
                ),
            )
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + Multipart.CHUNK, payload.size)
                sendEnvelope(Kind.MEDIA_DATA, Proto.blob(1, payload.copyOfRange(offset, end)))
                offset = end
            }
        }

        private fun startUpload(fields: Fields) {
            pendingUpload = PendingUpload(
                declaredSize = fields.uint(1).toInt(),
                caption = fields.text(2),
                extension = fields.text(5),
                mediaId = fields.sint(6),
                capturedAtMillis = fields.sint(9),
                contentId = fields.sint(10),
                scale = fields.uint(12),
            )
        }

        private fun continueUpload(fields: Fields) {
            val pending = checkNotNull(pendingUpload) { "media data arrived before its metadata" }
            val chunk = fields.blob(1)
            uploadChunkSizes.add(chunk.size)
            pending.bytes += chunk

            val receipt = fields.uint(16)
            if (receipt == 0L) return

            val error = uploadReceiptError
            if (error != null) {
                uploadReceiptError = null
                sendEnvelope(
                    Kind.RECEIPT,
                    Proto.join(Proto.uint(1, receipt), Proto.blob(2, Proto.uint(1, error.code.toLong()))),
                )
                pendingUpload = null
                return
            }

            check(pending.bytes.size == pending.declaredSize) {
                "upload declared ${pending.declaredSize} bytes but delivered ${pending.bytes.size}"
            }
            uploads.add(
                ReceivedUpload(
                    mediaId = pending.mediaId,
                    contentId = pending.contentId,
                    caption = pending.caption,
                    extension = pending.extension,
                    scale = pending.scale,
                    capturedAtMillis = pending.capturedAtMillis,
                    bytes = pending.bytes,
                ),
            )
            pendingUpload = null
            sendEnvelope(Kind.RECEIPT, Proto.uint(1, receipt))
        }

        /**
         * Kinds 33 and 34: apply [change] to the library and answer with a receipt, or with
         * the armed [manageError] instead, the way a frame refuses.
         */
        private fun handleManage(kind: Int, fields: Fields, change: (Set<Long>) -> Unit) {
            val ids = Proto.parsePackedIds(fields.blob(1)).toSet()
            manageRequests.add(kind to ids)
            val receipt = fields.uint(16)
            val error = manageError
            if (error != null) {
                manageError = null
                sendEnvelope(Kind.RECEIPT, Proto.join(Proto.uint(1, receipt), Proto.blob(2, Proto.uint(1, error.code.toLong()))))
                return
            }
            change(ids)
            sendEnvelope(Kind.RECEIPT, Proto.uint(1, receipt))
        }

        private fun handlePermissionRequest(type: Long) {
            permissionRequests.add(type)
            if (grantViewOnRequest) {
                permissions = FramePermissions(view = true, manage = type == 3L)
            }
        }

        // --- framing ------------------------------------------------------------------

        /** The frame stamps its own protocol version, unlike [Envelopes.wrap]. */
        private fun frameEnvelope(kind: Int, body: ByteArray): ByteArray {
            val out = ByteArray(Envelopes.HEADER_SIZE + body.size)
            writeBeInt(out, 0, protocolVersion)
            writeBeInt(out, 4, kind)
            body.copyInto(out, Envelopes.HEADER_SIZE)
            return out
        }

        private fun sendEnvelope(kind: Int, body: ByteArray = ByteArray(0)) {
            val envelope = frameEnvelope(kind, body)
            if (!Multipart.isRequired(envelope)) {
                sendApp(envelope)
                return
            }
            for (part in Multipart.split(envelope, CryptoRandom.id63())) sendApp(part)
        }

        private fun receiveEnvelope(): Pair<Int, Fields> {
            while (true) {
                val message = receiveApp()
                var envelope = Envelopes.unwrap(message)
                var fields = Proto.parse(envelope.body)
                if (envelope.kind == Multipart.KIND) {
                    val complete = assembler.accept(fields) ?: continue
                    envelope = Envelopes.unwrap(complete)
                    fields = Proto.parse(envelope.body)
                }
                return envelope.kind to fields
            }
        }

        private fun sendApp(message: ByteArray) {
            val payload = ByteArray(2 + message.size)
            writeBeShort(payload, 0, message.size)
            message.copyInto(payload, 2)
            writeSealed("MESG", Nonce.SERVER_MESSAGE, payload)
        }

        private fun receiveApp(): ByteArray {
            val record = readRecord()
            check(hasTag(record, "MESG")) { "expected MESG" }
            val sequence = readBeLong(record, 8)
            check(sequence > rx) { "client re-used sequence $sequence" }
            rx = sequence
            val plain = Nacl.secretBoxOpen(
                key = sessionKey,
                nonce = Nonce.sequenced(Nonce.CLIENT_MESSAGE, sequence),
                boxed = record.copyOfRange(16, record.size),
            )
            checkNotNull(plain) { "client message failed to authenticate" }
            check(readBeShort(plain, 0) == plain.size - 2) { "client message length mismatch" }
            return plain.copyOfRange(2, plain.size)
        }

        private fun writeSealed(marker: String, noncePrefix: String, plain: ByteArray) {
            val record = tag(marker) + beLong(tx) +
                Nacl.secretBoxSeal(sessionKey, Nonce.sequenced(noncePrefix, tx), plain)
            tx++
            writeRecord(record)
            if (marker == "MESG" && replayNextRecord) {
                replayNextRecord = false
                writeRecord(record)
            }
        }

        private fun writeRecord(record: ByteArray) {
            val framed = ByteArray(2 + record.size)
            writeBeShort(framed, 0, record.size)
            record.copyInto(framed, 2)
            output.write(framed)
            output.flush()
        }

        private fun readRecord(): ByteArray {
            val header = readFully(2)
            return readFully(readBeShort(header, 0))
        }

        private fun readFully(length: Int): ByteArray {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) throw EOFException("client closed the connection")
                read += n
            }
            return buffer
        }
    }

    private class PendingUpload(
        val declaredSize: Int,
        val caption: String,
        val extension: String,
        val mediaId: Long,
        val capturedAtMillis: Long,
        val contentId: Long,
        val scale: Long,
    ) {
        var bytes: ByteArray = ByteArray(0)
    }

    private companion object {
        const val HOST = "127.0.0.1"
        val MAGIC = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x90.toByte(), 0x9F.toByte())
        val BASE_POINT = ByteArray(32).also { it[0] = 9 }

        fun tag(name: String): ByteArray = MAGIC + name.toByteArray(Charsets.US_ASCII)

        fun hasTag(record: ByteArray, name: String): Boolean {
            val expected = tag(name)
            return record.size >= expected.size && record.copyOfRange(0, expected.size).contentEquals(expected)
        }

        fun beLong(value: Long): ByteArray = ByteArray(8).also { writeBeLong(it, 0, value) }
    }
}

/** A photo the mock frame accepted, for assertions. */
internal class ReceivedUpload(
    val mediaId: Long,
    val contentId: Long,
    val caption: String,
    val extension: String,
    val scale: Long,
    val capturedAtMillis: Long,
    val bytes: ByteArray,
)

/** Convenience for building expected library contents. */
internal fun mediaItem(
    id: Long,
    type: MediaType = MediaType.PHOTO,
    visible: Boolean = true,
    capturedAtMillis: Long = 1_700_000_000_000,
    receivedAtMillis: Long = 1_700_000_100_000,
): MediaItem = MediaItem(id, type, visible, capturedAtMillis, receivedAtMillis)
