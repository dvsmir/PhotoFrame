# FrameAlt — Overview & Scope

> Personal Android app that sends photos to a Frameo digital photo frame over the
> local network, with no Frameo account, no cloud, and no subscription limits.

Derived from [`Intent.md`](Intent.md).

**Name (2026-09-23).** Users see the app as **Photo Frame**: the launcher label, the share
sheet, and every sentence in the UI. The package, namespace and application ID are
`dev.dsmirnov.photoframe` (debug builds `dev.dsmirnov.photoframe.debug`); the protocol
library is `dev.dsmirnov.photoframe.protocol` and the CLI `dev.dsmirnov.photoframe.framectl`.
Until 2026-09-23 all of these were `app.framealt`. The move changed the application ID,
so it installed as a new app that had to be paired again. "FrameAlt" remains the name of the
project, the repository, some class names (`FrameAltApp`) and the logcat tag. Wherever
these documents quote UI copy that says "FrameAlt", the app shows
"Photo Frame". The icon is a wooden photo frame around a small landscape, as an adaptive
icon with a monochrome layer for themed icons.

---

## 1. Background

[Frameo](https://frameo.net) digital photo frames ship with a companion phone app.
That app requires an account, routes transfers through Frameo's cloud relay
(Trifork SecureDeviceGrid), and gates batch sending behind a subscription — the free
tier sends about 10 photos at a time.

The frames themselves expose the **full protocol on the local network**. A frame on
your Wi-Fi advertises itself over mDNS and accepts an encrypted TCP session from any
peer that has completed the frame's own on-device pairing ("Add friend" → friend code).
Pairing produces a long-lived key pair stored on the client. Nothing in that path
touches Frameo's servers, needs an account, or enforces a photo count.

[`yasoob/frameo-client`](https://github.com/yasoob/frameo-client) ("Frameo Local") is a
Go desktop app that implements exactly this. It is the reference implementation for
this project: its protocol layer (~900 lines across `internal/protocol` and
`internal/device`) is complete, tested against synthetic vectors, and proven against
real frames on protocol version 18.

FrameAlt is that capability as a native Android app — so photos go straight from the
phone that took them to the frame, in one share-sheet tap.

## 2. Confirmed product decisions

These were decided up front and are binding for v1. Changing one is a spec change, not
an implementation detail.

| # | Decision | Value | Consequence |
|---|---|---|---|
| D1 | Network topology | **LAN only** — phone and frame on the same Wi-Fi | No relay, no internet path, no SecureDeviceGrid reverse engineering. Sending only works at home. |
| D2 | v1 feature scope | **Send + gallery with management** | Upload path, plus a gallery that shows what is on the frame *and* manages it: delete, hide/show, display now. The gallery needs the frame's *view* and *manage* permissions, requested together and approved once on the frame; sending needs pairing only. (Revised 2026-09-23: a read-only gallery made little sense.) |
| D3 | Protocol implementation | **Pure Kotlin port + BouncyCastle** | No NDK, no JNI, no Go toolchain. Protocol lives in a plain Kotlin/JVM module that unit-tests on the desktop JVM in milliseconds. |
| D4 | Send entry points | **Android share sheet + in-app photo picker** | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/*`, plus `PickMultipleVisualMedia`. No watched-album auto-sync, no in-app camera. |
| D5 | Image preparation | **Match frame resolution** | Downscale so the photo just covers the frame panel (the frame reports its own width/height), encode WebP q≈85. |
| D6 | Frame count | **One frame** | Single-frame UI. The storage model is still a list keyed by peer ID, so a second frame is additive rather than a rewrite. |
| D7 | Interrupted sends | **Background queue + retry** | Uploads run in a `dataSync` foreground service driven by WorkManager; the queue is persisted and survives app death, Wi-Fi loss, and a sleeping frame. |
| D8 | Video | **Out of scope for v1** | The reference client does not implement video upload and the frame-side format is unverified. The gallery may still *show* that a video exists on the frame. |

## 3. Goals

- **G1** — Send an arbitrary number of photos to the frame in one action, with no
  account, no subscription, and no per-batch cap beyond the practical ones in §5.
- **G2** — Sending from Google Photos (or any gallery app) is a share-sheet tap:
  share → FrameAlt → Send. No export, no intermediate file, no desktop involved.
- **G3** — A send that starts always finishes or reports why. Walking out of Wi-Fi
  range, locking the screen, or killing the app must not silently lose photos.
- **G4** — First-run pairing is a guided flow that works from the frame's friend code
  alone, and never needs a manually typed IP address on a normal home network.
- **G5** — Show what is already on the frame, so the user can tell whether a photo
  landed and avoid re-sending duplicates.

## 4. Non-goals

- **N1** — Sending when away from home. No relay, no port forwarding, no VPN support.
  (The queue in D7 covers *picking* photos while away; the *send* still waits for
  home Wi-Fi.)
- **N2** — Video upload (D8).
- **N3** — Managing the frame *itself*: no album editing, brightness/sleep/settings, or
  firmware actions. (Managing the *photos* on it, meaning delete, hide/show and display
  now, is in scope since 2026-09-23; see D2.)
- **N4** — Replacing the official app entirely. The frame's own settings, Wi-Fi setup
  and friend management stay on the frame; the official app stays installed if wanted.
- **N5** — Play Store distribution, multi-user accounts, telemetry, analytics, or crash
  reporting to any third party.
- **N6** — Frameo protocol versions below 13. The gallery requires ≥13; the reference
  client tolerates older frames for upload only, and we keep that tolerance but do not
  test it.

## 5. Hard limits inherited from the protocol

These are not FrameAlt choices; they come from the wire format and the frame. Full
detail in [`01 - Protocol.md`](01%20-%20Protocol.md).

| Limit | Value | Where it bites |
|---|---|---|
| Encoded photo size | ≤ 32 MB | Measured after WebP encoding. D5 makes this unreachable in practice. |
| Source pixel count | ≤ 50 MP | Rejected before decode. |
| Application message | ≤ 16 416 bytes | Anything larger is split by the multipart wrapper (kind 30). |
| Upload data chunk | 16 316 bytes (924 for protocol < 4) | Payload per `kind 5` message. |
| MDG record | 8 … 65 535 bytes | 2-byte big-endian length prefix on the wire. |
| Photo format on the wire | WebP only | We always encode to WebP before sending. |
| Frame error codes | 0–12, see protocol doc §7 | Must be surfaced as human text, never as a number. |

## 6. Target platform

| Item | Value | Rationale |
|---|---|---|
| Devices | Pixel 6, Pixel 10 | From the intent. Both run Android 17 as of 2026-09. |
| `minSdk` | 31 (Android 12) | Pixel 6's launch OS; costs nothing and keeps older family phones viable. `Bitmap.CompressFormat.WEBP_LOSSY` needs API 30, so 31 is already safe. |
| `compileSdk` | 37 (Android 17) | Build against the OS the devices actually run. |
| `targetSdk` | 36 initially, 37 as a tracked follow-up | See the local-network-permission trap in [`02 - Architecture.md`](02%20-%20Architecture.md) §8. Declaring `ACCESS_LOCAL_NETWORK` while targeting ≤36 is explicitly wrong. |
| Language / UI | Kotlin, Jetpack Compose, Material 3 | — |
| `applicationId` | `dev.dsmirnov.photoframe` (placeholder, trivially changed) | — |
| Distribution | Debug/release APK installed over ADB or by direct download | See §8. |

Pixel 6 leaves Google's support window in **October 2026**. It keeps working; it just
stops receiving OS updates. Nothing in this spec depends on that.

## 7. Success criteria (v1 definition of done)

1. From a cold install, pairing with the frame succeeds using only the friend code
   shown on the frame, with no manual IP entry, on a normal home Wi-Fi.
2. Selecting 50 photos in Google Photos → Share → FrameAlt → Send puts all 50 on the
   frame, with a progress notification throughout and no user interaction after the
   Send tap.
3. Locking the phone mid-send does not interrupt the send.
4. Turning Wi-Fi off mid-send pauses the queue; turning it back on resumes it and the
   batch completes without duplicates on the frame.
5. Force-stopping the app mid-send leaves the queue intact; reopening (or the next
   WorkManager run) finishes the remaining photos.
6. The gallery lists the frame's media after the frame owner approves the access
   request, and the count matches what the official app shows.
7. Every failure path shows a sentence a non-engineer can act on — never a stack
   trace, an error code, or "something went wrong".
8. The protocol module's JVM test suite passes, including all 20 PACE vectors and the
   mock-frame end-to-end handshake.

## 8. Legal & distribution notes

- **`yasoob/frameo-client` ships no LICENSE file.** Absent a license the default is all
  rights reserved. We are re-implementing the protocol in Kotlin for personal use
  rather than copying or redistributing its code, and a wire protocol itself is not
  copyrightable. Do not vendor its Go source into this repository, do not publish
  FrameAlt derived from it without sorting the licensing out first, and keep the
  reference clone outside the repo tree. The one artifact worth copying is
  `internal/protocol/testdata/pace.json` (20 synthetic PACE vectors); treat it as a test
  fixture, note its origin in the test file, or regenerate equivalents locally — see
  [`04 - Testing.md`](04%20-%20Testing.md) §3.
- Frameo's protocol is reverse-engineered. A firmware update can change it. This is a
  personal tool; treat any break as expected maintenance, not a bug.
- **Android developer verification**: Google is phasing in identity verification for
  apps installed outside Play — enforcement began 2026-09-30 in Brazil, Indonesia,
  Singapore and Thailand and expands globally during 2027. Installing your own debug
  builds over ADB is unaffected. If you later hand the APK to family, the free *limited
  distribution* account type covers up to 20 devices without a government ID. Settle
  this before sharing beyond your own devices.
- Frameo is a trademark of Frameo ApS. FrameAlt is an unaffiliated personal tool; do not
  use Frameo's name or branding in the app name, icon, or any listing.

## 9. Glossary

| Term | Meaning |
|---|---|
| **Frame** | The Frameo digital photo frame — an Android device running Frameo's firmware. |
| **MDG** | Trifork *Mobile Device Gateway* / SecureDeviceGrid — the transport Frameo uses. Its LAN mode is what we speak. |
| **Peer ID** | A frame's 32-byte Curve25519 public key, hex-encoded (64 chars). The primary key for a pairing. |
| **Friend code** | 7–20 digit code the frame shows under "Add friend". The PACE password. Single-use and short-lived. |
| **PACE** | Password Authenticated Connection Establishment — the pairing handshake that turns the friend code into a trusted long-term key relationship. |
| **Issuer** | An Ed25519 key that signs frame certificates. ~250 known issuer keys are embedded in the client as the trust root. |
| **Receipt** | A caller-generated 63-bit ID attached to a request; the frame echoes it in a `kind 6` message to confirm the operation landed. |
| **Media ID** | Signed 64-bit identifier of an item on the frame. Negative values are valid. Never round-trip one through a float. |
