# FrameAlt 

An Android app that sends photos to a Frameo digital photo frame over the local
network — no account, no cloud, no subscription limits.

Based on the: [`yasoob/frameo-client`](https://github.com/yasoob/frameo-client) implementation.


## v1 specs

| # | Decision |
|---|---|
| D1 | LAN only — phone and frame on the same Wi-Fi |
| D2 | v1 = send photos + browse the frame's gallery (read-only) |
| D3 | Pure Kotlin protocol port, BouncyCastle crypto, no NDK/JNI/Go |
| D4 | Entry points: Android share sheet + in-app photo picker |
| D5 | Photos downscaled to the frame's panel resolution, WebP q≈85 |
| D6 | One frame (storage model still supports more) |
| D7 | Persistent send queue in a foreground service, with retries |
| D8 | No video in v1 |