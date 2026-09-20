package app.framealt.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.framealt.diag.EventLog
import app.framealt.protocol.net.FrameSocket
import app.framealt.protocol.net.FrameSocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "network"

/**
 * Creates sockets bound to the Wi-Fi network rather than the process default.
 *
 * Without this, a phone with mobile data active routes LAN traffic to cellular whenever
 * Wi-Fi is not the default network — which happens whenever the Wi-Fi has no internet, is
 * captive, or is simply deprioritised. The socket then goes nowhere and the failure is
 * indistinguishable from a sleeping frame.
 *
 * `registerNetworkCallback` is used rather than `requestNetwork` on purpose: it only
 * observes, so it needs no `CHANGE_NETWORK_STATE` permission. `bindProcessToNetwork` is
 * deliberately avoided — it is process-global and would affect everything else the app does.
 */
class WifiSocketFactory(
    context: Context,
    private val eventLog: EventLog,
) : FrameSocketFactory {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wifiNetwork: Network? = null

    @Volatile
    private var wifiAddresses: String = ""

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            eventLog.info(TAG, "Wi-Fi network available")
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                wifiAddresses = ""
                eventLog.warn(TAG, "Wi-Fi network lost")
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (wifiNetwork != network) return
            wifiAddresses = linkProperties.linkAddresses.joinToString { it.address.hostAddress.orEmpty() }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
            .onFailure { eventLog.error(TAG, "could not observe Wi-Fi: ${it.message}") }
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /** True when a Wi-Fi network is currently available to bind to. */
    val isOnWifi: Boolean get() = wifiNetwork != null

    /** For the Diagnostics screen. */
    fun describe(): String =
        if (wifiNetwork == null) "not connected to Wi-Fi" else "Wi-Fi bound ($wifiAddresses)"

    override fun connect(host: String, port: Int, connectTimeoutMillis: Int): FrameSocket {
        val network = wifiNetwork
            ?: throw IOException("not connected to Wi-Fi")

        val socket = network.socketFactory.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
        return AndroidFrameSocket(socket)
    }

    private class AndroidFrameSocket(private val socket: Socket) : FrameSocket {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun setReadTimeout(millis: Int) {
            socket.soTimeout = millis
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
