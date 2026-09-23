# Delivery Plan, Risks & Backlog

Seven phases. Each one ends at a gate that can be demonstrated, not just compiled.
Phases 0–1 never touch the frame; Phase 2 is the first real-hardware milestone and is
deliberately small, because it is where the protocol port either works or does not.

---

## Phase 0 — Foundations

**Build**
- Gradle project, two modules (`protocol`, `app`), version catalog, Kotlin + Compose
  toolchain, `compileSdk 37` / `minSdk 31` / `targetSdk 36`.
- `protocol/` crypto layer: `X25519`, `Ed25519.verify`, `HSalsa20`, `XSalsa20` stream,
  `secretbox`, `box`, `beforenm`, nonce construction, constant-time compare.
- `tools/gen-vectors.py` plus the committed vector JSON.
- Fill `Agents.md` with the working agreement: build/test commands, the module boundary
  rule (`protocol/` imports nothing from `android.*`), the "never vendor the Go
  reference's source" rule, and where the reference clone lives (outside the repo).

**Gate** — `./gradlew :protocol:test` green with: all NaCl/RFC vectors from
[`04 - Testing.md`](04%20-%20Testing.md) §2, all 20 PACE vectors, every negative case.

No UI exists yet. This is correct. If the crypto is wrong, everything downstream
debugs as a network problem.

## Phase 1 — Transport & mock frame

**Build**
- Protobuf subset, envelope, multipart split/reassemble.
- `MdgTransport`: framing, handshake, metadata map, certificate verification, issuer
  list asset, replay guard, sequence discipline.
- `FrameoClient`: receive loop with auto-responses, `getInfo`, `upload`, `list`,
  `fetchMedia`, `requestPermission`.
- `Pace` driver on top of the pure function from Phase 0.
- Mock frame (in-memory) + the `:mockframe` standalone runner with jmDNS advertisement.

**Gate** — the full mock-frame suite in [`04 - Testing.md`](04%20-%20Testing.md) §5 is
green, including pairing against the mock with a fixed friend code, a 3-part multipart
message, and an upload that blocks until its receipt.

## Phase 2 — Pairing on a real frame

**Build**
- Android app shell, theme, navigation, the hand-rolled DI container.
- `IdentityStore` (Keystore-wrapped key in DataStore), Room with the `frames` table.
- `NsdDiscovery` (serialised resolves), `WifiSockets` network-callback socket factory.
- Connect-frame flow: empty state → discovery list → manual entry → friend code + sender
  name → pairing → Home's frame card and Connection details.
- Diagnostics screen, early — it pays for itself in this phase.

**Gate — the project's real risk gate.** On a Pixel, on home Wi-Fi:
1. The frame is discovered by mDNS without manual entry.
2. Pairing with a fresh friend code succeeds.
3. Connection details show the frame's **real** name, placement, resolution, protocol
   version, and permission bits.
4. Killing and reopening the app reconnects using the stored pairing, with no re-pair.

If the handshake fails here, the likely causes in order are: HSalsa20 input-subtraction
(architecture §3.2), nonce prefix padding, sequence numbering, and the issuer list being
stale. Diagnostics should name the handshake stage that failed.

## Phase 3 — Sending

**Build**
- `ImagePipeline` per [`02 - Architecture.md`](02%20-%20Architecture.md) §6.
- Photo picker entry, Review & Send screen, batch caption, fit/crop switch, duplicate
  badge, `sent_ledger`.
- Room `queue_items`, `SendQueue`, `SendWorker` with the `dataSync` foreground service,
  notification channel, backoff, terminal-failure handling.
- Stable `mediaId`/`contentId` across retries.

**Gate** — manual tests 5–13 in [`04 - Testing.md`](04%20-%20Testing.md) §8 pass,
including the 50-photo batch, screen lock, Wi-Fi drop/resume, and force-stop/resume.
Answer §6 question 1 (does the frame de-duplicate?) and record it.

**Gate outcome (2026-09-23): passed**, with the scope narrowed by decision:

- **HEIC and Motion Photo are out of scope for v1.** Test 13 covers JPEG (including EXIF
  rotation), PNG with transparency, portrait and panorama. HEIC may well work, since
  `ImageDecoder` reads it natively, but it is neither tested nor promised. The same goes
  for Motion Photos (sent as their still image, if at all).
- **Test 11 (unplug the frame mid-send) was not run**, judged too rough an edge case to be
  worth the setup. The expected behaviour, traced from the code, is recorded in
  [`04 - Testing.md`](04%20-%20Testing.md) §8.1 in its place.
- Crop (12) and portrait and panorama (13) were confirmed by eye on the frame.
- §6 question 1 (de-duplication) is still open; it does not block the gate.


## Phase 4 — Share sheet

**Build**
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` filters for `image/*`.
- Share entry reuses Review & Send; non-image items counted and skipped.
- Not-paired share path holds the batch through pairing.
- Preparation happens before the activity finishes, while the URI grant is alive.

**Gate** — Google Photos → share 20 photos → FrameAlt → Send → return to Google Photos,
all 20 arrive. Repeat with the app force-stopped beforehand.

## Phase 5 — Gallery

**Build**
- Request-access flow (kind 27 type 3: view + manage, + polling) with its waiting and
  timeout states.
- Media list, thumbnail grid (kind 23 at 400×400), bounded memory cache, badges for
  video/greeting, header counts, pull to refresh.
- Photo preview with full-size fetch, all actions on screen without scrolling. (Save to
  phone was built, then removed on 2026-09-23.)
- Protocol < 13 fallback copy.
- **Managing** (D2, revised 2026-09-23): kinds 33/34/35 in `:protocol` with mock-frame
  tests; multi-select with Delete / Hide / Show; preview actions; "Select photos sent
  from this phone", backed by the media ID now kept in `sent_ledger`.

**Gate** — manual tests 15–18 pass; the item count matches the official Frameo app; and
the test photos uploaded while building Phases 2–4 are removed from the frame with
"Select photos sent from this phone" → Delete, leaving everything else untouched.

## Phase 6 — Hardening & release

**Build**
- Settings (sender name, defaults, quality, duplicates warning, advanced, about).
- Full error-copy pass against [`03 - UX.md`](03%20-%20UX.md) §7 — every path reachable
  in the UI, none showing a code or an exception.
- **`targetSdk 37` + `ACCESS_LOCAL_NETWORK`**, with the runtime request at *Connect your
  frame*, denial handling, and re-verification of discovery and connect on a Pixel
  running Android 17.
- Backup exclusion, `allowBackup=false`, R8 release build, app icon, signing config.
- Accessibility pass at 200% font scale, dark mode, TalkBack on the send flow.

**Gate** — every success criterion in [`00 - Overview.md`](00%20-%20Overview.md) §7,
on both a Pixel 6 and a Pixel 10.

## Open items — paused 2026-09-23

Work stopped after Phase 5 was built. Phases 0–4 are closed; Phase 5 is built and
mostly verified; Phase 6 has not started. Nothing below blocks using the app; each item
is a check or fix that was deliberately left for later. Results so far are in
[`04 - Testing.md`](04%20-%20Testing.md) §8.1–§8.3.

**Tests not run**

| Phase | Item | What is needed |
|---|---|---|
| 3 | Test 11: unplug the frame mid-send | Not run by decision. Expected behaviour, traced from the code, is in Testing §8.1. |
| 3 | Test 9: resume when the app is *reopened* after a force-stop | In the run, Android restarted the process by itself and it finished the queue. Reopening as the only trigger was never exercised. |
| 4 | Mixed share (photos plus a PDF) on the device | Classification is unit-tested (`SharedItemsTest`); not yet run from a real share sheet. |
| 4 | Share before any frame is paired | Needs the pairing removed and a friend code. |
| 5 | Test 16: grid smoothness and memory at scale | Scrolls through 1330 items; not measured. |
| 5 | Test 18: deny or ignore the access request | Check the timeout copy appears and nothing gets stuck. |
| 5 | Item count matches the official Frameo app | The app lists 1330 (1328 photos, 2 videos) after cleanup. |
| 5 | *Show on frame now* visibly works | No receipt exists for kind 35, so it needs a look at the frame. |
| — | Testing §6 question 1: does the frame de-duplicate on re-send? | Decides whether §7.3 of the architecture needs a reconciliation pass. |
| — | Testing §6 question 6: a sleeping frame | Whether it stops advertising, stops accepting TCP, or both. |

**Known gaps and rough edges**

- **Manual address entry is not wired up.** The connect screen shows host and port but
  has no fields to type them into, although UX §2.2 requires it as the fallback when mDNS
  is blocked. A Phase 2 gap; fix before release.
- **Nothing notices the frame coming back** after an outage. The queue waits for its next
  backoff retry (up to WorkManager's 5 h cap) unless the user taps *Send now* or opens
  the app. Cheap fix: also retry on app foreground.
- **Review & Send in landscape**: the caption and switch take most of the height, so few
  photos are visible (it scrolls).
- **`framectl` prints full peer IDs** in `pair` and `info`, against the rule to show
  only the first 8 hex characters.
- **2 test photos still on the frame**, sent by `framectl` from the desktop on 2026-09-23
  (a blue "FrameAlt upload test" card and a noisy "multi-chunk test" card). The phone has
  no record of them, so remove them by hand: in the gallery, long-press → Delete.

## 1. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Hand-composed NaCl is subtly wrong | Medium | Blocks everything | Independent vectors (testing §2), never self-round-trip alone. The HSalsa20 subtraction step is the specific trap. |
| The frame's issuer is not in the embedded list | Low | Blocks pairing | Distinct error copy + an explicit advanced override (architecture §3.5). Capture the real issuer and add it. |
| Frameo firmware update changes the protocol | Low per year, certain eventually | App stops working | Accepted — personal tool. Diagnostics must make "the protocol changed" distinguishable from "the frame is off". |
| `NsdManager` flakiness (stale cache, `FAILURE_ALREADY_ACTIVE`, silence on some routers) | **High** | Frame not found | Serialise resolves; forced rediscovery on connect failure; manual `host:port` always available; cache the last good endpoint. |
| Local network permission denied (targetSdk 37) | Medium | Looks exactly like an offline frame | Detect: Wi-Fi present + permission denied + connect timeout → show the permission message, not the offline one. |
| Wi-Fi is not the default network (cellular preferred) | Medium | Sends fail silently | Network-callback-bound socket factory (architecture §8.2). |
| Retry after a lost receipt duplicates a photo | Medium | Annoying, not fatal | Stable `mediaId`/`contentId`; verify frame behaviour in Phase 3 and add reconciliation if needed. |
| Large batches overheat/drain during WebP encoding | Low | Slow sends | Encode lazily, one at a time, off the main thread; downscale before encode so the bitmaps stay small. |
| Pixel 6 loses OS support (Oct 2026) | Certain | None functionally | `minSdk 31`; nothing depends on future updates. |
| Reference client is unlicensed | n/a | Legal, if published | Clean-room Kotlin port; no vendored Go; do not publish without resolving it (overview §8). |
| Developer verification for sideloading (global 2027) | Certain | Sharing with family | ADB installs unaffected; free limited-distribution account covers 20 devices. |

## 2. Open questions

Not blockers — each has a stated default so work can proceed.

1. **Does the frame de-duplicate by `mediaId` or `contentId`?** Default: assume it does
   not; keep the stable-ID strategy and be ready to add reconciliation. Resolve in Phase 3.
2. **Is `1280 × 800` the right fallback panel size?** Default: yes, matching the
   reference. Replace with the real value after the first connect; it is only used
   before a frame is ever reached.
3. **WebP quality 85 vs 92?** Default: 85, with a Settings toggle. Decide by looking at
   the frame after Phase 3 — at panel resolution the difference may be invisible.
4. **Caption per batch or per photo?** Default: per batch for v1 (UX §4). Revisit only
   if it turns out to matter in use.
5. **Should an unrecognised-issuer override exist at all?** Default: yes, buried in
   Advanced with a warning. It converts a hard brick into a user decision.
6. **`app.framealt` as the application ID.** Default: yes; change now if a different
   namespace is preferred, as changing it later means re-pairing is not required but the
   install is.

## 3. Explicitly deferred (post-v1 backlog)

Ordered by expected value.

1. ~~**Manage operations**~~ — moved into v1, Phase 5 (D2 revised 2026-09-23).
2. **Multiple frames + fan-out** — the storage model already supports it; it costs a
   frame picker, a destination selector in Review & Send, and per-frame queue drains.
3. **Watched album auto-send** — nominate an album; new items are queued automatically.
   Needs `READ_MEDIA_IMAGES`, a MediaStore observer, and careful duplicate handling.
4. **Download everything from the frame** — a backup action over kind 23, useful if a
   frame is being retired.
5. **Video** — investigation first (protocol §9 item 4): capture what the official app
   sends for a short clip and see whether it is kinds 4/5 with a different extension.
6. **Remote sending** — the SecureDeviceGrid relay. Large, and the reason D1 exists.
   Only worth it if a frame ends up living somewhere other than home.
7. **Quick Settings tile / home-screen widget** — "send last photo to the frame".
8. **HEIC and Motion Photo, verified.** Out of scope for v1 (decided 2026-09-23). Test a
   Pixel HEIC and a Motion Photo end to end, and decide whether a Motion Photo should send
   its still image or be refused.


## 4. Working agreements

- The reference clone (`yasoob/frameo-client`) lives **outside** this repository. Read
  it; do not copy its source in. See [`00 - Overview.md`](00%20-%20Overview.md) §8.
- Any protocol fact learned from a real frame goes back into
  [`01 - Protocol.md`](01%20-%20Protocol.md) — §9 is a living list, and shrinking it is
  progress.
- A spec change to one of the D1–D8 decisions is a spec edit first, code second.
- No work item is done until its phase gate passes on a real device.
