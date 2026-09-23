# FrameAlt Specification

A personal Android app that sends photos to a Frameo digital photo frame over the local
network — no account, no cloud, no subscription limits.

Reference implementation: [`yasoob/frameo-client`](https://github.com/yasoob/frameo-client)
(Go desktop app — read it, do not vendor it).
## Documents

| # | Document | Read it when |
|---|---|---|
| 00 | [Overview & Scope](00%20-%20Overview.md) | You need the product decisions, goals, non-goals, limits, and the legal/distribution notes. **Start here.** |
| 01 | [Protocol Reference](01%20-%20Protocol.md) | You are implementing or debugging anything on the wire. Complete enough to re-implement without reading the Go source. |
| 02 | [Architecture](02%20-%20Architecture.md) | You are writing app code: modules, crypto mapping, sessions, storage, image pipeline, send queue, Android platform traps. |
| 03 | [UX](03%20-%20UX.md) | You are building a screen or writing user-facing text. The copy is the spec. |
| 04 | [Testing](04%20-%20Testing.md) | Before you touch the real frame, and whenever you add protocol code. |
| 05 | [Plan, Risks & Backlog](05%20-%20Plan.md) | You want to know what to build next and what the gate is. |

## The decisions, in one table

| # | Decision |
|---|---|
| D1 | LAN only — phone and frame on the same Wi-Fi |
| D2 | v1 = send photos + a gallery of the frame that also manages it: delete, hide/show, display now (revised 2026-09-23) |
| D3 | Pure Kotlin protocol port, BouncyCastle crypto, no NDK/JNI/Go |
| D4 | Entry points: Android share sheet + in-app photo picker |
| D5 | Photos downscaled to the frame's panel resolution, WebP q≈85 |
| D6 | One frame (storage model still supports more) |
| D7 | Persistent send queue in a foreground service, with retries |
| D8 | No video in v1 |

## The shortest possible protocol summary

mDNS `_frameo._tcp.local` → TCP → a CurveCP-style handshake (`🐟TELL/WELC/HELO/COOK/VOCH/REDY`)
→ Ed25519 certificate check against ~250 known issuers → NaCl secretbox records carrying
an 8-byte envelope plus hand-rolled protobuf. Pairing is PACE v1 using the friend code
from the frame's screen, and yields a long-lived key pair. Uploads are WebP, chunked at
16 316 bytes, confirmed by a receipt.

Full detail: [01 - Protocol.md](01%20-%20Protocol.md).

## First three things to build

1. Crypto + vectors ([Plan](05%20-%20Plan.md) Phase 0) — nothing else can be debugged
   until this is provably correct.
2. Transport + mock frame (Phase 1) — the whole protocol, tested on a laptop JVM.
3. Discovery + pairing on a real frame (Phase 2) — the project's real risk gate.
