package dev.dsmirnov.photoframe.device

import dev.dsmirnov.photoframe.data.FrameStore
import dev.dsmirnov.photoframe.data.IdentityStore
import dev.dsmirnov.photoframe.data.Settings
import dev.dsmirnov.photoframe.data.StoredFrame
import dev.dsmirnov.photoframe.diag.EventLog
import dev.dsmirnov.photoframe.protocol.FrameEndpoint
import dev.dsmirnov.photoframe.protocol.FramePairing
import dev.dsmirnov.photoframe.protocol.FrameSession
import dev.dsmirnov.photoframe.protocol.Frameo
import dev.dsmirnov.photoframe.protocol.ProtocolException
import dev.dsmirnov.photoframe.protocol.net.FrameSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "session"

/**
 * Owns connections to the frame.
 *
 * Two responsibilities the protocol layer deliberately does not have:
 *
 *  - **Serialisation.** The protocol has no request correlation beyond receipt IDs and no
 *    stream multiplexing, so exactly one operation may be in flight. A [Mutex] enforces it.
 *  - **Finding the frame again.** Addresses move with DHCP, so a failed connect is a reason
 *    to re-discover and retry once, not to report failure.
 */
class FrameConnectionManager(
    private val identityStore: IdentityStore,
    private val frameStore: FrameStore,
    private val settings: Settings,
    private val discovery: NsdDiscovery,
    private val socketFactory: FrameSocketFactory,
    private val eventLog: EventLog,
) {

    private val lock = Mutex()

    /**
     * Opens a session with the paired frame, runs [body], and persists whatever the frame
     * reported about itself.
     *
     * @throws NotPairedException if no pairing exists yet.
     */
    suspend fun <T> withSession(body: suspend (FrameSession) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val stored = frameStore.current() ?: throw NotPairedException()
            val identity = identityStore.identity()
            val senderName = settings.currentSenderName()

            val session = open(stored, identity, senderName)
            session.use {
                val info = it.refreshInfo()
                frameStore.saveInfo(info, currentEndpoint ?: stored.endpoint)
                eventLog.info(TAG, "connected to \"${info.name}\" (protocol ${info.protocolVersion})")
                body(it)
            }
        }
    }

    /** The endpoint the live session actually used, which may differ after rediscovery. */
    private var currentEndpoint: FrameEndpoint? = null

    private suspend fun open(
        stored: StoredFrame,
        identity: dev.dsmirnov.photoframe.protocol.FrameIdentity,
        senderName: String,
    ): FrameSession {
        currentEndpoint = null

        if (stored.host.isNotEmpty() && stored.port > 0) {
            try {
                eventLog.debug(TAG, "connecting to ${stored.endpoint}")
                val session = Frameo.connect(
                    endpoint = stored.endpoint,
                    identity = identity,
                    pairing = stored.pairing,
                    senderName = senderName,
                    socketFactory = socketFactory,
                )
                currentEndpoint = stored.endpoint
                return session
            } catch (failure: ProtocolException) {
                // An identity or issuer mismatch is not an addressing problem: the device at
                // that address is the wrong one, or something is impersonating it. Looking
                // harder would be the wrong response.
                eventLog.error(TAG, "refused: ${failure.message}")
                throw failure
            } catch (failure: Exception) {
                eventLog.warn(TAG, "${stored.endpoint} did not answer (${failure.message}); re-discovering")
            }
        }

        val found = discovery.discover().firstOrNull { stored.pairing.matches(it.instance) }
            ?: throw FrameUnreachableException()

        eventLog.info(TAG, "found the frame again at ${found.endpoint}")
        frameStore.saveEndpoint(found.endpoint)
        currentEndpoint = found.endpoint
        return Frameo.connect(
            endpoint = found.endpoint,
            identity = identity,
            pairing = stored.pairing,
            senderName = senderName,
            socketFactory = socketFactory,
        )
    }

    /**
     * Pairs with a frame and saves the result.
     *
     * @param endpoint from discovery, or entered by hand when mDNS is blocked.
     */
    suspend fun pair(endpoint: FrameEndpoint, friendCode: String, senderName: String): FramePairing =
        lock.withLock {
            withContext(Dispatchers.IO) {
                settings.setSenderName(senderName)
                val identity = identityStore.identity()
                eventLog.info(TAG, "pairing with $endpoint")

                val trusted = if (settings.currentAllowUnknownIssuer()) {
                    eventLog.warn(TAG, "accepting any certificate issuer (enabled in Advanced)")
                    AcceptAnyIssuer
                } else {
                    Frameo.trustedIssuers
                }

                val pairing = Frameo.pair(
                    endpoint = endpoint,
                    identity = identity,
                    friendCode = friendCode,
                    socketFactory = socketFactory,
                    trustedIssuers = trusted,
                )
                eventLog.info(
                    TAG,
                    "paired with ${EventLog.redact(pairing.peerId)} (issuer ${EventLog.redact(pairing.issuer)})",
                )
                frameStore.savePairing(pairing, endpoint)
                pairing
            }
        }

    suspend fun forget() = lock.withLock { frameStore.forget() }
}

/** No pairing exists yet; the UI should send the user through the connect flow. */
class NotPairedException : Exception("no frame is paired with this phone")

/** The frame is paired but not answering on this network. Retryable. */
class FrameUnreachableException : Exception("could not find the frame on this network")

/**
 * A set that claims to contain everything.
 *
 * Used only when the user has explicitly enabled "allow an unrecognised certificate issuer"
 * in Advanced, which turns an unknown-issuer brick into an informed decision.
 */
private val AcceptAnyIssuer = object : AbstractSet<String>() {
    override val size: Int get() = 0
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    override fun contains(element: String): Boolean = true
}
