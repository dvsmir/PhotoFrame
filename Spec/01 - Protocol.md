# Frameo LAN Protocol — Implementation Reference

Everything needed to re-implement the client in Kotlin without reading the Go source.
Derived by reading [`yasoob/frameo-client`](https://github.com/yasoob/frameo-client)
(`internal/protocol/*.go`, `internal/device/discovery.go`), confirmed against its
documented message table for **Frameo Android app v1.40.5** and its PACE test vectors.

Verified working against **protocol version 18**; the gallery requires **≥ 13**.

> **Byte-order rule for this whole document:** every integer on the transport layer is
> **big-endian**. Every integer inside a protobuf body is a protobuf varint. Do not mix
> the two up.

---

## 1. Layer cake

```
┌──────────────────────────────────────────────────────────────┐
│ Application     8-byte envelope + protobuf body              │  §5–§7
│                 (kinds 1,2,3,4,5,6,10,23,27,30,31,32,33,…)   │
├──────────────────────────────────────────────────────────────┤
│ Framing         u16be length  +  2-byte payload length       │  §3.2, §3.6
├──────────────────────────────────────────────────────────────┤
│ MDG session     NaCl secretbox records, 🐟MESG + u64be seq   │  §3.6
├──────────────────────────────────────────────────────────────┤
│ MDG handshake   TELL/WELC/HELO/COOK/VOCH/REDY (CurveCP-like) │  §3.3
│                 + Ed25519 certificate check                  │  §3.5
├──────────────────────────────────────────────────────────────┤
│ TCP             host:port from mDNS _frameo._tcp.local        │  §2
└──────────────────────────────────────────────────────────────┘

Pairing (§4) rides on the same handshake with a different `protocol` metadata value,
and exchanges raw (non-envelope) bytes.
```

## 2. Discovery

| Property | Value |
|---|---|
| Service type | `_frameo._tcp` |
| Domain | `local.` |
| Instance name | The frame's peer ID (32-byte public key, hex, 64 chars) **truncated to 63 characters** — a DNS label cannot exceed 63 bytes |
| Address family | IPv4 (the reference client filters to IPv4) |
| Browse window | ~3 seconds |
| TXT records | Not used |

**Matching a saved pairing to a discovered service:**

```
instance == peerIdHex  ||  instance == peerIdHex.take(63)
```

**Matching at pairing time** (peer ID is not yet known until `WELC` arrives):

```
peerIdHex.startsWith(instance)      // else: "frame identity does not match discovery"
```

The frame's IP and port change with DHCP. Always cache the last known `host:port`, and
on any connection failure re-run discovery (forced, cache-busting) and retry once
before reporting an error. A manual `host:port` entry must remain available as a
fallback for networks where mDNS is blocked.

**Observed on a real frame (2026-09-22, awake).** One `_frameo._tcp` service on IPv4,
`192.168.2.22:36916`. The instance name was 63 hex characters and the peer ID returned in
`WELC` was 64, with the instance being its prefix, which confirms the truncation rule
above. The port is not a well-known one. Whether it is stable across reboots is unobserved,
so treat it as discovered, never configured. mDNS was received on the Windows desktop
this time, so the firewall problem noted in `framectl`'s `Probe` is not constant.
On 2026-09-23 the frame was at the same `192.168.2.22:36916`. Whether the frame
rebooted in between is unknown, so this does not show the port is stable.

**Observed with Android `NsdManager` (2026-09-23, Pixel 6a, Android 17).** Discovery
found the frame about 0.2 s after browsing started and resolved the same
`192.168.2.22:36916`. While resolving, the platform's `NsdService` logged
`IllegalArgumentException: Key cannot be empty` from `NsdServiceInfo.setAttribute`,
which means the frame's TXT record contains an entry with an empty key. The error stays
inside `system_server`: the resolve still succeeds and the app is not affected. Do not
depend on TXT attributes. Nothing in this protocol needs them.

## 3. MDG transport

### 3.1 Constants

| Name | Value |
|---|---|
| `MAGIC` | `"🐟"` = `F0 9F 90 9F` (4 bytes, U+1F41F) |
| Record tag | `MAGIC` + 4 ASCII bytes → an 8-byte header (`🐟TELL`, `🐟WELC`, `🐟HELO`, `🐟COOK`, `🐟VOCH`, `🐟REDY`, `🐟MESG`) |
| Record size | 8 … 65 535 bytes (reject anything outside) |
| Application message | ≤ 16 416 bytes |
| Handshake deadline | 12 s (socket deadline, cleared once `REDY` is verified) |
| Send deadline | 30 s |

### 3.2 Framing

Every record is written as `u16be(len(record)) || record`. Reads take 2 bytes, then
exactly that many. `len < 8` is a protocol error.

### 3.3 Handshake

`C` = client (us), `F` = frame. `ephPub/ephPriv` is a fresh X25519 keypair per
connection. `idPriv/idPub` is our long-lived identity keypair. `framePub` is the
frame's 32-byte identity public key.

| # | Dir | Record | Layout |
|---|---|---|---|
| 1 | C→F | `🐟TELL` | 8 bytes, nothing else |
| 2 | F→C | `🐟WELC` | 8 + `framePub`(32) = **40 bytes exactly** |
| 3 | C→F | `🐟HELO` | 8 + `ephPub`(32) + `u64be(0)` + `box(zeros(64), n="CurveCP-client-H"‖u64be(0), framePub, ephPriv)`(80) = **128 bytes** |
| 4 | F→C | `🐟COOK` | 8 + `cookieNonce`(16) + `box`(144) = **168 bytes exactly** |
| 5 | C→F | `🐟VOCH` | 8 + `cookie`(96) + `u64be(1)` + `secretbox(body, n="CurveCP-client-I"‖u64be(1), K)` |
| 6 | F→C | `🐟REDY` | 8 + `u64be(seq)` + `secretbox(metadata, n="CurveCP-server-R"‖u64be(seq), K)` |

Steps 1–2 are confirmed against a real frame (2026-09-22): a bare `TELL` got a
40-byte `WELC` back. Steps 3–6 and the certificate check (§3.5) were confirmed on
2026-09-23, by `framectl pair` and `framectl info` from the Windows desktop. Both the
pairing connection and a later `framedump_local` reconnect completed the full handshake.

**Step 4 detail.** Open the box with nonce `"CurveCPK"(8 ASCII) ‖ cookieNonce(16)` =
24 bytes, peer key `framePub`, our key `ephPriv`. Plaintext must be **128 bytes**:

```
serverEph = plain[0:32]     // frame's ephemeral public key
cookie    = plain[32:128]   // 96 opaque bytes, echoed verbatim in VOCH
```

**Session key.** `K = crypto_box_beforenm(serverEph, ephPriv)`
(i.e. `HSalsa20(X25519(ephPriv, serverEph), zeros(16))`). Every `secretbox` from here
on uses `K`.

**Step 5 body.**

```
vouchNonce = 16 random bytes
vouch      = box(ephPub(32), n="CurveCPV"(8) ‖ vouchNonce(16), framePub, idPriv)   // 48 bytes
body       = idPub(32) ‖ vouchNonce(16) ‖ vouch(48) ‖ encodeMetadata(m)
```

`m` is the client metadata map (§3.4).

**Step 6.** Decrypt, then parse the frame's metadata map. `metadata["certificate"]` is
mandatory (§3.5).

### 3.4 Metadata map encoding

```
byte    count
repeat count times:
  byte      keyLength            (must be > 0)
  bytes     key                  (ASCII)
  u16be     valueLength
  bytes     value
```

Trailing bytes, a duplicate key, or a zero-length key are protocol errors.

Client sends:

| Session | Map |
|---|---|
| Normal | `{"protocol": "framedump_local"}` |
| Pairing | `{"protocol": "<pairing>", "pace_versions": "1"}` |

> `framedump_local` is the exact string the reference client uses and the frame
> accepts. Treat it as a magic constant; do not "clean it up".

Frame sends back (in `REDY`): at least `certificate`; during pairing also
`pace_versions`.

### 3.5 Frame certificate & trust root

`metadata["certificate"]` is **128 bytes**:

```
issuerPub = cert[0:32]     // Ed25519 public key
signature = cert[32:96]    // Ed25519 signature
subject   = cert[96:128]   // must equal framePub from WELC
```

Checks, all mandatory:

1. `cert.size == 128`
2. `subject == framePub`
3. `Ed25519.verify(signature, issuerPub, message = subject)`
4. **Issuer trust**:
   - *First connection / pairing*: `hex(issuerPub)` must appear in the embedded
     trusted-issuer list (~250 keys; copy from the reference client's
     `internal/protocol/issuers.json` into an app asset).
   - *Subsequent connections*: `hex(issuerPub)` must equal the issuer stored with the
     pairing. A change means a different or tampered frame — hard-fail, do not prompt.

If a real frame's issuer is missing from the list, that is a first-run blocker. Surface
a distinct error ("This frame's certificate is signed by a key Photo Frame doesn't
recognise") and offer an explicit, deliberately awkward trust-on-first-use override in
settings. Never silently accept an unknown issuer.

**Observed on a real frame (2026-09-23).** The certificate was 128 bytes, its subject
matched `framePub`, and the signature verified. The issuer
`aa335c65c736515bda80d5081c23f44b0ee9f4d75677e0a58699370ac7a1bd67` is in the embedded
list, so the reference list covers this frame and no trust-on-first-use override was
needed.

### 3.6 Session records

After `REDY`, both directions exchange:

```
🐟MESG ‖ u64be(seq) ‖ secretbox(payload, nonce(prefix, seq), K)
payload = u16be(len(appMessage)) ‖ appMessage
```

| Direction | Nonce prefix |
|---|---|
| Client → frame | `"CurveCP-client-M"` |
| Frame → client | `"CurveCP-server-M"` |

**Nonce construction** (all secretbox/`REDY`/`MESG` nonces): 24 bytes =
16-byte ASCII prefix (each prefix above is exactly 16 chars) ‖ `u64be(seq)`.

**Sequence numbers.** One monotonic `tx` counter on the client:

| Record | seq used |
|---|---|
| `HELO` | 0 (in both the header field and the nonce) |
| `VOCH` | 1 |
| first client `MESG` | 2, then 3, 4, … |

**Replay protection.** Track `rx`, the last sequence seen from the frame. The first
inbound record establishes the baseline (`REDY`); every later record must satisfy
`seq > rx`, otherwise drop the connection with "replayed record". `COOK` is not
covered — it is a box, not a secretbox, and does not touch `rx`.

**Decrypt guard.** Before opening any `MESG`/`REDY` record: `size ≥ 32` and
`record[0:8] == MAGIC ‖ marker`. After opening, validate
`u16be(payload[0:2]) == payload.size - 2`.

### 3.7 Crypto primitive inventory

| Primitive | Used for |
|---|---|
| X25519 scalar multiplication (base point **and** arbitrary points) | key agreement, PACE |
| `crypto_box` / `crypto_box_open` (X25519 + HSalsa20 + XSalsa20-Poly1305) | `HELO`, `COOK`, vouch |
| `crypto_box_beforenm` (= HSalsa20 of the X25519 output with a zero nonce) | session key `K` |
| `crypto_secretbox` / open (XSalsa20-Poly1305) | `VOCH`, `REDY`, `MESG` |
| Raw XSalsa20 keystream (24-byte nonce) | PACE mapping decryption |
| SHA-512 | PACE |
| SHA-256 | upload content ID |
| Ed25519 verify | frame certificate |
| CSPRNG | ephemeral keys, identity key, PACE scalar, message/receipt IDs |

Mapping these onto BouncyCastle is in [`02 - Architecture.md`](02%20-%20Architecture.md) §3.

## 4. PACE v1 pairing

Pairing runs on a connection dialled with `protocol = "<pairing>"`. The frame's
identity is not yet known or trusted, so `peer` and `issuer` are unconstrained on this
dial (the issuer must still be in the embedded list).

**Precondition.** If the frame's `REDY` metadata contains `pace_versions` and that
value does not contain the ASCII byte `"1"`, abort: the frame does not support PACE v1.

**The friend code.** From the frame's *Add friend* screen. Normalise by removing spaces
and hyphens; the result must be 7–20 characters and digits only.

### 4.1 Challenge

Immediately after `REDY`, the frame sends one application message (raw bytes via §3.6,
**no envelope, no protobuf**) of exactly **97 bytes**, `challenge[0] == 3`:

```
challenge[0]      = 0x03          tag
challenge[1:33]   = Y             frame's PACE public point
challenge[33:65]  = salt          32 bytes; the first 24 double as the XSalsa20 nonce
challenge[65:97]  = encMapping    32 bytes, encrypted mapping scalar
```

Timeout for this receive: 12 s.

### 4.2 Response computation

```
password   = SHA512( codeAscii ‖ clientPub(32) ‖ framePub(32) )          // 64 bytes
stream     = SHA512( password(64) ‖ challenge[33:65](32) )               // 64 bytes
key        = stream[0:32]
mapping    = XSalsa20(key, nonce = challenge[33:57]) XOR challenge[65:97]

scalar     = 32 random bytes                                            // ephemeral
base       = X25519(K, basePoint)          // K = the MDG session key — channel binding
mapped     = X25519(mapping, base)
public     = X25519(scalar, mapped)
sharedPace = X25519(scalar, challenge[1:33])

proof      = SHA512( SHA512(challenge[1:33]) ‖ sharedPace )
expected   = SHA512( SHA512(public)          ‖ sharedPace )
```

Note two things that look like mistakes but are not:

- `clientPub`/`framePub` in `password` are the **identity** keys (ours from the store,
  the frame's from `WELC`) — not the ephemeral ones.
- `base` derives from the **session key `K`** used as an X25519 scalar. That is the
  channel binding that stops a relay attack; it must be `K`, not a fresh key.

### 4.3 Exchange

```
C→F   0x04 ‖ public(32) ‖ proof[0:32]                 // 65 bytes
F→C   0x05 ‖ expected[0:32]                            // 33 bytes
```

Compare the reply to `0x05 ‖ expected[0:32]` in **constant time**. A mismatch means the
friend code was wrong or expired → "Pairing rejected; check the friend code on the
frame." Timeout 12 s.

On success the frame has stored our identity public key. Persist
`{peerId, issuer, host, port, senderName}`, close the pairing connection, and reconnect
with `protocol = "framedump_local"`.

**Observed on a real frame (2026-09-23).** Pairing with a real 10-digit friend code
from the frame's "Add friend" screen succeeded on the first attempt. The frame's reply
matched `expected` byte for byte, so §4.2 is correct on hardware as well as against the
reference vectors. After that, a separate process reconnected from the persisted pairing
with no new friend code.

Later the same day, the same code also paired the Android app, which uses a different
client identity from `framectl`. A friend code is therefore **not single-use**: one
code accepted more than one pairing. How long a code stays valid, and what makes the frame
issue a new one, is unobserved.

### 4.4 Test vectors

The reference repo carries 20 synthetic vectors in
`internal/protocol/testdata/pace.json`, each `{client, frame, channel, scalar,
challenge, response, proof}` in hex, all computed with the friend code `"12 34 56 78 90"`.
`channel` is `K`. These must pass before any real frame is touched — see
[`04 - Testing.md`](04%20-%20Testing.md) §3.

## 5. Application layer

### 5.1 Envelope

```
u32be version   // always write 18; on receive, this is the FRAME's protocol version
u32be kind
bytes protobufBody
```

The frame's protocol version is read off inbound envelopes and cached on the session
(it drives the ≥13 gallery gate and the <4 chunk size).

### 5.2 Multipart wrapper (kind 30)

If `envelope.size > 16 416`, split it:

```
id = random 63-bit, non-zero                 // sint64 field 1
for each 16 316-byte slice, index 0..n:
    part = sint64(1, id) ‖ uint(2, totalEnvelopeSize) ‖ uint(3, index) ‖ bytes(4, slice)
    send Envelope(30, part)
```

Reassembly rules (inbound): `index == 0` resets the buffer; every later part must carry
the same `id` and `size` and the next consecutive `index`; total size must be
`8 … 32 MiB`; overflow past the announced size is an error. When the buffer reaches
`size`, un-envelope it and process the inner message.

### 5.3 Protobuf subset

Hand-rolled, no `.proto` files, no generated code. Required wire types: varint (0),
64-bit (1), length-delimited (2), 32-bit (5). Field numbers 1–16.

- Parse into `Map<Int, List<Value>>`; accessors return the **last** value for a field.
- Unknown fields are kept and ignored, never an error.
- An invalid tag, field number `< 1`, truncated field, or unsupported wire type is a
  hard parse error.

**Signedness matters and is easy to get wrong:**

| Encoding | Fields |
|---|---|
| **sint64 (ZigZag)** | media IDs, capture timestamps, receive timestamps, content IDs, multipart IDs |
| **plain varint (uint64)** | receipt IDs, sizes, indices, enums, booleans, the `scale` flag |
| **fixed32 float** | focus point X/Y in upload metadata |

Sanity check: `sint64(field 1, -1)` must encode to `08 01`.

**Packed ID list** (kinds 33/34, field 1): concatenate ZigZag varints with no tags, then
wrap the whole run as a single length-delimited field 1.

## 6. Message catalogue

| Kind | Name | Direction |
|---|---|---|
| 1 | Request frame information | C→F |
| 2 | Return frame information | F→C |
| 3 | Send client information | C→F |
| 4 | Media metadata | both |
| 5 | Media data segment | both |
| 6 | Receipt | both |
| 10 | (frame-initiated; ack only) | F→C |
| 23 | Get media item | C→F |
| 27 | Request permission | C→F |
| 30 | Multipart wrapper | both |
| 31 | Request media list | C→F |
| 32 | Return media list | F→C |
| 33 | Change media visibility | C→F — *not in v1* |
| 34 | Delete media | C→F — *not in v1* |
| 35 | Display selected media | C→F — *not in v1* |

### Kind 2 — frame information

| Field | Type | Meaning |
|---|---|---|
| 1 | string | Frame name |
| 2 | string | Placement / room |
| 3 | uint | Panel width in px |
| 4 | uint | Panel height in px |
| 5 | uint→bool | permission: share pairing |
| 6 | uint→bool | permission: backup |
| 8 | uint→bool | permission: **view photos** |
| 9 | uint→bool | permission: **manage photos** |

Protocol version comes from the envelope, not a field.

**Observed on a real frame (2026-09-23).** Fields 1–4 decoded to the name and placement
set on the frame and an 800 × 1280 panel (width < height, i.e. the frame reports
portrait). The envelope carried protocol version **18**. A newly paired client got
`false` for all four permissions, so photo access always needs a kind 27 request that
the owner approves on the frame (§8.6).

### Kind 3 — client information

| Field | Type | Meaning |
|---|---|---|
| 1 | string | Sender name shown on the frame (≤ 100 chars) |

### Kind 4 — media metadata

**Outbound (upload):**

| Field | Type | Value |
|---|---|---|
| 1 | uint | byte length of the WebP payload |
| 2 | string | caption (may be empty) |
| 3 | float32 | focus X — `0.5` |
| 4 | float32 | focus Y — `0.5` |
| 5 | string | `"webp"` |
| 6 | sint64 | media ID we generate (random 63-bit, non-zero) |
| 9 | sint64 | capture time, **milliseconds** since epoch |
| 10 | sint64 | content ID = `BE.u64(sha256(data)[0:8]) & 0x7FFF_FFFF_FFFF_FFFF` |
| 12 | uint | `1` = show whole photo (fit), `2` = centred crop |

**Inbound (download response):** field 1 = total size, 2 = caption, 5 = file extension,
6 = sint64 media ID (must match the request), 11 = error submessage.

### Kind 5 — media data segment

| Field | Type | Meaning |
|---|---|---|
| 1 | bytes | chunk |
| 16 | uint | receipt ID — present only on the final chunk (outbound), or whenever the frame wants an ack (inbound) |

### Kind 6 — receipt

| Field | Type | Meaning |
|---|---|---|
| 1 | uint | receipt ID being acknowledged |
| 2 | message | error submessage, absent on success |

### Kind 23 — get media item

| Field | Type | Meaning |
|---|---|---|
| 1 | sint64 | media ID |
| 2 | uint | requested width — omit for the full-size original |
| 3 | uint | requested height — omit for the full-size original |

Send fields 2 and 3 together or not at all. The reference UI uses 400×400 for grid
thumbnails.

### Kind 27 — request permission

| Field | Type | Meaning |
|---|---|---|
| 1 | uint | `1` = view photos, `3` = view + manage |

v1 sends `3` (view and manage, D2 as revised on 2026-09-23). The frame shows a prompt; the owner taps Allow **on the frame**. There is
no push response — poll `GetInfo` (kind 1) every 2 s until the permission bits flip.

### Kind 31 / 32 — media list

Request: empty body. Response:

| Field | Type | Meaning |
|---|---|---|
| 1 (repeated) | message | one media item |
| 2 | message | error submessage |

Item submessage:

| Field | Type | Meaning |
|---|---|---|
| 1 | sint64 | media ID (**may be negative**) |
| 2 | uint | type: `0` photo, `1` video, `2` greeting |
| 3 | uint→bool | visible (i.e. in the slideshow) |
| 4 | sint64 | capture time, ms |
| 5 | sint64 | receive time, ms |

### Kinds 33 / 34 / 35 — manage (in v1 since 2026-09-23)

- **33** visibility: `1` = packed ID list, `2` = `0` hide / `1` show, `16` = receipt.
- **34** delete: `1` = packed ID list, `16` = receipt.
- **35** display now: `1` = sint64 ID. **No receipt is returned** — fire and forget.
- 33/34 accept 1…1000 IDs; 35 accepts exactly one. All three require *manage* permission.
- 33 and 34 are answered with a kind 6 receipt for field 16; an error submessage in the
  receipt's field 2 means the frame refused. Callers split longer lists into requests of
  at most 1000.

## 7. Error submessage

Field `1` is a uint code:

| Code | Client-facing message |
|---|---|
| 0 | Unspecified error |
| 1 | Unauthorized |
| 2 | Frame error |
| 3 | Bad request |
| 4 | Operation failed |
| 5 | Permission required on the frame |
| 6 | Photo no longer exists |
| 7 | Frame could not send this photo |
| 8 | Not found |
| 9 | Request declined |
| 12 | Frame limit reached |

Anything else → "Unknown error". Always keep the numeric code in the diagnostic log;
never put it in the UI. Copy for each is in [`03 - UX.md`](03%20-%20UX.md) §7.

## 8. Operation flows

### 8.1 Receive loop (shared by every operation)

Every inbound message passes through one loop that handles housekeeping before handing
the message to the caller:

```
loop:
  msg = transport.receive(deadline)
  (version, kind, body) = unenvelope(msg)
  fields = parse(body)

  if kind == 30:  accumulate multipart; continue until complete, then re-enter with
                  the reassembled inner message
  if kind ==  1:  send kind 3 { 1: senderName }        // frame asked who we are
  if kind ==  2:  cache frame info + protocol version
  if kind == 10 and fields.uint(16) != 0:
                  send kind 6 { 1: fields.uint(16) }   // unsolicited ack
  return (kind, fields)
```

Only **one** operation may be in flight per connection — the loop has no request
correlation beyond receipt IDs. Serialise per frame (§8.7).

### 8.2 Connect

1. TCP connect, handshake (§3.3), certificate check (§3.5).
2. Send kind 1, wait for kind 2 (10 s). Persist name/placement/size/permissions/version.

### 8.3 Upload a photo

```
id      = random63()
receipt = random63()
send kind 4 (metadata, §6)
chunk   = 16316   (924 if frameProtocolVersion < 4)
for each slice of data:
    body = bytes(1, slice)
    if last slice: body += uint(16, receipt)
    send kind 5 body
    report progress(bytesSent, total)
wait for kind 6 where field 1 == receipt        // up to 120 s
check field 2 error → success or a mapped failure
```

The receipt is the **only** proof the frame accepted the photo. A send is not complete
until it arrives; the queue must not mark an item done before then.

**Observed on a real frame (2026-09-23, protocol 18, via `framectl send`).** Uploading
needs **no permission**: both sends below succeeded while all four permission bits were
`false`. The kind 2 permissions control only gallery access (kinds 23, 31/32) and
management (33–35), never sending.

| File | Size | Kind 5 segments | Result |
|---|---|---|---|
| 800 × 1280 WebP, q85, flat test card | 12 628 B | 1 | receipt, no error |
| 800 × 1280 WebP, q92, noise | 488 466 B | 30 | receipt, no error; about 1.7 s end to end including JVM start and handshake |

So the 16 316-byte chunk size, the receipt in the last segment, and the kind 6 match all
work on hardware. Whether the photos actually *appear* on the frame, and how captions are
shown, must be checked by looking at the frame.

### 8.4 List media

Guard on `permissions.view` and `protocolVersion ≥ 13` before sending. Send kind 31,
wait for kind 32 (30 s), check field 2, map items.

### 8.5 Fetch a media item (preview or original)

Guard on `permissions.view`. Send kind 23; expect kind 4 (verify field 6 == requested
ID, check field 11, read size — reject > 64 MiB, reject 0), then accumulate kind 5
blobs, acking any field-16 receipts, until the announced size is reached. 45 s deadline.
Data arriving before metadata, or exceeding the announced size, is a protocol error.

### 8.6 Request permission

Send kind 27 `{1: 1}`, then poll kind 1/2 every 2 s until `permissions.view` is true or
the user cancels. Keep a generous overall timeout (the owner has to walk to the frame) —
5 minutes, cancellable.

### 8.7 Concurrency model

One TCP connection per frame, one operation at a time, guarded by a per-peer mutex.
Media transfers must never interleave: the protocol has no stream multiplexing, and
kind 5 segments carry no stream identifier.

### 8.8 Timeouts (from the reference client)

| Phase | Timeout |
|---|---|
| TCP connect | 8 s |
| Handshake (whole) | 12 s |
| PACE receive (each) | 12 s |
| `GetInfo` | 10 s |
| List media | 30 s |
| Fetch media | 45 s |
| Record write | 30 s |
| Receipt wait | 120 s |
| Whole job (reference) | 4 min |

## 9. Known unknowns

Record these as-is; do not invent behaviour.

1. **`framedump_local`** — why this protocol string is accepted is unknown. Keep it
   byte-identical.
2. **Kind 10** — the frame sends it; the reference client only acks it and ignores the
   body. Log the body in debug builds to learn what it carries.
3. **Kind 2 field 7** and **kind 4 fields 7, 8, 11 (outbound)** — unused by the
   reference client, meaning unknown.
4. **Video upload** — no known-good metadata shape. `extension` is a string, so `"mp4"`
   is the obvious guess, but duration limits, transcoding expectations and the frame's
   acceptance criteria are unverified. Out of scope (D8).
5. **`greeting`** media type (2) — appears in listings; creation path unknown.
6. **Frame storage limit** — surfaces only as error code 12 after the fact. There is no
   known query for free space.
7. **Issuer list churn** — a frame manufactured after the reference list was captured
   may present an unknown issuer. See §3.5. The one frame tested so far (2026-09-23)
   is covered by the list. That is one data point, not proof that the list is complete.

## 10. Porting checklist

Go source → Kotlin target. Tick these off; each is independently testable.

| Go | Kotlin | Notes |
|---|---|---|
| `wire.go` `Parse`/`Fields`/`Uint`/`Blob`/`Text`/`Float`/`Sint`/`PackedIDs` | `wire/Protobuf.kt` | Pure, no I/O. Fuzz-friendly. |
| `wire.go` `Envelope`/`Unenvelope` | `wire/Envelope.kt` | |
| `transport.go` `encodeMetadata`/`decodeMetadata` | `transport/Metadata.kt` | |
| `transport.go` `nonce` | `crypto/Nonce.kt` | 16-char prefix + u64be |
| `transport.go` `Dial` | `transport/MdgTransport.connect()` | Inject a `SocketFactory` so tests and the Wi-Fi-bound factory both work. |
| `transport.go` `Send`/`Receive`/`decrypt`/`read`/`write` | `transport/MdgTransport` | |
| `issuers.json` | `assets/issuers.json` | ~250 hex keys |
| `pairing.go` `pairingResponse`/`Pair` | `pairing/Pace.kt` | Pure function + a driver; the pure part takes the vectors. |
| `client.go` `send`/`receive`/multipart | `client/FrameoClient` | |
| `client.go` `GetInfo`/`Upload`/`List`/`Download`/`RequestPermission` | `client/FrameoClient` | `Change` deferred to post-v1. |
| `client.go` `frameError` | `client/FrameError.kt` | Code → typed error, message resolved in the UI layer. |
| `discovery.go` `Discover` | `discovery/FrameDiscovery` (interface) | Android impl uses `NsdManager`; the JVM test impl is a stub. |
| `store.go` | Room + DataStore | See [`02 - Architecture.md`](02%20-%20Architecture.md) §5. |
