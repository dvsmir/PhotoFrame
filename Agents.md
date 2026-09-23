# Working agreement

Read [`Spec/00 - Initial/README.md`](Spec/00%20-%20Initial/README.md) first.
The spec is the source of truth; this file is only how to work in the repo.

## Build & test

The CLI needs a JDK on `JAVA_HOME`. Android Studio's bundled JBR 21 is the one this
project is developed against:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\gradlew :protocol:test          # the gate that matters
.\gradlew build
```

Regenerating the crypto test vectors (rarely needed — the output is committed):

```sh
cd tools/gen-vectors && npm install && npm run gen
```

## Module boundary — not negotiable

`:protocol` is plain Kotlin/JVM. It must not import `android.*` or `androidx.*`, and its
only production dependency is BouncyCastle. That is what keeps the entire protocol
testable on a laptop JVM in seconds, with no emulator and no photo frame.

Anything platform-specific is an interface in `:protocol` and an implementation in
`:app`. Today that means sockets and mDNS discovery.

## Crypto rules

- BouncyCastle **lightweight API only** (`org.bouncycastle.crypto.*`,
  `org.bouncycastle.math.ec.*`). Never register it as a JCE `Security` provider — Android
  ships its own repackaged copy and provider registration is where the breakage lives.
- Never prove crypto by round-tripping our own output. A symmetric bug passes that test
  happily. Every primitive is checked against vectors from an independent implementation
  (`tools/gen-vectors` uses tweetnacl-js, cross-checked against OpenSSL).
- Every MAC, proof and "expected reply" comparison goes through
  `ConstantTime.equals`.
- Never log key material, friend codes, identity keys, certificate bytes, or photo
  content. Redact peer IDs to their first 8 hex characters.

## The reference client

[`yasoob/frameo-client`](https://github.com/yasoob/frameo-client) is the Go
implementation this port is based on. It ships **no LICENSE file**, so:

- Clone it *outside* this repository. Read it freely; never copy its source in.
- The one imported artifact is its PACE test fixture, at
  `protocol/src/test/resources/vectors/pace.json`, which records its origin in the file.
- `protocol/src/main/resources/dev/dsmirnov/photoframe/protocol/issuers.json` is the frame
  certificate trust root — a list of public keys, i.e. data, not code.

## Conventions

- `allWarningsAsErrors` is on in `:protocol`. Fix warnings; do not suppress them.
- Protocol facts learned from a real frame go back into
  `Spec/00 - Initial/01 - Protocol.md` §9. Shrinking that list is progress.
- Changing one of the D1–D8 decisions is a spec edit first, code second.
- Transport integers are big-endian; Salsa20 internals are little-endian. The helpers in
  `crypto/Packing.kt` are named accordingly — use them rather than open-coding shifts.
- A phase is not done until its gate in
  [`Spec/00 - Initial/05 - Plan.md`](Spec/00%20-%20Initial/05%20-%20Plan.md)
  passes.

## Status

| Phase | State |
|---|---|
| 0 — Foundations (crypto + vectors) | **done** — external NaCl/X25519/Ed25519 vectors + all 20 PACE vectors |
| 1 — Transport & mock frame | **done** — 54 tests green |
| 2 — Pairing on a real frame | **done** — gate passed 2026-09-23 on a Pixel 6a (Android 17): mDNS discovery, pairing, real frame details, reconnect after force-stop |
| 3 — Sending | **done** — gate passed 2026-09-23 on the Pixel 6a, scope narrowed by decision (`05 - Plan.md`, Phase 3 gate outcome): tests 5–10, 12, 13 and 14 pass, test 11 was not run and its expected behaviour is traced in `04 - Testing.md` §8.1, and HEIC and Motion Photo are out of scope for v1. `.\gradlew :app:testDebugUnitTest`: 27 JVM tests. Debug builds can drive the send flow over adb: `DebugPickActivity`, see its KDoc |
| 4 — Share sheet | **done** — gate passed 2026-09-23: Google Photos → share 20 → FrameAlt → Send → back in Google Photos, 20/20 arrived, with FrameAlt running and again force-stopped (`04 - Testing.md` §8.2). Not yet run on the device: a mixed photo+PDF share (unit-tested) and sharing before pairing |
| 5 — Gallery + managing | **built, gate mostly passed.** Scope revised 2026-09-23 (D2): the gallery also deletes, hides/shows and displays now. Tests 15 and 17 pass, and the build-time test photos were cleaned up with "Select photos sent from this phone" (`04 - Testing.md` §8.3). Open: 16 (not measured), 18 (deny/timeout), comparing the count with the official app, and 2 `framectl` test photos still on the frame. **Since then (2026-09-23):** named Photo Frame with a new icon; Home reduced to Add photos, On the frame and a "Frame info" card; a Frame screen with a local rename; a swipeable preview with no Save to phone; and a photos/videos filter. All of it is in the spec (Overview name note, UX §1–§3 and §6, Architecture §5.2, §10 and §11) |
| 6 | not started |

**Paused 2026-09-23.** Everything not yet run or fixed is listed in `05 - Plan.md`, "Open items". Start there when resuming.

### Phase 2 — how it was verified

On 2026-09-23 the frame was awake, and `framectl` (desktop) and the app (Pixel 6a,
Android 17) each passed:

1. The app installs and launches.
2. `NsdManager` finds the frame with nothing cached, in about 0.2 s.
3. Pairing with a real friend code succeeds. The frame's issuer is in `issuers.json`.
4. Connection details show the real name, placement, 800 × 1280 and protocol 18.
5. After a force-stop, the app reconnects from the stored pairing without pairing again.

Not yet exercised: rediscovery after the stored address goes stale, and anything with the
frame asleep. What was learned is recorded in `01 - Protocol.md` (§2, §3.3, §3.5, §4.3,
kind 2).

**Driving the phone from here.** Debug builds mirror the Diagnostics log to logcat
under `FrameAlt/<tag>`. The debug package is `dev.dsmirnov.photoframe.debug`.

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n dev.dsmirnov.photoframe.debug/dev.dsmirnov.photoframe.ui.MainActivity
adb logcat -s "FrameAlt/session" "FrameAlt/discovery" "FrameAlt/network"
```

Screenshots: use `adb exec-out screencap -p > s.png` from the Bash tool. Redirection in
PowerShell 5.1 corrupts binary output. To type a friend code, pipe the `input` commands into
`adb shell` over stdin, so the code does not show up in the device's `adbd` log.

If a handshake fails, the likely causes in order are: a stale issuer list (the frame's
certificate issuer is not in `issuers.json`), the endpoint being wrong, or the frame not
serving while asleep. The Diagnostics screen names the stage that failed.

`:framectl` exists for exactly this: the same protocol library driven from a terminal, so a
failure can be pinned on the protocol or on the Android layer rather than guessed at.

```powershell
.\gradlew :framectl:installDist
.\framectl\build\install\framectl\bin\framectl.bat discover   # mDNS
.\framectl\build\install\framectl\bin\framectl.bat probe      # handshake sweep, firewall-immune
.\framectl\build\install\framectl\bin\framectl.bat pair <friend-code>
```

`./gradlew :protocol:test`: 60 tests, about 5 s, no emulator, no frame. (54 at the end of Phase 1; 6 added for managing in Phase 5.)

| Suite | Covers |
|---|---|
| `NaclVectorTest` | HSalsa20, XSalsa20, secretbox, box, beforenm, X25519, Ed25519 vs. tweetnacl + OpenSSL |
| `PaceTest` | the 20 reference PACE vectors, friend-code and challenge validation, issuer list |
| `WireTest` | protobuf subset, ZigZag, envelopes, multipart split/reassembly, malformed input |
| `SessionTest` | handshake, certificate trust, replay, pairing, upload, gallery, permissions, managing (delete, hide/show, display now, batching) vs. `MockFrame` over loopback TCP |
