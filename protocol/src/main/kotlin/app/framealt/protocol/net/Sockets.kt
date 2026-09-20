package app.framealt.protocol.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The transport's view of a TCP connection.
 *
 * This exists so `:protocol` never names a concrete socket implementation. On Android the
 * app supplies a factory that creates sockets bound to the Wi-Fi [android.net.Network],
 * which is what stops LAN traffic leaking to cellular when Wi-Fi is not the default
 * network. See `Spec/00 - Initial/02 - Architecture.md` §8.2.
 */
public interface FrameSocket : AutoCloseable {

    public val input: InputStream

    public val output: OutputStream

    /** Applies to each subsequent read. Zero means block indefinitely. */
    public fun setReadTimeout(millis: Int)
}

public fun interface FrameSocketFactory {
    public fun connect(host: String, port: Int, connectTimeoutMillis: Int): FrameSocket
}

/**
 * Plain `java.net.Socket`. Works unchanged on the JVM and on Android, and is the default
 * everywhere except the Android send path.
 */
public object JavaSocketFactory : FrameSocketFactory {

    override fun connect(host: String, port: Int, connectTimeoutMillis: Int): FrameSocket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        return JavaFrameSocket(socket)
    }

    private class JavaFrameSocket(private val socket: Socket) : FrameSocket {
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
