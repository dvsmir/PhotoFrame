package dev.dsmirnov.photoframe.framectl

import dev.dsmirnov.photoframe.protocol.util.Hex
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds frames without mDNS.
 *
 * Windows Firewall silently drops inbound UDP 5353 for unrecognised executables, which makes
 * discovery look identical to "there is no frame here". This probe is outbound-only: it opens
 * TCP connections and speaks the first two bytes of the MDG handshake.
 *
 * A frame answers `🐟TELL` with a 40-byte `🐟WELC` carrying its public key, so a positive
 * result is proof of identity, not a guess from a port number — and it hands back the exact
 * host, port and peer ID needed for `pair --host --port`.
 */
object Probe {

    private val MAGIC = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x90.toByte(), 0x9F.toByte())
    private val TELL = MAGIC + "TELL".toByteArray(Charsets.US_ASCII)
    private val WELC = MAGIC + "WELC".toByteArray(Charsets.US_ASCII)

    class Found(val host: String, val port: Int, val peerId: String)

    /** Opens a TCP connection and returns true if something is listening. */
    private fun isOpen(host: String, port: Int, timeoutMillis: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Speaks TELL to an open port.
     *
     * @return the frame's peer ID, or null if whatever is listening is not a frame.
     */
    fun identify(host: String, port: Int, timeoutMillis: Int = 2_000): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis

            // Records are length-prefixed: u16be length, then the record.
            val framed = byteArrayOf(0, TELL.size.toByte()) + TELL
            socket.getOutputStream().write(framed)
            socket.getOutputStream().flush()

            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length != 40) return null
            val record = ByteArray(length)
            input.readFully(record)
            if (!record.copyOfRange(0, 8).contentEquals(WELC)) return null
            Hex.encode(record.copyOfRange(8, 40))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Scans [hosts] across [ports] and returns every frame that answers.
     *
     * @param parallelism kept high because almost every probe is a timeout; a home LAN
     *   sweep is dominated by waiting, not by work.
     */
    fun scan(
        hosts: List<String>,
        ports: IntRange,
        connectTimeoutMillis: Int = 400,
        parallelism: Int = 512,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<Found> {
        val pool = Executors.newFixedThreadPool(parallelism)
        val open = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val done = java.util.concurrent.atomic.AtomicInteger()
        val total = hosts.size * (ports.last - ports.first + 1)

        try {
            for (host in hosts) {
                for (port in ports) {
                    pool.submit {
                        if (isOpen(host, port, connectTimeoutMillis)) open.add(host to port)
                        val count = done.incrementAndGet()
                        if (count % 2000 == 0) onProgress?.invoke(count, total)
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }
        onProgress?.invoke(total, total)

        return open.sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { (host, port) ->
            identify(host, port)?.let { Found(host, port, it) }
        }
    }

    /** Every address in this machine's own /24, excluding the network and broadcast addresses. */
    fun subnetOf(address: String): List<String> {
        val prefix = address.substringBeforeLast('.')
        return (1..254).map { "$prefix.$it" }.filterNot { it == address }
    }
}
