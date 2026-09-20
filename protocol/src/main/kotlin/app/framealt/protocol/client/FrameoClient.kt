package app.framealt.protocol.client

import app.framealt.protocol.ProtocolException
import app.framealt.protocol.crypto.CryptoRandom
import app.framealt.protocol.crypto.Digest
import app.framealt.protocol.crypto.readBeLong
import app.framealt.protocol.transport.MdgTransport
import app.framealt.protocol.wire.Envelopes
import app.framealt.protocol.wire.Fields
import app.framealt.protocol.wire.Multipart
import app.framealt.protocol.wire.MultipartAssembler
import app.framealt.protocol.wire.Proto
import java.net.SocketTimeoutException

/** Frameo application message kinds, from the Android app v1.40.5 protocol. */
internal object Kind {
    const val REQUEST_INFO = 1
    const val INFO = 2
    const val CLIENT_INFO = 3
    const val MEDIA_METADATA = 4
    const val MEDIA_DATA = 5
    const val RECEIPT = 6
    const val FRAME_EVENT = 10
    const val GET_MEDIA = 23
    const val REQUEST_PERMISSION = 27
    const val LIST_REQUEST = 31
    const val LIST_RESPONSE = 32
}

/**
 * The Frameo application protocol over a live [MdgTransport].
 *
 * Blocking by design: one operation at a time on one connection, because the protocol has
 * no request correlation beyond receipt IDs and no stream multiplexing. The Android layer
 * wraps these calls on `Dispatchers.IO` and serialises them per frame.
 *
 * See `Spec/00 - Initial/01 - Protocol.md` §6 and §8.
 */
internal class FrameoClient(
    private val transport: MdgTransport,
    private val senderName: String,
) {

    private val assembler = MultipartAssembler()

    /** Refreshed from every kind-2 message the frame sends. */
    var info: FrameInfo = FrameInfo.UNKNOWN
        private set

    // --- operations -------------------------------------------------------------------

    fun refreshInfo(): FrameInfo {
        send(Kind.REQUEST_INFO)
        val deadline = Deadline.after(INFO_TIMEOUT_MS)
        while (true) {
            if (receive(deadline).kind == Kind.INFO) return info
        }
    }

    /**
     * Lists what is on the frame.
     *
     * @throws ProtocolException if the frame has not granted view access, or its firmware
     *   predates the media gallery.
     */
    fun listMedia(): List<MediaItem> {
        if (!info.permissions.view) {
            throw ProtocolException("photo access has not been granted on the frame")
        }
        if (info.protocolVersion < Envelopes.MIN_GALLERY_VERSION) {
            throw ProtocolException("frame protocol version ${info.protocolVersion} predates the media gallery")
        }

        send(Kind.LIST_REQUEST)
        val deadline = Deadline.after(LIST_TIMEOUT_MS)
        while (true) {
            val received = receive(deadline)
            if (received.kind != Kind.LIST_RESPONSE) continue
            FrameError.check(received.fields, 2)
            return received.fields.all(1).mapNotNull { value ->
                value.bytes?.let { Proto.parse(it) }?.let { item ->
                    MediaItem(
                        id = item.sint(1),
                        type = MediaType.of(item.uint(2)),
                        visible = item.bool(3),
                        capturedAtMillis = item.sint(4),
                        receivedAtMillis = item.sint(5),
                    )
                }
            }
        }
    }

    /**
     * Fetches one item.
     *
     * @param size when positive, asks the frame for a scaled preview of `size × size`;
     *   omit for the frame's full-size copy.
     */
    fun fetchMedia(id: Long, size: Int = 0): MediaDownload {
        if (!info.permissions.view) {
            throw ProtocolException("photo access has not been granted on the frame")
        }

        var body = Proto.sint(1, id)
        if (size > 0) {
            body = Proto.join(body, Proto.uint(2, size.toLong()), Proto.uint(3, size.toLong()))
        }
        send(Kind.GET_MEDIA, body)

        val deadline = Deadline.after(FETCH_TIMEOUT_MS)
        var expected = -1
        var extension = ""
        var caption = ""
        var buffer = ByteArray(0)

        while (true) {
            val received = receive(deadline)
            when (received.kind) {
                Kind.MEDIA_METADATA -> {
                    if (received.fields.sint(6) != id) {
                        throw ProtocolException("frame answered with a different photo ID")
                    }
                    FrameError.check(received.fields, 11)
                    val declared = received.fields.uint(1)
                    if (declared > MAX_DOWNLOAD_BYTES) {
                        throw ProtocolException("photo of $declared bytes exceeds the download limit")
                    }
                    if (declared == 0L) throw ProtocolException("the frame returned an empty photo")
                    expected = declared.toInt()
                    extension = received.fields.text(5)
                    caption = received.fields.text(2)
                }

                Kind.MEDIA_DATA -> {
                    if (expected < 0) throw ProtocolException("photo data arrived before its metadata")
                    buffer += received.fields.blob(1)
                    if (buffer.size > expected) {
                        throw ProtocolException("photo exceeded its announced size")
                    }
                    val receipt = received.fields.uint(16)
                    if (receipt != 0L) send(Kind.RECEIPT, Proto.uint(1, receipt))
                    if (buffer.size == expected) {
                        return MediaDownload(buffer, extension, caption)
                    }
                }
            }
        }
    }

    /**
     * Sends one photo and waits for the frame's receipt.
     *
     * [mediaId] and [contentId] are parameters rather than freshly generated here so a
     * retry after a lost receipt presents the frame with *identical* identifiers for
     * identical content — the only lever available for frame-side de-duplication. The send
     * queue persists both with the queue item.
     *
     * @param data the WebP payload.
     * @return the media ID the frame now holds this photo under.
     */
    @Suppress("LongParameterList")
    fun upload(
        data: ByteArray,
        caption: String = "",
        scale: PhotoScale = PhotoScale.FIT,
        capturedAtMillis: Long = System.currentTimeMillis(),
        mediaId: Long = CryptoRandom.id63(),
        contentId: Long = contentIdFor(data),
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
    ): Long {
        if (data.isEmpty() || data.size > MAX_UPLOAD_BYTES) {
            throw ProtocolException("photo must be between 1 byte and ${MAX_UPLOAD_BYTES / (1 shl 20)} MB")
        }

        val receipt = CryptoRandom.id63()
        send(
            Kind.MEDIA_METADATA,
            Proto.join(
                Proto.uint(1, data.size.toLong()),
                Proto.text(2, caption),
                Proto.float(3, FOCUS_CENTRE),
                Proto.float(4, FOCUS_CENTRE),
                Proto.text(5, "webp"),
                Proto.sint(6, mediaId),
                Proto.sint(9, capturedAtMillis),
                Proto.sint(10, contentId),
                Proto.uint(12, scale.wireValue),
            ),
        )

        val chunk = if (info.protocolVersion in 1 until Envelopes.SMALL_CHUNK_VERSION) {
            SMALL_CHUNK
        } else {
            Multipart.CHUNK
        }

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunk, data.size)
            var body = Proto.blob(1, data.copyOfRange(offset, end))
            if (end == data.size) body = Proto.join(body, Proto.uint(16, receipt))
            send(Kind.MEDIA_DATA, body)
            offset = end
            onProgress?.invoke(offset, data.size)
        }

        awaitReceipt(receipt)
        return mediaId
    }

    /**
     * Asks the frame for photo access. The owner must approve it on the frame itself;
     * there is no push response, so the caller polls [refreshInfo] until the bits flip.
     */
    fun requestPermission(manage: Boolean = false) {
        send(Kind.REQUEST_PERMISSION, Proto.uint(1, if (manage) 3 else 1))
    }

    // --- plumbing ---------------------------------------------------------------------

    private class Received(val version: Int, val kind: Int, val fields: Fields)

    private fun send(kind: Int, body: ByteArray = ByteArray(0)) {
        val envelope = Envelopes.wrap(kind, body)
        if (!Multipart.isRequired(envelope)) {
            transport.send(envelope)
            return
        }
        for (part in Multipart.split(envelope, CryptoRandom.id63())) {
            transport.send(part)
        }
    }

    /**
     * Reads the next message, handling the housekeeping the frame expects of every client:
     * answering its request for our name, caching its info, and acknowledging events that
     * carry a receipt ID.
     */
    private fun receive(deadline: Deadline): Received {
        while (true) {
            val message = transport.receive(deadline.remainingMillis())
            var envelope = Envelopes.unwrap(message)
            var fields = Proto.parse(envelope.body)

            if (envelope.kind == Multipart.KIND) {
                val complete = assembler.accept(fields) ?: continue
                envelope = Envelopes.unwrap(complete)
                fields = Proto.parse(envelope.body)
            }

            when (envelope.kind) {
                Kind.REQUEST_INFO -> send(Kind.CLIENT_INFO, Proto.text(1, senderName))
                Kind.INFO -> info = parseInfo(envelope.version, fields)
                Kind.FRAME_EVENT -> {
                    val receipt = fields.uint(16)
                    if (receipt != 0L) send(Kind.RECEIPT, Proto.uint(1, receipt))
                }
            }
            return Received(envelope.version, envelope.kind, fields)
        }
    }

    private fun awaitReceipt(receipt: Long) {
        val deadline = Deadline.after(RECEIPT_TIMEOUT_MS)
        while (true) {
            val received = receive(deadline)
            if (received.kind == Kind.RECEIPT && received.fields.uint(1) == receipt) {
                FrameError.check(received.fields, 2)
                return
            }
        }
    }

    private fun parseInfo(version: Int, fields: Fields) = FrameInfo(
        name = fields.text(1),
        placement = fields.text(2),
        width = fields.uint(3).toInt(),
        height = fields.uint(4).toInt(),
        protocolVersion = version,
        permissions = FramePermissions(
            sharePairing = fields.bool(5),
            backup = fields.bool(6),
            view = fields.bool(8),
            manage = fields.bool(9),
        ),
    )

    /** Tracks a wall-clock budget across the several reads one operation may need. */
    private class Deadline(private val endNanos: Long) {
        fun remainingMillis(): Int {
            val remaining = (endNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException("the frame did not respond in time")
            return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        companion object {
            fun after(millis: Int) = Deadline(System.nanoTime() + millis * 1_000_000L)
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES: Int = 32 shl 20
        private const val MAX_DOWNLOAD_BYTES = (64L shl 20)

        /** Chunk size for frames older than protocol version 4. */
        private const val SMALL_CHUNK = 924

        /** The frame's crop focus point. The reference always centres it. */
        private const val FOCUS_CENTRE = 0.5f

        private const val INFO_TIMEOUT_MS = 10_000
        private const val LIST_TIMEOUT_MS = 30_000
        private const val FETCH_TIMEOUT_MS = 45_000
        private const val RECEIPT_TIMEOUT_MS = 120_000

        /** The frame's content identifier: the first 8 bytes of SHA-256, sign bit cleared. */
        fun contentIdFor(data: ByteArray): Long =
            readBeLong(Digest.sha256(data), 0) and 0x7FFF_FFFF_FFFF_FFFFL
    }
}
