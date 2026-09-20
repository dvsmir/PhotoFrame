package app.framealt.framectl

import app.framealt.protocol.DiscoveredFrame
import app.framealt.protocol.FrameDiscovery
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS

/**
 * Desktop mDNS discovery.
 *
 * Browses on **every** usable IPv4 interface rather than letting jmDNS pick one. A desktop
 * commonly has several (Wi-Fi, Ethernet, plus virtual adapters from Docker, WSL, VPNs and
 * VirtualBox), and jmDNS's default choice is often the wrong one — which looks exactly like
 * "the frame isn't there".
 *
 * The Android app uses `NsdManager` instead; this class exists only for the CLI.
 */
class JmdnsDiscovery : FrameDiscovery {

    override fun discover(timeoutMillis: Int): List<DiscoveredFrame> {
        val found = LinkedHashMap<String, DiscoveredFrame>()
        for (address in usableAddresses()) {
            try {
                JmDNS.create(address).use { jmdns ->
                    for (service in jmdns.list(FrameDiscovery.SERVICE_TYPE_LOCAL, timeoutMillis.toLong())) {
                        val host = service.inet4Addresses.firstOrNull()?.hostAddress ?: continue
                        found.putIfAbsent(service.name, DiscoveredFrame(service.name, host, service.port))
                    }
                }
            } catch (failure: Exception) {
                System.err.println("  (discovery failed on ${address.hostAddress}: ${failure.message})")
            }
        }
        return found.values.toList()
    }

    /** Every up, multicast-capable, non-loopback IPv4 address on this machine. */
    fun usableAddresses(): List<InetAddress> = buildList {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback || !nic.supportsMulticast()) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    add(address)
                }
            }
        }
    }

    fun describeInterfaces(): String = buildString {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            val v4 = nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
            if (v4.isEmpty()) continue
            appendLine(
                "  ${nic.displayName}: ${v4.joinToString { it.hostAddress }}" +
                    if (nic.supportsMulticast()) "" else "  (no multicast)",
            )
        }
    }
}
