package dev.dsmirnov.photoframe.framectl

import dev.dsmirnov.photoframe.protocol.FrameEndpoint
import dev.dsmirnov.photoframe.protocol.FrameIdentity
import dev.dsmirnov.photoframe.protocol.FramePairing
import java.io.File
import java.util.Properties

/**
 * The CLI's saved identity and pairing.
 *
 * **Plain text on purpose.** This is a developer tool for validating the protocol against a
 * real frame, and a file the user can inspect is worth more here than encryption a human
 * cannot read. The Android app stores the same key wrapped with an Android Keystore key —
 * see `Spec/00 - Initial/02 - Architecture.md` §5.1. Do not reuse this class there.
 */
class State(private val file: File) {

    private val properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    var identity: FrameIdentity?
        get() = properties.getProperty(KEY_PRIVATE)?.let { FrameIdentity.fromPrivateKey(it) }
        set(value) {
            if (value == null) properties.remove(KEY_PRIVATE) else properties.setProperty(KEY_PRIVATE, value.exportPrivateKey())
        }

    var pairing: FramePairing?
        get() {
            val peer = properties.getProperty(KEY_PEER) ?: return null
            val issuer = properties.getProperty(KEY_ISSUER) ?: return null
            return FramePairing(peer, issuer)
        }
        set(value) {
            if (value == null) {
                properties.remove(KEY_PEER)
                properties.remove(KEY_ISSUER)
            } else {
                properties.setProperty(KEY_PEER, value.peerId)
                properties.setProperty(KEY_ISSUER, value.issuer)
            }
        }

    var endpoint: FrameEndpoint?
        get() {
            val host = properties.getProperty(KEY_HOST) ?: return null
            val port = properties.getProperty(KEY_PORT)?.toIntOrNull() ?: return null
            return FrameEndpoint(host, port)
        }
        set(value) {
            if (value == null) {
                properties.remove(KEY_HOST)
                properties.remove(KEY_PORT)
            } else {
                properties.setProperty(KEY_HOST, value.host)
                properties.setProperty(KEY_PORT, value.port.toString())
            }
        }

    var senderName: String
        get() = properties.getProperty(KEY_SENDER) ?: "framectl"
        set(value) = properties.setProperty(KEY_SENDER, value).let { }

    var frameName: String
        get() = properties.getProperty(KEY_FRAME_NAME) ?: ""
        set(value) = properties.setProperty(KEY_FRAME_NAME, value).let { }

    /** Creates the identity on first use, so a pairing is never attempted without one. */
    fun requireIdentity(): FrameIdentity = identity ?: FrameIdentity.generate().also {
        identity = it
        save()
        println("Generated a new client identity: ${it.publicKeyHex.take(16)}…")
    }

    fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "FrameAlt framectl state - contains a private pairing key") }
    }

    val path: String get() = file.absolutePath

    companion object {
        private const val KEY_PRIVATE = "identity.privateKey"
        private const val KEY_PEER = "frame.peerId"
        private const val KEY_ISSUER = "frame.issuer"
        private const val KEY_HOST = "frame.host"
        private const val KEY_PORT = "frame.port"
        private const val KEY_SENDER = "sender.name"
        private const val KEY_FRAME_NAME = "frame.name"

        fun default(): State =
            State(File(System.getProperty("user.home"), ".framealt/framectl.properties"))
    }
}
