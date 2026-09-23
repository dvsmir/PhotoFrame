package app.framealt.protocol

import app.framealt.protocol.client.FrameErrorCode
import app.framealt.protocol.client.FrameException
import app.framealt.protocol.client.FramePermissions
import app.framealt.protocol.client.FrameoClient
import app.framealt.protocol.client.MediaType
import app.framealt.protocol.client.PhotoScale
import app.framealt.protocol.crypto.CryptoRandom
import app.framealt.protocol.crypto.Curve25519
import app.framealt.protocol.mock.MockFrame
import app.framealt.protocol.mock.mediaItem
import app.framealt.protocol.net.JavaSocketFactory
import app.framealt.protocol.pairing.PaceExchange
import app.framealt.protocol.pairing.PairingException
import app.framealt.protocol.transport.MdgTransport
import app.framealt.protocol.transport.Metadata
import app.framealt.protocol.transport.TransportTimeouts
import app.framealt.protocol.util.Hex
import app.framealt.protocol.wire.Multipart
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end tests against [MockFrame] over real loopback TCP.
 *
 * These cover the protocol's *behaviour*: handshake sequencing, certificate trust, the
 * message state machines, chunk boundaries and multipart. Cryptographic correctness is
 * established separately by `NaclVectorTest` and `PaceTest`.
 */
class SessionTest {

    private val timeouts = TransportTimeouts(connectMillis = 4_000, handshakeMillis = 4_000)

    private fun MockFrame.connect(
        identityPrivateKey: ByteArray = CryptoRandom.x25519PrivateKey(),
        protocol: String = Metadata.PROTOCOL_SESSION,
        expectedPeerHex: String? = null,
        expectedIssuerHex: String? = null,
    ): MdgTransport = MdgTransport.connect(
        socketFactory = JavaSocketFactory,
        host = host,
        port = port,
        identityPrivateKey = identityPrivateKey,
        protocol = protocol,
        expectedPeerHex = expectedPeerHex,
        expectedIssuerHex = expectedIssuerHex,
        trustedIssuers = trustedIssuers,
        timeouts = timeouts,
    )

    // --- handshake --------------------------------------------------------------------

    @Test
    @DisplayName("handshake completes and the frame's details come back")
    fun handshakeAndInfo() {
        MockFrame(frameName = "Kitchen", placement = "Shelf", width = 1920, height = 1080).start().use { frame ->
            frame.connect().use { transport ->
                assertEquals(frame.peerHex, transport.framePublicKeyHex)
                assertEquals(frame.issuerHex, transport.issuerHex)

                val client = FrameoClient(transport, senderName = "Dmitrii's phone")
                val info = client.refreshInfo()

                assertEquals("Kitchen", info.name)
                assertEquals("Shelf", info.placement)
                assertEquals(1920, info.width)
                assertEquals(1080, info.height)
                assertEquals(18, info.protocolVersion)
                assertTrue(info.permissions.view)

                // The frame asked who we were; the client must have answered unprompted.
                // That reply is fire-and-forget, so wait for it rather than racing the close.
                assertTrue(frame.awaitClientName(), "the client never answered the frame's kind-1")
                assertEquals(listOf("Dmitrii's phone"), frame.clientNames.toList())
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("a saved pairing that names a different frame is refused")
    fun peerPinning() {
        MockFrame().start().use { frame ->
            val wrongPeer = Hex.encode(Curve25519.scalarMultBase(CryptoRandom.x25519PrivateKey()))
            val failure = assertFailsWith<ProtocolException> { frame.connect(expectedPeerHex = wrongPeer) }
            assertTrue(failure.message!!.contains("differs from the saved pairing"))
        }
    }

    @Test
    @DisplayName("a changed certificate issuer is refused even though the frame key matches")
    fun issuerPinning() {
        MockFrame().start().use { frame ->
            val otherIssuer = "00".repeat(32)
            val failure = assertFailsWith<ProtocolException> {
                frame.connect(expectedPeerHex = frame.peerHex, expectedIssuerHex = otherIssuer)
            }
            assertTrue(failure.message!!.contains("issuer changed"))
        }
    }

    @Test
    @DisplayName("an issuer outside the trust list is refused on a first connection")
    fun unknownIssuer() {
        MockFrame().start().use { frame ->
            val failure = assertFailsWith<ProtocolException> {
                MdgTransport.connect(
                    socketFactory = JavaSocketFactory,
                    host = frame.host,
                    port = frame.port,
                    identityPrivateKey = CryptoRandom.x25519PrivateKey(),
                    protocol = Metadata.PROTOCOL_SESSION,
                    trustedIssuers = setOf("11".repeat(32)),
                    timeouts = timeouts,
                )
            }
            assertTrue(failure.message!!.contains("unrecognised frame certificate issuer"))
        }
    }

    @Test
    @DisplayName("malformed certificates are refused, each for its own reason")
    fun badCertificates() {
        // Each case must fail on the check it targets, not incidentally on an earlier one.
        val cases: List<Triple<String, (MockFrame) -> ByteArray, String>> = listOf(
            Triple("wrong length", { ByteArray(120) }, "128"),
            Triple("wrong subject", { ByteArray(128) }, "issued for a different device"),
            Triple("corrupt signature", { it.certificateWithCorruptSignature() }, "signature is invalid"),
        )
        for ((name, build, expected) in cases) {
            MockFrame().start().use { frame ->
                frame.certificateOverride = build(frame)
                val failure = assertFailsWith<ProtocolException>("$name was accepted") { frame.connect() }
                assertTrue(
                    failure.message!!.contains(expected),
                    "$name failed for the wrong reason: ${failure.message}",
                )
            }
        }
    }

    @Test
    @DisplayName("a replayed record is rejected rather than processed twice")
    fun replayRejection() {
        MockFrame().apply { askForClientName = false }.start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                frame.replayNextRecord = true
                // The frame's answer arrives twice; the duplicate must be refused by name.
                val failure = assertFailsWith<ProtocolException> {
                    client.refreshInfo()
                    client.refreshInfo()
                }
                assertTrue(
                    failure.message!!.contains("replayed"),
                    "rejected for the wrong reason: ${failure.message}",
                )
            }
        }
    }

    // --- pairing ----------------------------------------------------------------------

    @Test
    @DisplayName("PACE pairing succeeds with the frame's friend code")
    fun pairingSucceeds() {
        MockFrame(friendCode = "1234567890").start().use { frame ->
            frame.connect(protocol = Metadata.PROTOCOL_PAIRING).use { transport ->
                PaceExchange.pair(transport, "12 34 56 78 90")
            }
            assertTrue(frame.awaitPairing(), "the frame never confirmed the pairing")
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("PACE pairing with the wrong friend code fails as REJECTED, not as a dropped connection")
    fun pairingRejectsWrongCode() {
        MockFrame(friendCode = "1234567890").start().use { frame ->
            frame.connect(protocol = Metadata.PROTOCOL_PAIRING).use { transport ->
                val failure = assertFailsWith<PairingException> { PaceExchange.pair(transport, "9999999999") }
                assertEquals(PairingException.Reason.REJECTED, failure.reason)
            }
            assertTrue(frame.pairingRejected, "the frame did not see a bad proof")
            assertEquals(1L, frame.pairingSucceeded.count, "the frame wrongly confirmed the pairing")
        }
    }

    @Test
    @DisplayName("a frame that does not advertise PACE v1 is reported as unsupported")
    fun pairingUnsupportedVersion() {
        MockFrame().apply { paceVersions = "2,3" }.start().use { frame ->
            frame.connect(protocol = Metadata.PROTOCOL_PAIRING).use { transport ->
                val failure = assertFailsWith<PairingException> { PaceExchange.pair(transport, "1234567890") }
                assertEquals(PairingException.Reason.UNSUPPORTED_PACE_VERSION, failure.reason)
            }
        }
    }

    // --- uploads ----------------------------------------------------------------------

    @Test
    @DisplayName("an upload delivers exact bytes, metadata and a receipt")
    fun upload() {
        MockFrame().start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()

                val photo = Random(7).nextBytes(50_000)
                val mediaId = client.upload(
                    data = photo,
                    caption = "Sunday walk",
                    scale = PhotoScale.CROP,
                    capturedAtMillis = 1_600_000_000_000,
                    mediaId = 4242,
                    contentId = 777,
                )

                assertEquals(4242, mediaId)
                val received = frame.uploads.single()
                assertContentEquals(photo, received.bytes)
                assertEquals("Sunday walk", received.caption)
                assertEquals("webp", received.extension)
                assertEquals(2, received.scale) // CROP
                assertEquals(1_600_000_000_000, received.capturedAtMillis)
                assertEquals(4242, received.mediaId)
                assertEquals(777, received.contentId)
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("upload chunks respect the 16 316-byte payload boundary")
    fun uploadChunkBoundary() {
        MockFrame().start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                client.upload(Random(8).nextBytes(Multipart.CHUNK * 2 + 17))
            }
            assertEquals(
                listOf(Multipart.CHUNK, Multipart.CHUNK, 17),
                frame.uploadChunkSizes.toList(),
            )
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("frames older than protocol 4 get 924-byte chunks")
    fun uploadLegacyChunkSize() {
        MockFrame(protocolVersion = 3).start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                assertEquals(3, client.refreshInfo().protocolVersion)
                client.upload(Random(9).nextBytes(2_000))
            }
            assertEquals(listOf(924, 924, 152), frame.uploadChunkSizes.toList())
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("a receipt carrying 'frame limit reached' surfaces as a typed error")
    fun uploadRejected() {
        MockFrame().start().use { frame ->
            frame.uploadReceiptError = FrameErrorCode.LIMIT_REACHED
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val failure = assertFailsWith<FrameException> { client.upload(Random(10).nextBytes(1_000)) }
                assertEquals(FrameErrorCode.LIMIT_REACHED, failure.errorCode)
            }
        }
    }

    @Test
    @DisplayName("an empty or oversized photo is refused before anything is sent")
    fun uploadLimits() {
        MockFrame().start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                assertFailsWith<ProtocolException> { client.upload(ByteArray(0)) }
            }
        }
    }

    // --- gallery ----------------------------------------------------------------------

    @Test
    @DisplayName("the media list maps IDs, types and flags, negatives included")
    fun listMedia() {
        MockFrame().start().use { frame ->
            frame.media = listOf(
                mediaItem(id = 1),
                mediaItem(id = -9_007_199_254_740_993, type = MediaType.VIDEO, visible = false),
                mediaItem(id = Long.MAX_VALUE, type = MediaType.GREETING),
            )
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val items = client.listMedia()

                assertEquals(3, items.size)
                assertEquals(listOf(1L, -9_007_199_254_740_993L, Long.MAX_VALUE), items.map { it.id })
                assertEquals(
                    listOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.GREETING),
                    items.map { it.type },
                )
                assertEquals(listOf(true, false, true), items.map { it.visible })
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("a media list too large for one record is reassembled from multipart")
    fun listMediaMultipart() {
        MockFrame().start().use { frame ->
            frame.media = (1..3_000).map { mediaItem(id = it.toLong()) }
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val items = client.listMedia()
                assertEquals(3_000, items.size)
                assertEquals(3_000L, items.last().id)
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("listing without view permission fails before anything is sent")
    fun listRequiresPermission() {
        MockFrame(permissions = FramePermissions(view = false)).start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                assertFailsWith<ProtocolException> { client.listMedia() }
            }
        }
    }

    @Test
    @DisplayName("listing on a pre-gallery frame reports the firmware, not a protocol error")
    fun listRequiresProtocol13() {
        MockFrame(protocolVersion = 12).start().use { frame ->
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val failure = assertFailsWith<ProtocolException> { client.listMedia() }
                assertTrue(failure.message!!.contains("predates the media gallery"))
            }
        }
    }

    @Test
    @DisplayName("a list error from the frame surfaces as a typed error")
    fun listError() {
        MockFrame().start().use { frame ->
            frame.listError = FrameErrorCode.PERMISSION_REQUIRED
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val failure = assertFailsWith<FrameException> { client.listMedia() }
                assertEquals(FrameErrorCode.PERMISSION_REQUIRED, failure.errorCode)
            }
        }
    }

    @Test
    @DisplayName("fetching a photo reassembles its segments and returns its caption")
    fun fetchMedia() {
        MockFrame().start().use { frame ->
            frame.mediaBytes = Random(11).nextBytes(80_000)
            frame.mediaCaption = "From the frame"
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                client.refreshInfo()
                val download = client.fetchMedia(id = -55, size = 400)

                assertContentEquals(frame.mediaBytes, download.bytes)
                assertEquals("webp", download.extension)
                assertEquals("From the frame", download.caption)
            }
            frame.assertHealthy()
        }
    }

    // --- permissions ------------------------------------------------------------------

    @Test
    @DisplayName("requesting photo access sends type 1 and the frame can grant it")
    fun requestPermission() {
        MockFrame(permissions = FramePermissions(view = false)).start().use { frame ->
            frame.grantViewOnRequest = true
            frame.connect().use { transport ->
                val client = FrameoClient(transport, senderName = "phone")
                assertTrue(!client.refreshInfo().permissions.view)

                client.requestPermission(manage = false)
                assertTrue(client.refreshInfo().permissions.view, "the frame granted access but the client missed it")
            }
            assertEquals(listOf(1L), frame.permissionRequests.toList())
            frame.assertHealthy()
        }
    }

    // --- managing (kinds 33, 34, 35) --------------------------------------------------

    private val manager = FramePermissions(view = true, manage = true)

    private fun MockFrame.session(): FrameSession {
        val transport = connect()
        return FrameSession(transport, FrameoClient(transport, "phone")).also { it.refreshInfo() }
    }

    @Test
    @DisplayName("delete removes exactly the given items, negative IDs included, after a receipt")
    fun deleteItems() {
        MockFrame(permissions = manager).start().use { frame ->
            frame.media = listOf(mediaItem(id = 1), mediaItem(id = -9_007_199_254_740_993), mediaItem(id = 3))
            frame.session().use { session ->
                session.delete(listOf(1L, -9_007_199_254_740_993))

                assertEquals(listOf(3L), session.listMedia().map { it.id })
            }
            assertEquals(listOf(34 to setOf(1L, -9_007_199_254_740_993)), frame.manageRequests.toList())
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("hide and show flip the visible flag and leave the item on the frame")
    fun hideAndShow() {
        MockFrame(permissions = manager).start().use { frame ->
            frame.media = listOf(mediaItem(id = 1), mediaItem(id = 2))
            frame.session().use { session ->
                session.setVisibility(listOf(2L), visible = false)
                assertEquals(listOf(true, false), session.listMedia().map { it.visible })

                session.setVisibility(listOf(2L), visible = true)
                assertEquals(listOf(true, true), session.listMedia().map { it.visible })
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("more than 1000 IDs go out as several requests of at most 1000")
    fun deleteIsBatched() {
        MockFrame(permissions = manager).start().use { frame ->
            frame.media = (1..2_345).map { mediaItem(id = it.toLong()) }
            frame.session().use { session ->
                session.delete((1L..2_345L).toList())

                assertTrue(session.listMedia().isEmpty())
            }
            assertEquals(listOf(1000, 1000, 345), frame.manageRequests.map { it.second.size })
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("a refused delete surfaces the frame's error and changes nothing")
    fun deleteRefused() {
        MockFrame(permissions = manager).start().use { frame ->
            frame.media = listOf(mediaItem(id = 1))
            frame.manageError = FrameErrorCode.PERMISSION_REQUIRED
            frame.session().use { session ->
                val failure = assertFailsWith<FrameException> { session.delete(listOf(1L)) }
                assertEquals(FrameErrorCode.PERMISSION_REQUIRED, failure.errorCode)
                assertEquals(listOf(1L), session.listMedia().map { it.id })
            }
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("without manage permission nothing is sent")
    fun manageRequiresPermission() {
        MockFrame(permissions = FramePermissions(view = true)).start().use { frame ->
            frame.media = listOf(mediaItem(id = 1))
            frame.session().use { session ->
                assertFailsWith<ProtocolException> { session.delete(listOf(1L)) }
                assertFailsWith<ProtocolException> { session.setVisibility(listOf(1L), visible = false) }
                assertFailsWith<ProtocolException> { session.displayNow(1L) }
            }
            assertTrue(frame.manageRequests.isEmpty())
            assertTrue(frame.displayedNow.isEmpty())
            frame.assertHealthy()
        }
    }

    @Test
    @DisplayName("display now sends the one ID and expects no receipt")
    fun displayNow() {
        MockFrame(permissions = manager).start().use { frame ->
            frame.media = listOf(mediaItem(id = -42))
            frame.session().use { session ->
                session.displayNow(-42)
                // A following request proves the connection is still in step after 35.
                assertEquals(1, session.listMedia().size)
            }
            assertEquals(listOf(-42L), frame.displayedNow.toList())
            frame.assertHealthy()
        }
    }
}
