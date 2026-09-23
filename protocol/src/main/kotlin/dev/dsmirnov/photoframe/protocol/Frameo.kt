package dev.dsmirnov.photoframe.protocol

import dev.dsmirnov.photoframe.protocol.client.FrameInfo
import dev.dsmirnov.photoframe.protocol.client.FrameoClient
import dev.dsmirnov.photoframe.protocol.client.MediaDownload
import dev.dsmirnov.photoframe.protocol.client.MediaItem
import dev.dsmirnov.photoframe.protocol.client.PhotoScale
import dev.dsmirnov.photoframe.protocol.crypto.CryptoRandom
import dev.dsmirnov.photoframe.protocol.crypto.Curve25519
import dev.dsmirnov.photoframe.protocol.net.FrameSocketFactory
import dev.dsmirnov.photoframe.protocol.net.JavaSocketFactory
import dev.dsmirnov.photoframe.protocol.pairing.PaceExchange
import dev.dsmirnov.photoframe.protocol.transport.IssuerTrust
import dev.dsmirnov.photoframe.protocol.transport.MdgTransport
import dev.dsmirnov.photoframe.protocol.transport.Metadata
import dev.dsmirnov.photoframe.protocol.transport.TransportTimeouts
import dev.dsmirnov.photoframe.protocol.util.Hex

/**
 * The public surface of the protocol library.
 *
 * Everything below this line is `internal`, so callers cannot accidentally depend on the
 * wire format. Both the Android app and the desktop CLI go through here.
 */

/** Where a frame is on the network right now. Addresses change with DHCP; re-discover on failure. */
public class FrameEndpoint(public val host: String, public val port: Int) {
    override fun toString(): String = "$host:$port"
}

/** A frame found by mDNS. [instance] is the frame's peer ID, truncated to 63 characters. */
public class DiscoveredFrame(
    public val instance: String,
    public val host: String,
    public val port: Int,
) {
    public val endpoint: FrameEndpoint get() = FrameEndpoint(host, port)
}

/** Platform-specific service discovery: jmDNS on desktop, `NsdManager` on Android. */
public interface FrameDiscovery {
    public fun discover(timeoutMillis: Int = 5_000): List<DiscoveredFrame>

    public companion object {
        public const val SERVICE_TYPE: String = "_frameo._tcp"
        public const val SERVICE_TYPE_LOCAL: String = "_frameo._tcp.local."
    }
}

/**
 * This client's long-term key pair. **The private key is the pairing** — anyone holding it
 * can send to any frame paired with it, and losing it means pairing again with a fresh
 * friend code.
 */
public class FrameIdentity(internal val privateKey: ByteArray) {

    public val publicKeyHex: String = Hex.encode(Curve25519.scalarMultBase(privateKey))

    /** Hex, for storage. Treat the result as a secret. */
    public fun exportPrivateKey(): String = Hex.encode(privateKey)

    public companion object {
        public fun generate(): FrameIdentity = FrameIdentity(CryptoRandom.x25519PrivateKey())

        public fun fromPrivateKey(hex: String): FrameIdentity {
            val bytes = try {
                Hex.decode(hex)
            } catch (failure: IllegalArgumentException) {
                throw ProtocolException("stored identity key is not valid hex", failure)
            }
            if (bytes.size != Curve25519.KEY_SIZE) {
                throw ProtocolException("stored identity key must be 32 bytes, was ${bytes.size}")
            }
            return FrameIdentity(bytes)
        }
    }
}

/**
 * What pairing produced: the frame's identity and the certificate issuer pinned at that
 * moment. Both are checked on every later connection.
 */
public class FramePairing(
    public val peerId: String,
    public val issuer: String,
) {
    /** True when an mDNS instance name refers to this frame (instance names cap at 63 chars). */
    public fun matches(instance: String): Boolean =
        instance.equals(peerId, ignoreCase = true) ||
            (instance.length == 63 && peerId.startsWith(instance, ignoreCase = true))
}

/** A live, authenticated connection to one frame. Not thread-safe: one operation at a time. */
public class FrameSession internal constructor(
    private val transport: MdgTransport,
    private val client: FrameoClient,
) : AutoCloseable {

    public val pairing: FramePairing = FramePairing(transport.framePublicKeyHex, transport.issuerHex)

    public val info: FrameInfo get() = client.info

    public fun refreshInfo(): FrameInfo = client.refreshInfo()

    public fun listMedia(): List<MediaItem> = client.listMedia()

    public fun fetchMedia(id: Long, size: Int = 0): MediaDownload = client.fetchMedia(id, size)

    /**
     * Sends one WebP photo and blocks until the frame confirms it.
     *
     * Pass a stable [mediaId] and [contentId] when retrying a previously interrupted send,
     * so the frame sees identical identifiers for identical content.
     */
    @Suppress("LongParameterList")
    public fun upload(
        webp: ByteArray,
        caption: String = "",
        scale: PhotoScale = PhotoScale.FIT,
        capturedAtMillis: Long = System.currentTimeMillis(),
        mediaId: Long = CryptoRandom.id63(),
        contentId: Long = FrameoClient.contentIdFor(webp),
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
    ): Long = client.upload(webp, caption, scale, capturedAtMillis, mediaId, contentId, onProgress)

    /** Asks for photo access. The owner approves on the frame; poll [refreshInfo] afterwards. */
    public fun requestPermission(manage: Boolean = false): Unit = client.requestPermission(manage)

    /**
     * Hides ([visible] false) or shows items in the frame's slideshow; they stay on the
     * frame either way. Needs *manage* permission. Any number of IDs: they go out in
     * requests of at most 1000, each confirmed by the frame before the next.
     */
    public fun setVisibility(ids: Collection<Long>, visible: Boolean) {
        ids.distinct().chunked(FrameoClient.MAX_MANAGE_IDS).forEach { client.setVisibility(it, visible) }
    }

    /**
     * Deletes items from the frame, for everyone, irreversibly. Needs *manage* permission.
     *
     * Batches of at most 1000, each confirmed before the next. If a later batch fails, the
     * earlier ones are already gone; re-list to see what remains.
     */
    public fun delete(ids: Collection<Long>) {
        ids.distinct().chunked(FrameoClient.MAX_MANAGE_IDS).forEach { client.delete(it) }
    }

    /** Shows one item on the frame now. The frame sends no receipt, so this cannot be confirmed. */
    public fun displayNow(id: Long): Unit = client.displayNow(id)

    override fun close(): Unit = transport.close()
}

public object Frameo {

    /** A fresh media/receipt identifier, for callers that persist one before sending. */
    public fun newId(): Long = CryptoRandom.id63()

    /** The frame's content identifier for a payload: first 8 bytes of SHA-256, sign bit cleared. */
    public fun contentIdFor(data: ByteArray): Long = FrameoClient.contentIdFor(data)

    /**
     * Opens a session with a frame this client is already paired with.
     *
     * @param pairing pins the frame's identity and certificate issuer; a mismatch is a hard
     *   failure, never a prompt.
     */
    @Suppress("LongParameterList")
    public fun connect(
        endpoint: FrameEndpoint,
        identity: FrameIdentity,
        pairing: FramePairing,
        senderName: String,
        socketFactory: FrameSocketFactory = JavaSocketFactory,
        timeouts: TransportTimeouts = TransportTimeouts(),
    ): FrameSession {
        val transport = MdgTransport.connect(
            socketFactory = socketFactory,
            host = endpoint.host,
            port = endpoint.port,
            identityPrivateKey = identity.privateKey,
            protocol = Metadata.PROTOCOL_SESSION,
            expectedPeerHex = pairing.peerId,
            expectedIssuerHex = pairing.issuer,
            timeouts = timeouts,
        )
        return FrameSession(transport, FrameoClient(transport, senderName))
    }

    /**
     * Pairs with a frame using the friend code from its *Add friend* screen.
     *
     * The code is single-use and short-lived. On success, persist the returned
     * [FramePairing] alongside the identity.
     *
     * @param trustedIssuers override only to accept an issuer outside the built-in list,
     *   and only on an explicit, informed user decision.
     */
    @Suppress("LongParameterList")
    public fun pair(
        endpoint: FrameEndpoint,
        identity: FrameIdentity,
        friendCode: String,
        socketFactory: FrameSocketFactory = JavaSocketFactory,
        trustedIssuers: Set<String> = IssuerTrust.trusted,
        timeouts: TransportTimeouts = TransportTimeouts(),
    ): FramePairing {
        MdgTransport.connect(
            socketFactory = socketFactory,
            host = endpoint.host,
            port = endpoint.port,
            identityPrivateKey = identity.privateKey,
            protocol = Metadata.PROTOCOL_PAIRING,
            trustedIssuers = trustedIssuers,
            timeouts = timeouts,
        ).use { transport ->
            PaceExchange.pair(transport, friendCode)
            return FramePairing(transport.framePublicKeyHex, transport.issuerHex)
        }
    }

    /** The issuer keys trusted for a first connection. Exposed for diagnostics. */
    public val trustedIssuers: Set<String> get() = IssuerTrust.trusted
}
