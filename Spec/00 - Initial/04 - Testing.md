# Testing & Verification

The protocol is reverse-engineered, cryptographic, and talks to a device we cannot
easily inspect. A bug is more likely to look like "the frame is asleep" than like a
crash. So the testing strategy is front-loaded: **prove the protocol on a laptop JVM
before a single byte reaches the frame.**

---

## 1. Strategy

| Layer | Where | What it proves | Speed |
|---|---|---|---|
| Crypto vectors | `protocol/` JVM tests | Our NaCl composition matches real NaCl | ms |
| PACE vectors | `protocol/` JVM tests | Pairing maths is byte-exact | ms |
| Wire codec | `protocol/` JVM tests | Protobuf subset, envelope, multipart, ZigZag | ms |
| Mock frame | `protocol/` JVM tests | Handshake and message state machines | ms |
| Cross-validation | script + JVM tests | Our crypto agrees with an *independent* implementation | seconds |
| Standalone mock frame | `:mockframe` JVM app | The Android app end-to-end, no real frame | manual |
| Instrumented | `app/` androidTest | Image pipeline, Room, queue state machine | seconds |
| Manual | real frame | Everything the above cannot | minutes |

**The ordering rule:** §2 → §3 → §4 → §5 must all be green before §8 touches the frame.

## 2. Crypto vectors

The trap with hand-composed crypto is self-consistency: an implementation that seals and
opens its own messages happily can still be wrong in a way that no round-trip test
catches. Every crypto test here therefore uses vectors produced by **someone else's**
implementation.

Required vectors, all sourced — never invented:

| Function | Source |
|---|---|
| `crypto_secretbox` / open | libsodium or NaCl published test vectors |
| `crypto_box` / open | the classic NaCl documentation example (Alice/Bob keypair, 24-byte nonce, 131-byte message) |
| `crypto_box_beforenm` | libsodium test suite |
| `HSalsa20` | RFC/NaCl `hsalsa20` vectors |
| `XSalsa20` keystream, 24-byte nonce | libsodium `stream` vectors |
| `X25519` | RFC 7748 §5.2 test vectors, including the iterated ones |
| `Ed25519` verify | RFC 8032 §7.1 vectors, plus at least one *invalid* signature that must be rejected |

Store them as JSON under `protocol/src/test/resources/vectors/`, each file naming its
origin in a header field. Add negative cases: a flipped MAC bit must fail to open; an
all-zero X25519 result must be rejected.

**Generating them independently.** A ~30-line Python script using `pynacl` (libsodium
bindings) emits every vector above as JSON. That is the cheapest way to get an
implementation that shares no code lineage with either our Kotlin or the Go reference.
Keep it in `tools/gen-vectors.py` and commit both the script and its output.

## 3. PACE vectors

The reference repo ships 20 synthetic vectors at
`internal/protocol/testdata/pace.json`, each:

```json
{ "client": "<32-byte hex>", "frame": "<32-byte hex>", "channel": "<32-byte hex>",
  "scalar": "<32-byte hex>", "challenge": "<97-byte hex>",
  "response": "<65-byte hex>", "proof": "<33-byte hex>" }
```

All computed with the friend code `"12 34 56 78 90"`. `channel` is the MDG session key
`K`. The test drives the pure function from protocol §4.2:

```
paceResponse(challenge, code, clientPub, framePub, channel, scalar)
  → (response, expectedReply)
must equal (vector.response, vector.proof)
```

All 20 must pass. Note the fixture's origin in the test file (see
[`00 - Overview.md`](00%20-%20Overview.md) §8 for why that matters).

Also assert the input guards: codes shorter than 7 or longer than 20 digits, codes with
non-digits, a challenge of the wrong length, and a challenge whose first byte is not
`0x03` must each fail with a distinct error.

If regenerating rather than copying: build the Go reference, add a test that prints
`pairingResponse` inputs and outputs as JSON for 20 random inputs, and capture that.

## 4. Wire codec

- **ZigZag**: `sint64(1, -1) == [0x08, 0x01]`. Round-trip `42`, `-42`,
  `9_007_199_254_740_993`, `Long.MIN_VALUE`, `Long.MAX_VALUE`, `0`.
- **Signedness separation**: a message carrying a sint64 media ID and a plain-varint
  receipt in the same body decodes both correctly.
- **Malformed input rejection** — each of these is a parse error, not a silent success:
  `[0x00]` (field number 0), `[0x08, 0x80]` (truncated varint), `[0x0A, 0x05, 'a']`
  (length-delimited overrun), `[0x0D, 0x01]` (truncated fixed32), `[0x0B]` (unsupported
  wire type 3).
- **Repeated fields**: accessors return the last; the list accessor returns all in order.
- **Unknown fields** are preserved and ignored.
- **Envelope**: round-trip version/kind/body; a body shorter than 8 bytes is an error.
- **Multipart**: an envelope of 16 417 bytes splits into exactly 2 parts; 100 KB splits
  into ceil(100 000 / 16 316) parts; reassembly rejects an out-of-order index, a changed
  ID, a changed total, an oversized total (> 32 MiB), an undersized total (< 8), and
  overflow past the announced size.
- **Packed IDs**: a packed list of `[1, -1, 0x7FFF_FFFF_FFFF_FFFF]` encodes and decodes.
- Property test: random field maps survive encode → decode unchanged.

## 5. Mock frame

A Kotlin test double implementing the **frame's** side of §3 and §6, driven over an
in-memory socket pair (and over a real loopback socket for the standalone variant).

Covers:

- Full handshake: `TELL/WELC/HELO/COOK/VOCH/REDY` with a generated frame identity and a
  certificate signed by a test issuer injected into the trust list.
- Certificate rejection: wrong subject, bad signature, unknown issuer, wrong length —
  each must abort the connection with its own error.
- Replay rejection: re-send a `MESG` with a sequence already seen.
- Sequence discipline: assert the client's `HELO`=0, `VOCH`=1, first `MESG`=2.
- Kind 1 → the client must auto-reply kind 3 with the sender name.
- Kind 10 carrying field 16 → the client must auto-ack with kind 6.
- Upload: metadata correctness (all of fields 1,2,3,4,5,6,9,10,12 present and
  well-formed), exact chunk boundaries at 16 316 (and 924 when the mock announces
  protocol version 3), receipt only on the final chunk, and the client blocking until
  the receipt arrives.
- Upload failure: a receipt carrying error 12 surfaces as *frame limit reached*.
- List: a response with negative media IDs, all three media types, and a mixture of
  visible flags maps correctly.
- Download: metadata-then-segments, a segment arriving before metadata (error), an
  oversized payload (error), an empty payload (error).
- Multipart in both directions with a message that requires 3 parts.
- Timeouts: a mock that never answers must produce the documented timeout, not a hang.

> **What the mock frame does not prove:** cryptographic correctness. It uses our own
> crypto for the frame side, so a symmetric bug passes. §2 and §3 are what cover that.
> Say so in a comment at the top of the file so nobody is lulled.

### 5.1 Standalone mock frame

A `:mockframe` JVM entry point that runs the same mock on a real TCP port and advertises
`_frameo._tcp.local` (jmDNS, test-scope only). This lets the whole Android app —
discovery, pairing with a fixed friend code, sending, gallery — be exercised against a
laptop with no real frame in the loop. Worth the afternoon it costs: it turns "did I
break sending?" from a 10-minute physical test into a 30-second one.

## 6. Questions the tests must answer against a real frame

These are unknowns the spec records but cannot resolve on a laptop. Run each once,
write the answer back into [`01 - Protocol.md`](01%20-%20Protocol.md) §9 or
[`02 - Architecture.md`](02%20-%20Architecture.md) §7.3.

1. **Does the frame de-duplicate?** Send a photo, note the media ID. Re-send the exact
   same bytes with the *same* `mediaId` and `contentId`. Does the frame now hold one
   item or two? Then repeat with a fresh `mediaId` but the same `contentId`. This
   decides whether §7.3's retry strategy is sufficient or needs a reconciliation pass.
2. **What is in kind 10?** Log the body in a debug build across a normal session.
3. **Chunk ceiling.** Confirm 16 316 is accepted on protocol 18 and that a 16 317-byte
   chunk is rejected — i.e. that our limit is the real one.
4. **Capture date handling.** Send a photo with a 2015 capture date and confirm the frame
   orders it by capture date, not receive date.
5. **Fit vs crop.** Send the same photo with `scale = 1` and `scale = 2`; confirm which
   is which visually.
6. **Does the frame serve while asleep?** Observed 2026-09-21: with the frame asleep, it
   neither answered mDNS nor appeared on the LAN. Confirm whether a sleeping frame stops
   advertising, stops accepting TCP, or both — and whether anything wakes it. This decides
   whether an overnight send queue simply retries until morning (D7 handles that) or whether
   the app should say "the frame is asleep" rather than "the frame is unreachable", which
   are very different messages to the user. Until this is answered, treat *every* absence as
   retryable rather than terminal.
   Observed 2026-09-22 with the frame awake: it answered both mDNS and a TCP `TELL`
   (see [`01 - Protocol.md`](01%20-%20Protocol.md) §2). That only covers the awake case;
   the sleeping case is still open.
7. **Permission revocation.** Revoke photo access on the frame and confirm the client
   gets error 1 or 5 rather than hanging.

## 7. Android instrumented tests

- **Image pipeline**: JPEG, PNG, WebP, HEIC and a rotated (EXIF orientation 6) JPEG each
  produce a correctly oriented WebP at the expected dimensions for a 1280×800 panel; a
  51 MP source is rejected before decode; a CMYK/corrupt file fails cleanly.
- **Room**: schema migrations (export schemas from the first release), queue state
  transitions, `sent_ledger` uniqueness.
- **Queue state machine**: fake client that fails the first N attempts — verify backoff,
  `attempts` increment, terminal `FAILED` at 10, and that `mediaId`/`contentId` are
  stable across retries.
- **Share intent**: `ACTION_SEND_MULTIPLE` with 3 images and 1 PDF enqueues 3 and reports
  1 skipped.
- **Identity store**: key survives process restart; a second read returns identical
  bytes; the stored blob is not the raw key.

## 8. Manual test plan (real frame)

Run in order. Each line is pass/fail, recorded with the frame's firmware and protocol
version.

**Pairing**
1. Cold install → discovery finds the frame → pairing with a fresh code succeeds.
2. Pairing with a deliberately wrong code shows the friend-code message, not a crash.
3. Pairing with an expired code (wait for the frame's code to rotate) shows the same.
4. Force-quit mid-pairing, reopen: no half-written pairing is left behind.

**Sending**
5. Send 1 photo. It appears on the frame with the right orientation and caption.
6. Send 50 photos in one batch. All 50 arrive. Note elapsed time and any thermal effect.
7. Lock the screen during a 50-photo send → completes.
8. Turn Wi-Fi off mid-send → queue pauses with the waiting message; turn it back on →
   resumes and completes with no duplicates on the frame.
9. Force-stop the app mid-send → reopen → remaining photos complete.
10. Enable airplane mode for 10 minutes mid-send → resumes afterwards.
11. Unplug the frame mid-send → the queue reports the frame unreachable and retries once
    it is back.
12. Send with *Show the whole photo* off → confirm the crop.
13. Send a portrait photo and a panorama. (HEIC and Motion Photo: out of scope for v1, see
    Plan §3 item 8.)
14. Re-send a photo already sent → duplicate badge appears; sending anyway behaves as
    §6 question 1 determined.

**Gallery**
15. Request photo access → approve on the frame → gallery populates.
16. Thumbnail grid scrolls smoothly at ~200 items; memory stays bounded.
17. Open a photo → full-size loads, and every action is on screen without scrolling.
18. Deny/ignore the access request → the timeout copy appears and nothing is stuck.

**Recovery**
19. Remove the frame in Settings → Home returns to the empty state → re-pair works.
20. Move the frame to a different IP (reboot the router / change the DHCP lease) →
    rediscovery finds it without user action.

**Hygiene**
21. Pairing repeatedly during development adds entries to the frame's friend list.
    Periodically prune them on the frame.
22. Do not fill the frame's storage during testing — error 12 means real cleanup work.

### 8.1 Results — Phase 3 sending, 2026-09-23

Pixel 6a on Android 17, frame on protocol 18, same Wi-Fi. Driven over adb with the debug
build's `DebugPickActivity`. It stands in for the system photo picker, so everything
from Review & Send onwards is the real path. The test photos are labelled 4000 × 3000
JPEGs of about 1 MB each, which prepare to WebPs of a few hundred KB. "Arrived" means
the frame's kind 6 receipt came back for every photo. Whether each is *displayed*
correctly needs a look at the frame, and is marked as such.

| # | Result | Notes |
|---|---|---|
| 5 | ✅ | 4 photos sent through the real picker by hand; the owner confirmed they appeared. |
| 6 | ✅ | 50/50 arrived. 50 prepared in 10 s; the upload took 35.6 s (~0.7 s per photo) in one session. No thermal effect noticed. |
| 7 | ✅ | Screen locked 4 s into test 6's batch; the drain finished while locked, as a `dataSync` foreground service. |
| 8 | ✅ | Wi-Fi off after 3 sent: the photo in flight got `SocketException`, went back to `PREPARED` and was charged 1 attempt. Wi-Fi back on → the queue restarted within 0.5 s and sent the remaining 9. **Bug found and fixed:** Home kept saying "Ready" and "waiting to go … Send now" with Wi-Fi off; it now follows Wi-Fi. Duplicates on the frame: not yet checked by eye. |
| 9 | ✅ with a caveat | Force-stopped after 4 sent. The process restarted by itself about 2 s later (cause not identified) and sent the remaining 8 in the background, with no foreground service (`ForegroundServiceStartNotAllowedException`, handled). So "remaining photos complete" holds, but "resumes on reopen" was not exercised on its own. |
| 10 | ✅ | Airplane mode on for 3 min 27 s after 3 sent; shortened from 10 min by agreement. Home showed "No Wi-Fi" and "9 photos waiting for Wi-Fi at home." Wi-Fi reconnected 3 s after airplane mode ended, and the queue sent the remaining 9. |
| 11 | not run (by decision) | Judged too rough an edge case to be worth the setup. **Expected behaviour, traced from the code:** a frame that loses power sends no TCP reset, so the photo in flight fails on a write error or, if all its data was sent, on the 120 s receipt timeout. It is charged 1 attempt and put back in line. Reconnects then fail (connect timeout, then rediscovery finds nothing) without charging any photo, and WorkManager backs off from 30 s, doubling. Home shows "N photos waiting to go to <frame> · Send now", with no error. **Rough edge:** nothing notices the frame coming back, as there is no signal like the Wi-Fi callback. The queue resumes at the next backoff retry, which after a long outage can be many minutes away (WorkManager caps it at 5 h), or at once on *Send now* or on opening the app. If the power went out after the last chunk but before the receipt, the photo may arrive twice (§6 question 1). |
| 12 | ✅ | Crop card sent with *Show the whole photo* off. The owner confirmed on the frame that it was cropped to fill the screen. |
| 13 | ✅ (narrowed) | Portrait 3000 × 4000 and a 12000 × 2000 panorama arrived and were confirmed on the frame; earlier, a JPEG with EXIF orientation 6 and a transparent PNG. **HEIC and Motion Photo are out of scope for v1** (Plan, Phase 3 gate outcome). |
| 14 | ✅ badge | Reopening a folder already sent shows "Already sent" on those tiles and "2 of these were sent before." The pipeline is deterministic: the same source gives the same content ID. What the frame does on a re-send is still §6 question 1. |

### 8.2 Results — Phase 4 share sheet, 2026-09-23

Pixel 6a, Android 17. Google Photos → a device folder of 20 labelled JPEGs → Select → Share
→ Next → FrameAlt, driven over adb. The debug build's `READ_MEDIA_IMAGES` was revoked
first, so only the share's own grant could make the photos readable.

| Run | Result |
|---|---|
| FrameAlt running | ✅ 20 received (all images), prepared in 3.3 s. After Send, Google Photos was back in front with "20 photos queued for …". The frame confirmed 20/20 in 11.3 s while FrameAlt was in the background. |
| FrameAlt force-stopped first | ✅ The share started it cold. 20 prepared in 3.6 s, back in Google Photos after Send, 20/20 confirmed in 12.1 s. |

Not yet run on the device: a mixed share (photos plus a PDF; the classification is
unit-tested in `SharedItemsTest`), and sharing before any frame is paired.

### 8.3 Results — Phase 5 gallery, 2026-09-23

Pixel 6a, Android 17, frame on protocol 18.

| # | Result | Notes |
|---|---|---|
| 15 | ✅ | Request access (view + manage, kind 27 type 3) → the owner tapped Allow on the frame → granted about 12 s later, and the gallery listed 1458 items. |
| 16 | not measured | The grid scrolls through a 1330-item library, and thumbnails load as tiles appear. Smoothness and memory were not measured. |
| 17 | ✅ | Preview: full-size fetch, capture date ("Taken 17 Sep 2015", from the EXIF date the pipeline sent) and received date. *Save to phone* wrote the frame's copy to `Pictures/FrameAlt` (20.7 KB WebP). Hide, Show and *Show on frame now* all completed. Display now has no receipt, so whether it showed needs a look at the frame. |
| 18 | not run | Deny or ignore the access request. |
| count | ⏳ | The app lists 1330 items (1328 photos, 2 videos) after cleanup; still to compare with the official Frameo app. |
| cleanup | ✅ | "Select photos sent from this phone" matched 128 of the 133 sends the phone recorded. The other 5 were no longer on the frame; the selection cannot include anything the phone did not record. Delete removed the 128 in about 1 s; a re-list from the frame dropped from 1458 to 1330, exactly 128. **Left behind:** the 2 photos `framectl` sent from the desktop, which the phone has no record of. |

Also confirmed from the frame's own thumbnails: a transparent PNG arrives flattened onto
white, and a JPEG with EXIF orientation 6 arrives upright. The frame stores and lists items
under the media ID the client generated, which is what the "sent from this phone" selection
relies on.

## 9. CI

GitHub Actions on push:

1. `./gradlew :protocol:test` — vectors, codec, mock frame. This is the gate that matters.
2. `./gradlew :app:assembleDebug :app:lintDebug`
3. `./gradlew :app:testDebugUnitTest`

No emulator and no device tests in CI; instrumented tests run locally. If the protocol
tests ever go red, nothing else matters — fix that first.
