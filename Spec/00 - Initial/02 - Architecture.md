# Architecture

How FrameAlt is put together: modules, crypto mapping, session handling, storage, the
image pipeline, the send queue, and the Android platform constraints that shape all of
it.

Read [`01 - Protocol.md`](01%20-%20Protocol.md) first — this document assumes it.

---

## 1. Module layout

Two Gradle modules. The split is not ceremony: it is what lets the entire protocol be
tested on a laptop JVM in milliseconds, with no emulator, no device, and no frame.

```
FrameAlt/
├── settings.gradle.kts
├── gradle/libs.versions.toml          version catalog
├── protocol/                          Kotlin/JVM library — NO Android dependencies
│   └── src/
│       ├── main/kotlin/app/framealt/protocol/
│       │   ├── crypto/     Nacl.kt, HSalsa20.kt, X25519.kt, Nonce.kt, Random.kt
│       │   ├── wire/       Protobuf.kt, Envelope.kt, Multipart.kt
│       │   ├── transport/  MdgTransport.kt, Metadata.kt, Certificate.kt, Issuers.kt
│       │   ├── pairing/    Pace.kt
│       │   ├── client/     FrameoClient.kt, FrameInfo.kt, MediaItem.kt, FrameError.kt
│       │   └── net/        SocketFactory.kt  (interface)
│       └── test/kotlin/…   vectors, codec tests, MockFrame
└── app/                               Android application
    └── src/main/kotlin/app/framealt/
        ├── data/      Room, DataStore, repositories, IdentityStore
        ├── device/    FrameSession, FrameConnectionManager, NsdDiscovery, WifiSockets
        ├── media/     ImagePipeline, ExifReader
        ├── send/      SendQueue, SendWorker, SendNotifications
        ├── ui/        Compose screens, view models, theme
        └── FrameAltApp.kt
```

**Rule:** `protocol/` must not import anything from `android.*` or `androidx.*`. Its
only third-party dependency is BouncyCastle. Anything platform-specific is an interface
there and an implementation in `app/` — currently `SocketFactory` and `FrameDiscovery`.

## 2. Dependencies

| Dependency | Module | Why |
|---|---|---|
| `org.bouncycastle:bcprov-jdk18on` | protocol | X25519, Ed25519, XSalsa20, Poly1305, SHA-2 |
| Kotlin coroutines | both | structured concurrency; sessions are suspend functions |
| Jetpack Compose + Material 3 | app | UI |
| `androidx.activity:activity-compose` | app | photo picker contracts |
| Room | app | pairings + send queue |
| DataStore (Preferences) | app | identity key blob, settings |
| WorkManager | app | background send queue + foreground service |
| Coil | app | thumbnail loading in the picker/review/gallery grids |
| JUnit 5 + kotlin.test | protocol | JVM tests |
| — no DI framework | app | Hand-rolled container. The graph is ~10 objects; Hilt is not worth its build cost here. Revisit if the app grows. |

**BouncyCastle on Android — two rules.** Use only the *lightweight* API
(`org.bouncycastle.crypto.*`, `org.bouncycastle.math.ec.*`), and never register it as a
JCE `Security` provider. Android ships its own repackaged copy as
`com.android.org.bouncycastle`; direct class use is conflict-free, provider registration
is where the classic breakage lives. R8 will strip the unused ~90% of the jar.

## 3. Crypto mapping

The one genuinely delicate part of the port. NaCl `secretbox` and `box` are not
single BouncyCastle calls — they are a documented composition of primitives that BC
does provide. Reference implementation to follow, verbatim in structure:
[`codahale/xsalsa20poly1305`](https://github.com/codahale/xsalsa20poly1305) (Apache-2.0,
pure Java, built on the same BC primitives).

| Need | BouncyCastle |
|---|---|
| X25519, arbitrary point | `org.bouncycastle.math.ec.rfc7748.X25519.scalarMult(k, kOff, u, uOff, r, rOff)` |
| X25519, base point | `…rfc7748.X25519.scalarMultBase(k, kOff, r, rOff)` |
| Ed25519 verify | `org.bouncycastle.math.ec.rfc8032.Ed25519.verify(sig, sigOff, pk, pkOff, msg, msgOff, msgLen)` |
| XSalsa20 stream (24-byte nonce) | `org.bouncycastle.crypto.engines.XSalsa20Engine` + `ParametersWithIV(KeyParameter(key), nonce24)` |
| Poly1305 | `org.bouncycastle.crypto.macs.Poly1305` |
| Salsa20 core (for HSalsa20) | `org.bouncycastle.crypto.engines.Salsa20Engine.salsaCore(20, x, x)` — public static |
| SHA-512 / SHA-256 | `SHA512Digest` / `SHA256Digest`, or `java.security.MessageDigest` |
| CSPRNG | `java.security.SecureRandom` |

### 3.1 `secretbox(key, nonce24, message)`

```
engine = XSalsa20Engine(); engine.init(true, ParametersWithIV(KeyParameter(key), nonce24))
subkey = engine.processBytes(zeros(32))        // first 32 bytes of the keystream
cipher = engine.processBytes(message)          // stream continues, no re-init
mac    = Poly1305(subkey).doFinal(cipher)      // 16 bytes
return mac ‖ cipher                            // NaCl "combined" form
```

`open` mirrors it: derive the same subkey, recompute the MAC over `input[16:]`, compare
in **constant time**, and only then decrypt. A MAC failure is an authentication error,
never a decode error.

### 3.2 `HSalsa20(key, input16)`

BC computes this internally inside `XSalsa20Engine` but does not expose it, so write
~30 lines:

1. Build the 16-word state: `sigma` constants at words 0/5/10/15, the key at
   1–4 and 11–14, `input16` at 6–9.
2. `Salsa20Engine.salsaCore(20, x, x)`.
3. `salsaCore` adds the input words back in (it is the full Salsa20 core, not the
   truncated one HSalsa20 needs) — **subtract them again**: the four sigma words from
   0/5/10/15 and the four input words from 6/7/8/9.
4. Output words `[0, 5, 10, 15, 6, 7, 8, 9]` as 32 little-endian bytes.

Step 3 is the trap. Skipping it produces a plausible-looking 32-byte key that agrees
with nothing.

### 3.3 `box` family

```
beforenm(pk, sk) = HSalsa20(X25519(sk, pk), zeros(16))
box(m, n, pk, sk)      = secretbox(m, n, beforenm(pk, sk))
box_open(c, n, pk, sk) = secretbox_open(c, n, beforenm(pk, sk))
```

### 3.4 Validation rules

- Reject an all-zero X25519 output (small-order point). `X25519.scalarMult` returns a
  boolean in current BC; check it *and* verify the result is not all zeros.
- Clamp private scalars (`k[0] &= 248; k[31] &= 127; k[31] |= 64`) when generating our
  identity and ephemeral keys. X25519 clamps internally on use, so PACE's derived
  scalars need no pre-clamping — match the reference exactly and do not add clamping
  the reference does not do.
- Every comparison of a MAC, proof, or expected value uses a constant-time compare.
- Zero out key material buffers when a session ends. Best-effort on the JVM; do it
  anyway.

### 3.5 Verification gate

No frame is touched until `protocol/` passes: NaCl's own published `box`/`secretbox`
test vectors, and all 20 PACE vectors. See [`04 - Testing.md`](04%20-%20Testing.md).

## 4. Session & connection management

```
FrameConnectionManager                     app/device
 ├─ Mutex per peerId                        one operation at a time (protocol §8.7)
 ├─ endpoint resolution                     cached host:port → on failure, forced
 │                                          rediscovery → retry once → manual fallback
 ├─ SocketFactory                           Wi-Fi-bound (§8.2)
 └─ withSession(peerId) { client -> … }     connect, GetInfo, run block, persist info
```

`withSession` mirrors the reference client's `WithClient`: acquire the lock, resolve the
endpoint, dial, verify, `GetInfo`, persist the refreshed frame info, run the caller's
block, persist again, close. Connections are **not** pooled across operations in v1 —
except within one queue drain, where a single session sends the whole batch. Reconnect
cost is ~1 round trip; correctness is worth more than the milliseconds.

`FrameoClient` exposes suspend functions; all socket I/O is on `Dispatchers.IO`.
Cancellation propagates: cancelling the coroutine closes the socket.

## 5. Persistence

### 5.1 Identity key — the crown jewel

The 32-byte X25519 private key **is** the pairing. Anyone holding it can send to the
frame; losing it means re-pairing with a fresh friend code.

- Stored in DataStore (Preferences) in app-private storage, as a blob wrapped with an
  AES-256-GCM key held in the **Android Keystore** (StrongBox when
  `FEATURE_STRONGBOX_KEYSTORE` is present), `setUserAuthenticationRequired(false)` so
  background sends work with the screen locked.
- **Do not use `androidx.security:security-crypto` / `EncryptedSharedPreferences`.** It
  was deprecated upstream in favour of exactly this pattern, and carried keyset-corruption
  and main-thread-I/O problems on some OEM builds.
- Generated once, lazily, on first use. Never logged, never exported, never included in
  a diagnostics bundle.
- Excluded from cloud and device-to-device backup — see §8.6.

### 5.2 Room schema

```kotlin
@Entity("frames")
data class FrameEntity(
    @PrimaryKey val peerId: String,   // 64-char hex, the frame's public key
    val issuer: String,               // 64-char hex, pinned at pairing
    val host: String,
    val port: Int,
    val name: String,
    val placement: String,
    val width: Int,
    val height: Int,
    val protocolVersion: Int,
    val canView: Boolean,
    val canManage: Boolean,
    val senderName: String,           // shown on the frame
    val lastSeenAt: Long,
)

@Entity("queue_items")
data class QueueItemEntity(
    @PrimaryKey val id: String,       // UUID
    val peerId: String,
    val batchId: String,              // groups one share/pick action
    val sourceUri: String,            // provenance only; never read after PREPARED
    val displayName: String,
    val preparedPath: String?,        // app-private .webp — the durable source of truth
    val preparedBytes: Long,
    val caption: String,
    val fit: Boolean,
    val capturedAtMs: Long,
    val mediaId: Long,                // generated ONCE, reused across retries
    val contentId: Long,              // sha256-derived, reused across retries
    val state: String,                // see §7.1
    val sentBytes: Long,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity("sent_ledger", primaryKeys = ["peerId", "contentId"])
data class SentEntity(val peerId: String, val contentId: Long, val sentAt: Long, val displayName: String)
```

`sent_ledger` powers the "you already sent this one" hint on the review screen (G5) and
survives clearing the queue.

**As built (Phase 3).** `queue_items` and `sent_ledger` are in Room (`framealt_send.db`,
schema exported to `app/schemas/`). The `frames` table was not built. With one frame
(D6), the pairing stays in its own DataStore file (`FrameStore`), as Phase 2 left it. Move
it into Room when multiple frames arrive. `queue_items` also has a `frameErrorCode`
column, so the UI can map a frame's refusal to the right sentence (UX §7) without parsing
`lastError`.

### 5.3 Settings (DataStore)

Sender name, default fit/crop, WebP quality, "warn on duplicates", last selected frame,
diagnostics-logging toggle.

## 6. Image pipeline

Input: a `content://` URI. Output: an app-private `.webp` file plus its metadata.

```
1. Query size via ImageDecoder header callback (or BitmapFactory inJustDecodeBounds).
   Reject width*height > 50_000_000 → "This photo is too large to prepare."
2. scale = min(1.0, max(panelLong / long(w,h), panelShort / short(w,h)))   // cover the panel
   target = (round(w*scale), round(h*scale)), each at least 1
3. Decode with ImageDecoder, setTargetSize(target) — samples during decode, so a 50 MP
   source never becomes a 200 MB Bitmap.
4. Flatten onto opaque white (matches the reference; WebP lossy has no useful alpha).
5. Bitmap.compress(WEBP_LOSSY, quality = 85) → app-private file.
6. Reject > 32 MB after encoding (unreachable at these dimensions; keep the guard).
7. capturedAtMs = EXIF DateTimeOriginal → else MediaStore DATE_TAKEN → else now().
8. contentId = BE.u64(sha256(fileBytes)[0..7]) & 0x7FFF_FFFF_FFFF_FFFF
```

Notes:

- **Step 2 ignores orientation.** The frame reports its panel in native orientation. The
  one tested reports 800 × 1280 (protocol §6, kind 2), but it may stand either way and
  rotates photos itself. Matching the photo's long side to the panel's long side keeps a
  landscape photo sharp on a portrait-native panel. The original `frameWidth / w` form
  would have scaled a 4000 × 3000 photo to 1707 × 1280 for this frame, for no visible
  gain. Unit-tested in `SizingTest`.
- Decoding targets **sRGB**. The WebP encoder drops colour profiles, so a Display P3
  photo would otherwise look washed out on the frame.
- `ImageDecoder` (API 28+) applies EXIF orientation during decode; do not rotate again.
- **HEIC/HEIF decodes natively**, which is a real advantage over the reference client's
  browser path — Pixel photos need no conversion dance.
- Ultra HDR JPEG gain maps and Motion Photo video tracks are dropped. The frame is an
  SDR still display; this is correct, not a regression.
- If the frame's panel size is not yet known (never connected), fall back to
  **1280 × 800** — the reference client's default — and re-prepare nothing afterwards.
- Preparation happens **in the foreground**, while the URI grant is still valid (§8.5).
  The queue must never hold only a `content://` URI. As built, it starts as soon as the
  review screen opens rather than on Send. That way the content ID, which is a hash of the
  prepared bytes, is known in time for the "Already sent" badge. Caption and fit/crop are
  metadata and do not change the bytes, so nothing is redone when they change. Leaving
  the screen deletes the prepared files. Any left behind by process death are swept at the
  next start.

## 7. Send queue

### 7.1 States

```
PENDING ──▶ PREPARING ──▶ PREPARED ──▶ SENDING ──▶ SENT
                │              │           │
                └──────────────┴───────────┴──▶ FAILED (terminal)  /  CANCELLED
```

`PREPARING`/`PREPARED` run inline when the user taps Send; `SENDING` onwards belongs to
the worker.

As built, `PENDING` and `PREPARING` exist only on the review screen and are never stored.
A row is inserted already `PREPARED`, because only a prepared file may be queued. A
`SENDING` row found at worker start (process death mid-send) goes back to `PREPARED`
without being charged an attempt.

### 7.2 Worker

- `SendWorker : CoroutineWorker`, unique work name `send-queue`, policy `KEEP`.
- Calls `setForeground()` → a `dataSync` foreground service with a progress notification.
- Constraint: a `NetworkRequest` requiring `TRANSPORT_WIFI`.
- Drains the queue **in one session**: open the frame connection once, send every
  `PREPARED` item in `createdAt` order, then close.
- Per-item outcome:
  - receipt OK → `SENT`, write to `sent_ledger`, delete the prepared file.
  - frame error 12 (*frame limit reached*) → `FAILED`, stop the drain, notify.
  - frame errors 1/5 (*unauthorized* / *permission required*) → `FAILED`, prompt re-pair.
  - transport/timeout/unreachable → leave `PREPARED`, `attempts++`, `Result.retry()`
    with exponential backoff (30 s → 15 min cap).
  - `attempts >= 10` → `FAILED` with the last error retained.
- Enqueued on: user taps Send; app start with a non-empty queue; a Wi-Fi connectivity
  callback firing while items are pending.

**As built (Phase 3)**, where the implementation differs from the list above:

- **Policy.** `REPLACE` when the worker is idle or backing off, so the three triggers run
  it *now*. `APPEND_OR_REPLACE` while it runs, so photos added in a drain's last moments
  still go. Plain `KEEP` would leave a fresh batch waiting out an old backoff.
- **Attempts count only real sends.** If the frame cannot be reached at all, no photo was
  attempted and none is charged. A sleeping frame (Testing §6 question 6) then means
  retrying until morning, not ten strikes and `FAILED`. A connection that drops *during*
  an upload charges that one photo and ends the drain.
- **No 15-minute cap.** WorkManager's exponential backoff cannot be capped below its own
  5 h. The three triggers above cover the cases where a long backoff would be noticed.
- **Any other frame refusal** (e.g. 3 *bad request*) fails that one photo and the drain
  continues.
- **The foreground service is best-effort.** Android refuses one started from the
  background (a backoff retry with the app closed). The drain then runs as plain
  background work.
- The drain rules live in `QueueDrainer`, which has no Android dependencies, and are
  unit-tested with a fake DAO, including that retries carry identical `mediaId` and
  `contentId`.

### 7.3 Duplicate safety

The dangerous window is: all chunks sent, receipt never arrives (Wi-Fi drops). The frame
may or may not have committed the photo, and a naive retry duplicates it.

Mitigation: **generate `mediaId` and `contentId` once, at enqueue, and reuse both on
every retry.** The reference client regenerates them per attempt; we deliberately do
not. The frame is given identical identifiers for what is identical content, which is
the only lever we have for frame-side de-duplication.

> Unverified: whether the frame actually de-duplicates on `mediaId` or `contentId`.
> Test it explicitly (see [`04 - Testing.md`](04%20-%20Testing.md) §6) and record the
> answer here. If it does not, add a post-retry reconciliation pass against the media
> list before marking `SENT`.

## 8. Android platform constraints

### 8.1 Local network permission — read this before writing the manifest

Android 16 introduced Local Network Protections; from **Android 17 (API 37) they are
mandatory for apps targeting 37+**. Every LAN TCP connection, every UDP datagram, and
all `NsdManager` use is gated behind the runtime permission
`android.permission.ACCESS_LOCAL_NETWORK` (in the `NEARBY_DEVICES` group). Denied
access shows up as **TCP connect timeouts** and `EPERM` on UDP — not as a clean
`SecurityException`, so an unhandled case looks exactly like "the frame is off".

| `targetSdk` | Manifest | Runtime |
|---|---|---|
| ≤ 36 | **Do not declare** `ACCESS_LOCAL_NETWORK` — the docs are explicit | Implicitly granted via `INTERNET` |
| 37+ | Declare it | Request it before discovery; handle denial |

v1 ships `targetSdk 36` to keep the first milestone free of a permission dance, with the
flip to 37 tracked as its own task
([`05 - Plan.md`](05%20-%20Plan.md) Phase 5). Write the discovery and connect paths so
that a permission check is a single injectable gate, not a change threaded through
three layers.

When the flip happens: request at the moment the user taps *Connect your frame* (not at
launch), with a rationale naming the frame — "FrameAlt needs to find your photo frame on
this Wi-Fi network." On denial, fall back to manual `host:port` entry, which still needs
the permission for the TCP connect, so the honest copy is "FrameAlt can't reach your
frame without local network access."

### 8.2 Keeping traffic on Wi-Fi

When mobile data is active and the Wi-Fi has no internet (or is deprioritised), the
default network is cellular and a LAN socket goes nowhere.

- `registerNetworkCallback` with a `NetworkRequest` for `TRANSPORT_WIFI` — observation
  only, so **no `CHANGE_NETWORK_STATE` permission needed** (`requestNetwork` would need
  it).
- Hold the current Wi-Fi `Network` and create sockets via `network.socketFactory`. This
  is the `SocketFactory` injected into `MdgTransport`.
- Never call `bindProcessToNetwork` — it is process-global and would break anything else
  the app does.
- `NsdManager` cannot be network-bound; it uses the system's. On a phone with Wi-Fi up
  this is fine. If discovery fails while a Wi-Fi network is present, that is a known
  gap → manual entry.

### 8.3 Discovery on Android

- `NsdManager.discoverServices("_frameo._tcp", PROTOCOL_DNS_SD, listener)`.
- Resolve with `registerServiceInfoCallback` (API 34+) and `resolveService` below it.
  The legacy `resolveService` permits only one in-flight resolve — **serialise resolves
  in a queue** or you will get `FAILURE_ALREADY_ACTIVE`.
- `NsdServiceInfo.serviceName` is the instance name → match per protocol §2.
- Stop discovery after ~5 s or when the UI leaves the screen; mDNS browsing is a battery
  cost.
- No multicast lock is needed — `NsdManager` handles it internally (a raw jmDNS
  implementation would need `WifiManager.MulticastLock`).

### 8.4 Foreground service

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`.
- Override WorkManager's service in the manifest to add
  `android:foregroundServiceType="dataSync"`.
- `POST_NOTIFICATIONS` runtime permission (API 33+). If denied, sends still work — only
  the progress notification is missing; say so once rather than blocking.
- Android 15+ caps cumulative `dataSync` foreground time at roughly 6 h/day. Photo
  batches are minutes; not a concern, but do not repurpose this worker for polling.

### 8.5 Share-sheet URI lifetime

`ACTION_SEND`/`ACTION_SEND_MULTIPLE` grant read access tied to the receiving activity's
task. The grant does **not** survive process death, so a queue holding only
`content://` URIs would break exactly in the case D7 exists to fix. Hence §6 step
ordering: prepare and copy first, enqueue second.

### 8.6 Backup exclusion

Set `android:dataExtractionRules` and `android:fullBackupContent` to exclude the
identity DataStore file and the prepared-image directory. An identity key restored onto
a second device silently clones a pairing credential. Simplest safe default for a
personal app: `android:allowBackup="false"`.

### 8.7 Manifest summary

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- targetSdk 37+ only:
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" /> -->
```

No storage permissions: the photo picker and the share sheet both hand over URIs
directly.

## 9. Security posture

- **Threat model**: a personal app on a trusted phone, talking to a frame on a home
  LAN. The adversary worth defending against is another app on the same phone and
  another device on the same Wi-Fi. Not a targeted attacker with root.
- Frame identity is pinned at pairing (peer ID + issuer). Any change is a hard failure.
- The issuer allow-list is the trust root; unknown issuers are refused (protocol §3.5).
- No cleartext traffic of any kind — the entire session is authenticated-encrypted by
  the protocol itself. `android:usesCleartextTraffic="false"` is set for hygiene, though
  it does not govern raw sockets.
- No network calls other than to the frame. No analytics, no crash reporting, no
  update check.
- Logging: a ring buffer in memory, opt-in file logging for diagnostics. **Never** log
  key material, the friend code, the identity key, certificate bytes, or photo content.
  Redact the peer ID to its first 8 hex characters in logs.

## 10. Observability

A single `Diagnostics` screen, because the failure modes here (mDNS blocked, wrong
subnet, frame asleep, permission missing) are otherwise invisible:

- Last 200 protocol events: connect, handshake stage, kind sent/received, sizes, errors.
- Current Wi-Fi SSID/subnet, whether a Wi-Fi `Network` is bound, discovery results.
- Frame record: peer ID (redacted), issuer (redacted), host:port, protocol version,
  permissions, last successful contact.
- "Copy diagnostics" → clipboard, with an explicit note that it contains no keys.
