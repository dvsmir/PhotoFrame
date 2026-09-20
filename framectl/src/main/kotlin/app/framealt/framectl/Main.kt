package app.framealt.framectl

import app.framealt.protocol.FrameEndpoint
import app.framealt.protocol.FramePairing
import app.framealt.protocol.FrameSession
import app.framealt.protocol.Frameo
import app.framealt.protocol.ProtocolException
import app.framealt.protocol.client.PhotoScale
import app.framealt.protocol.pairing.PairingException
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * A desktop CLI for driving a real Frameo frame through the `:protocol` library.
 *
 * It exists to validate the protocol port against real hardware *before* any Android UI is
 * written — the same library, the same handshake, the same messages, just with a terminal
 * instead of Compose. When something breaks later on the phone, this tells you whether the
 * protocol or the Android layer is at fault.
 */
fun main(args: Array<String>) {
    val state = State.default()
    val arguments = Arguments(args.drop(1))
    try {
        when (args.firstOrNull()) {
            "discover" -> discover(arguments)
            "probe" -> probe(arguments)
            "pair" -> pair(state, arguments)
            "info" -> info(state)
            "list" -> listMedia(state)
            "grant" -> grant(state, arguments)
            "send" -> send(state, arguments)
            "get" -> get(state, arguments)
            "forget" -> forget(state)
            else -> usage()
        }
    } catch (failure: PairingException) {
        fail("Pairing failed (${failure.reason}): ${failure.message}")
    } catch (failure: ProtocolException) {
        fail("Protocol error: ${failure.message}")
    } catch (failure: Exception) {
        fail("${failure::class.simpleName}: ${failure.message}")
    }
}

// --- commands ---------------------------------------------------------------------------

private fun discover(arguments: Arguments) {
    val discovery = JmdnsDiscovery()
    println("Interfaces being browsed:")
    print(discovery.describeInterfaces())

    val seconds = arguments.int("seconds") ?: 5
    println("\nBrowsing _frameo._tcp.local. for ${seconds}s…")
    val frames = discovery.discover(seconds * 1000)

    if (frames.isEmpty()) {
        println(
            """
            |
            |No frames found.
            |  - Is the frame powered on and awake?
            |  - Is it on the same network as this machine (not a guest SSID)?
            |  - Some routers and VPNs block mDNS. Try: framectl pair <code> --host <ip> --port <port>
            """.trimMargin(),
        )
        return
    }

    println("\nFound ${frames.size} frame(s):")
    for (frame in frames) {
        println("  ${frame.host}:${frame.port}")
        println("    instance ${frame.instance}")
    }
}

/**
 * Finds frames by speaking the handshake directly, for when mDNS is blocked.
 *
 * Defaults to this machine's own /24 and the full port range, because Frameo's LAN port is
 * not fixed and is normally learned from the mDNS record we cannot see.
 */
private fun probe(arguments: Arguments) {
    val hosts = arguments.value("hosts")?.split(",")?.map { it.trim() }
        ?: JmdnsDiscovery().usableAddresses().firstOrNull()?.hostAddress?.let { Probe.subnetOf(it) }
        ?: fail("no usable IPv4 address found; pass --hosts a,b,c")

    val portRange = parsePorts(arguments.value("ports") ?: "1-65535")
    val timeout = arguments.int("timeout") ?: 400

    println("Probing ${hosts.size} host(s), ports ${portRange.first}-${portRange.last}, ${timeout}ms timeout.")
    println("Outbound TCP only, so the local firewall does not affect this.\n")

    val started = System.currentTimeMillis()
    val found = Probe.scan(hosts, portRange, timeout) { done, total ->
        val percent = done * 100 / total
        print("\r  $percent%  ($done / $total)")
        System.out.flush()
    }
    val seconds = (System.currentTimeMillis() - started) / 1000
    println("\rDone in ${seconds}s.".padEnd(40))

    if (found.isEmpty()) {
        println(
            """
            |
            |No frames answered.
            |  - Is the frame on this subnet? Wi-Fi and Ethernet are sometimes separate networks.
            |  - Is the frame awake? Some models stop serving while the screen is off.
            |  - Try a wider sweep:  framectl probe --hosts <ip> --ports 1-65535 --timeout 1000
            """.trimMargin(),
        )
        return
    }

    println("\nFound ${found.size} frame(s):")
    for (frame in found) {
        println("  ${frame.host}:${frame.port}")
        println("    peer id ${frame.peerId}")
        println("    pair with:  framectl pair <friend-code> --host ${frame.host} --port ${frame.port}")
    }
}

private fun parsePorts(spec: String): IntRange {
    val parts = spec.split("-")
    val first = parts[0].toIntOrNull() ?: fail("bad --ports value: $spec")
    val last = if (parts.size > 1) parts[1].toIntOrNull() ?: fail("bad --ports value: $spec") else first
    if (first !in 1..65535 || last !in first..65535) fail("bad --ports range: $spec")
    return first..last
}

private fun pair(state: State, arguments: Arguments) {
    val code = arguments.positional(0) ?: fail("usage: framectl pair <friend-code> [--name N] [--host H --port P]")
    val identity = state.requireIdentity()
    val endpoint = resolveEndpoint(arguments) ?: fail("no frame found; pass --host and --port")

    arguments.value("name")?.let { state.senderName = it }
    println("Pairing with $endpoint as \"${state.senderName}\"…")

    val trustAny = arguments.flag("trust-any")
    if (trustAny) {
        println(
            "\n!! --trust-any: accepting ANY certificate issuer. Only do this for a frame you\n" +
                "!! physically control, and check the issuer printed below before trusting it again.\n",
        )
    }

    val pairing = try {
        Frameo.pair(
            endpoint = endpoint,
            identity = identity,
            friendCode = code,
            trustedIssuers = if (trustAny) AcceptAnyIssuer else Frameo.trustedIssuers,
        )
    } catch (failure: ProtocolException) {
        if (failure.message?.contains("unrecognised frame certificate issuer") == true) {
            fail(
                """
                |This frame's certificate is signed by a key FrameAlt does not recognise.
                |
                |That means either the frame is newer than the built-in issuer list, or the
                |device answering is not a Frameo frame. To see the issuer, re-run with:
                |
                |    framectl pair $code --trust-any
                |
                |and if the issuer looks legitimate, add it to
                |protocol/src/main/resources/app/framealt/protocol/issuers.json
                """.trimMargin(),
            )
        }
        throw failure
    }

    state.pairing = pairing
    state.endpoint = endpoint
    state.save()

    println("\nPaired.")
    println("  peer id : ${pairing.peerId}")
    println("  issuer  : ${pairing.issuer}")
    println("  trusted : ${pairing.issuer in Frameo.trustedIssuers}")
    println("  saved to: ${state.path}")
    println("\nThat file contains your private pairing key. Keep it; do not share it.")

    withSession(state) { session ->
        val info = session.refreshInfo()
        state.frameName = info.name
        state.save()
        printInfo(session)
    }
}

private fun info(state: State) = withSession(state) { printInfo(it) }

private fun printInfo(session: FrameSession) {
    val info = session.info
    println(
        """
        |
        |Frame
        |  name       : ${info.name}
        |  placement  : ${info.placement}
        |  resolution : ${info.width} x ${info.height}
        |  protocol   : ${info.protocolVersion}
        |  peer id    : ${session.pairing.peerId}
        |Permissions
        |  view photos   : ${info.permissions.view}
        |  manage photos : ${info.permissions.manage}
        |  share pairing : ${info.permissions.sharePairing}
        |  backup        : ${info.permissions.backup}
        """.trimMargin(),
    )
}

private fun listMedia(state: State) = withSession(state) { session ->
    val items = session.listMedia()
    println("${items.size} item(s) on ${session.info.name}\n")
    println("%-22s %-9s %-8s %s".format("id", "type", "shown", "captured"))
    for (item in items.take(50)) {
        println(
            "%-22s %-9s %-8s %s".format(
                item.id,
                item.type.name.lowercase(),
                item.visible,
                formatTime(item.capturedAtMillis),
            ),
        )
    }
    if (items.size > 50) println("… and ${items.size - 50} more")
}

private fun grant(state: State, arguments: Arguments) = withSession(state) { session ->
    val manage = arguments.flag("manage")
    session.requestPermission(manage)
    println("Requested ${if (manage) "view + manage" else "view"} access.")
    println("Go to the frame and tap Allow. Waiting up to 5 minutes…")

    val deadline = System.currentTimeMillis() + 5 * 60_000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(2_000)
        val info = session.refreshInfo()
        if (info.permissions.view && (!manage || info.permissions.manage)) {
            println("\nGranted.")
            printInfo(session)
            return@withSession
        }
    }
    println("\nNo response yet. The request stays pending on the frame; run `info` later.")
}

private fun send(state: State, arguments: Arguments) {
    val path = arguments.positional(0) ?: fail("usage: framectl send <file.webp> [--caption C] [--crop]")
    val file = File(path)
    if (!file.isFile) fail("no such file: $path")
    val bytes = file.readBytes()
    if (!looksLikeWebP(bytes)) {
        fail(
            "framectl only sends WebP; the frame accepts nothing else.\n" +
                "Convert first, e.g.  cwebp -q 85 input.jpg -o output.webp",
        )
    }

    withSession(state) { session ->
        val scale = if (arguments.flag("crop")) PhotoScale.CROP else PhotoScale.FIT
        println("Sending ${file.name} (${bytes.size} bytes) to ${session.info.name}…")
        var lastPercent = -1
        val mediaId = session.upload(
            webp = bytes,
            caption = arguments.value("caption") ?: "",
            scale = scale,
            capturedAtMillis = file.lastModified(),
        ) { sent, total ->
            val percent = sent * 100 / total
            if (percent != lastPercent) {
                lastPercent = percent
                print("\r  $percent%")
                System.out.flush()
            }
        }
        println("\rDelivered. The frame confirmed media id $mediaId.")
    }
}

private fun get(state: State, arguments: Arguments) = withSession(state) { session ->
    val id = arguments.positional(0)?.toLongOrNull() ?: fail("usage: framectl get <media-id> [--size N] [--out F]")
    val size = arguments.int("size") ?: 0
    val download = session.fetchMedia(id, size)
    val out = File(arguments.value("out") ?: "frame-$id.${download.extension.ifEmpty { "bin" }}")
    out.writeBytes(download.bytes)
    println("Saved ${download.bytes.size} bytes to ${out.absolutePath}")
    if (download.caption.isNotEmpty()) println("Caption: ${download.caption}")
}

private fun forget(state: State) {
    state.pairing = null
    state.endpoint = null
    state.save()
    println("Pairing removed. The identity key is kept; use `pair` with a new friend code.")
}

// --- plumbing ---------------------------------------------------------------------------

/**
 * Opens a session, re-discovering the frame if its saved address has gone stale. DHCP moves
 * frames around, so a failed connect is a reason to look again, not to give up.
 */
private fun withSession(state: State, body: (FrameSession) -> Unit) {
    val identity = state.identity ?: fail("not paired yet; run: framectl pair <friend-code>")
    val pairing = state.pairing ?: fail("not paired yet; run: framectl pair <friend-code>")

    val session = openSession(state, identity, pairing)
    session.use {
        it.refreshInfo()
        body(it)
    }
}

private fun openSession(
    state: State,
    identity: app.framealt.protocol.FrameIdentity,
    pairing: FramePairing,
): FrameSession {
    state.endpoint?.let { saved ->
        try {
            return Frameo.connect(saved, identity, pairing, state.senderName)
        } catch (failure: Exception) {
            println("Saved address $saved did not answer (${failure.message}); re-discovering…")
        }
    }

    val found = JmdnsDiscovery().discover(5_000).firstOrNull { pairing.matches(it.instance) }
        ?: fail("could not find the paired frame on this network")
    println("Found it at ${found.endpoint}")
    state.endpoint = found.endpoint
    state.save()
    return Frameo.connect(found.endpoint, identity, pairing, state.senderName)
}

private fun resolveEndpoint(arguments: Arguments): FrameEndpoint? {
    val host = arguments.value("host")
    if (host != null) {
        val port = arguments.int("port") ?: fail("--host also needs --port")
        return FrameEndpoint(host, port)
    }
    println("Looking for a frame on this network…")
    val frames = JmdnsDiscovery().discover(5_000)
    return when (frames.size) {
        0 -> null
        1 -> frames.single().endpoint.also { println("Found one at $it") }
        else -> {
            println("Several frames found; pick one with --host and --port:")
            frames.forEach { println("  ${it.host}:${it.port}  (${it.instance.take(16)}…)") }
            exitProcess(1)
        }
    }
}

/** RIFF container with a `WEBP` form type. */
private fun looksLikeWebP(bytes: ByteArray): Boolean =
    bytes.size > 12 &&
        bytes.decodeToString(0, 4) == "RIFF" &&
        bytes.decodeToString(8, 12) == "WEBP"

private val AcceptAnyIssuer = object : AbstractSet<String>() {
    override val size: Int get() = 0
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    override fun contains(element: String): Boolean = true
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "—" else FORMATTER.format(Instant.ofEpochMilli(millis))

private val FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private class Arguments(private val tokens: List<String>) {

    fun flag(name: String): Boolean = "--$name" in tokens

    fun value(name: String): String? {
        val index = tokens.indexOf("--$name")
        return if (index >= 0 && index + 1 < tokens.size) tokens[index + 1] else null
    }

    fun int(name: String): Int? = value(name)?.toIntOrNull()

    /** Positional arguments are those not consumed by a `--flag` or `--option value`. */
    fun positional(index: Int): String? {
        val positionals = mutableListOf<String>()
        var skip = false
        for ((i, token) in tokens.withIndex()) {
            if (skip) {
                skip = false
                continue
            }
            if (token.startsWith("--")) {
                val next = tokens.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) skip = true
                continue
            }
            positionals.add(token)
        }
        return positionals.getOrNull(index)
    }
}

private fun usage(): Nothing {
    println(
        """
        |framectl - drive a Frameo frame from the terminal, through the FrameAlt protocol library.
        |
        |  discover [--seconds N]                       find frames via mDNS
        |  probe [--hosts a,b] [--ports 1-65535]        find frames by handshake, when mDNS
        |        [--timeout MS]                         is blocked (outbound TCP only)
        |  pair <friend-code> [--name N]                pair using the code from the frame's
        |         [--host H --port P] [--trust-any]     "Add friend" screen
        |  info                                         show the frame's details and permissions
        |  grant [--manage]                             request photo access (approve on the frame)
        |  list                                         list media on the frame
        |  send <file.webp> [--caption C] [--crop]      send one photo
        |  get <media-id> [--size N] [--out F]          download one item
        |  forget                                       drop the saved pairing
        |
        |State lives in ~/.framealt/framectl.properties and contains a private pairing key.
        """.trimMargin(),
    )
    exitProcess(1)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
