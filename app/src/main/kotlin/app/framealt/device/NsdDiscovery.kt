package app.framealt.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.framealt.diag.EventLog
import app.framealt.protocol.DiscoveredFrame
import app.framealt.protocol.FrameDiscovery
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "discovery"

/**
 * mDNS discovery via the platform's `NsdManager`.
 *
 * Two platform quirks shape this class:
 *
 *  - **Resolution must be serialised.** The legacy `resolveService` allows only one
 *    in-flight resolve per `NsdManager`; a second concurrent call fails with
 *    `FAILURE_ALREADY_ACTIVE`. A [Mutex] enforces one at a time.
 *  - **`resolveService` is deprecated from API 34**, replaced by
 *    `registerServiceInfoCallback`. Both paths are implemented because `minSdk` is 31.
 *
 * Discovery is also the operation most likely to be silently blocked — by a router that
 * drops multicast, a guest SSID, or (once targeting SDK 37) a missing local network
 * permission. Every step is logged so the Diagnostics screen can tell those apart.
 */
class NsdDiscovery(
    context: Context,
    private val eventLog: EventLog,
) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val resolveLock = Mutex()
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun discover(timeoutMillis: Int = DEFAULT_TIMEOUT_MS): List<DiscoveredFrame> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredFrame>()
            val discovered = Channel<NsdServiceInfo>(Channel.UNLIMITED)

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    eventLog.debug(TAG, "browsing $serviceType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    eventLog.debug(TAG, "found ${EventLog.redact(service.serviceName)}")
                    discovered.trySend(service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    eventLog.debug(TAG, "lost ${EventLog.redact(service.serviceName)}")
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    eventLog.error(TAG, "could not start discovery (error $errorCode)")
                    discovered.close()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    eventLog.warn(TAG, "could not stop discovery (error $errorCode)")
                }
            }

            try {
                nsdManager.discoverServices(
                    FrameDiscovery.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
                withTimeoutOrNull(timeoutMillis.toLong()) {
                    for (service in discovered) {
                        val frame = resolveLock.withLock { resolve(service) }
                        if (frame != null) found[frame.instance] = frame
                    }
                }
            } finally {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                discovered.close()
            }

            eventLog.info(TAG, "discovery finished: ${found.size} frame(s)")
            found.values.sortedBy { it.instance }
        }

    private suspend fun resolve(service: NsdServiceInfo): DiscoveredFrame? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= 34) resolveModern(service) else resolveLegacy(service)
        }

    @android.annotation.TargetApi(34)
    private suspend fun resolveModern(service: NsdServiceInfo): DiscoveredFrame? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    eventLog.warn(TAG, "resolve registration failed (error $errorCode)")
                    continuation.resumeOnce(null)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.hostAddresses
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull()
                        ?.hostAddress
                    continuation.resumeOnce(
                        host?.let { DiscoveredFrame(serviceInfo.serviceName, it, serviceInfo.port) },
                    )
                    runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                }

                override fun onServiceLost() {
                    continuation.resumeOnce(null)
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }

            runCatching { nsdManager.registerServiceInfoCallback(service, executor, callback) }
                .onFailure {
                    eventLog.warn(TAG, "resolve failed: ${it.message}")
                    continuation.resumeOnce(null)
                }
            continuation.invokeOnCancellation {
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(service: NsdServiceInfo): DiscoveredFrame? =
        suspendCancellableCoroutine { continuation ->
            nsdManager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        eventLog.warn(
                            TAG,
                            "resolve failed for ${EventLog.redact(serviceInfo.serviceName)} (error $errorCode)",
                        )
                        continuation.resumeOnce(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
                        continuation.resumeOnce(
                            host?.let { DiscoveredFrame(serviceInfo.serviceName, it, serviceInfo.port) },
                        )
                    }
                },
            )
        }

    /** Guards against a platform callback firing twice, which would crash the coroutine. */
    private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
        if (isActive) resume(value)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val RESOLVE_TIMEOUT_MS = 4_000L
    }
}
