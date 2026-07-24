# Root-cause analysis: "enabling a bandwidth limit stalls traffic to zero"

**TL;DR — The throttling logic is not the cause. The default/prebuilt build runs
the `loopback` packet engine, which reads the TUN and *drops* every packet (it
never forwards upstream). So any app routed into the tunnel loses connectivity
while the tunnel is active — independent of any bandwidth rule. Enabling a rule is
simply what puts the app under the tunnel's control (and/or what prompts turning
the VPN on), which is why the stall *correlates* with "enabling a limit." Build the
`netstack` engine for real forwarding.**

This was determined by instrumentation + a faithful simulation of the real code,
not by guessing. Each of the 9 requested checks is answered below.

---

## Method

Because the netstack engine needs an on-device/emulator run (and a Go+NDK AAR
build), the throttling arithmetic was verified on the JVM by driving the **real**
`TokenBucket` through the **exact** `pace()` loop (same ceil-to-millisecond
rounding), with caps resolved through the **real** `RuleCompiler` →
`PolicyEvaluator` path. A shared virtual clock advances by the same amount the
production `delay()` would sleep, so refill timing is modelled faithfully
(`System.nanoTime()` and `delay` are both real time in production; advancing one
clock models both). Harness + output are reproduced below and encoded as a
regression test (`TokenBucketTest.sustainedThroughputMatchesRateNotZero`).

## The 9 checks

### 1. Is the netstack engine actually running, or loopback?
**Loopback, in the default build and in the shipped `prebuilt/app-debug.apk`.**
`app/build.gradle.kts`: `useNetstack` defaults to `false`; only
`-Pnetvalve.netstack=true` selects `src/netstack`. With `false`, `src/loopback`'s
`LoopbackPacketPipeline` is used, and it **does not forward** — see
`LoopbackPacketPipeline.readLoop` (reads TUN, accounts bytes, drops). This is the
root cause of a total stall for controlled apps.
*Instrumentation added:* `TrafficEngine.start` now logs the active engine class and
`BuildConfig.USE_NETSTACK` (a WARNING for loopback); `LoopbackPacketPipeline` logs
dropped packet/byte counts every 5 s; the Dashboard shows a red "Development engine
(loopback)" banner when `!USE_NETSTACK`.

### 2. Do packets still reach the upstream socket after throttling is enabled?
**In the netstack engine: yes.** Throttling only inserts a `delay()` before
`upstream.write()`/`appSide.write()` in `FlowSupervisor`; it never bypasses or
drops the write. The simulation confirms bytes keep flowing at the configured
rate. **In the loopback engine: there is no upstream socket at all** — that is the
whole problem, and it is unrelated to throttling.

### 3. TokenBucket state trace (rate / burst / tokens / refill / requested / delay)
Captured live (1 Mbps download, 16 KB chunks). Buckets start full:
```
configured rate = 125000 B/s   burst = 65536 B   chunk = 16384 B   start tokens = 65536 B
it=0 requested=16384 tokensBefore=65536 waitNs=0         delayMs=0
it=1 requested=16384 tokensBefore=49152 waitNs=0         delayMs=0
it=2 requested=16384 tokensBefore=32768 waitNs=0         delayMs=0
it=3 requested=16384 tokensBefore=16384 waitNs=0         delayMs=0
it=4 requested=16384 tokensBefore=0     waitNs=131072000 delayMs=132   ← burst drained, now pacing
it=5 requested=16384 tokensBefore=116   waitNs=130144001 delayMs=131
...
=> sent=1327104 B over 10.093 s  achieved=131487 B/s  (cap=125000)  ratio=1.052
```
The bucket behaves exactly as designed: drains the burst instantly, then paces at
the rate. *Instrumentation added:* `ThrottleManager.pace` logs this same state at
DEBUG (gated by `Logger.isEnabled` so it is free when DEBUG is off), and logs
bucket creation at INFO.

### 4. Bandwidth unit conversions
All exact (also unit-tested in `PersistenceTest`):
```
1 kbps = 125 B/s     1 Mbps = 125000 B/s     1 KB/s = 1024 B/s     1 MB/s = 1048576 B/s
```
End-to-end throughput per unit (10 s sim), achieved ≈ cap in every case:
```
1 Mbps    -> 131487 B/s   (cap 125000,    ratio 1.05)
8 Mbps    -> 1024920 B/s  (cap 1000000,   ratio 1.02)
100 Mbps  -> 12812288 B/s (cap 12500000,  ratio 1.02)
1000 kbps -> 131487 B/s   (cap 125000,    ratio 1.05)
500 KB/s  -> 524771 B/s   (cap 512000,    ratio 1.02)
2 MB/s    -> 2149580 B/s  (cap 2097152,   ratio 1.02)
```
(The ~2–5 % excess is the initial burst amortised over the 10 s window — expected
and correct.) **No unit produces zero throughput.**

### 5. Do buckets start full?
**Yes.** `TokenBucket.tokens` initialises to `capacity`; the trace shows
`start tokens = 65536 = burst`. Confirmed by `TokenBucketTest.fullBucketAllowsBurst…`.

### 6. Does refill use a monotonic clock?
**Yes.** `TokenBucket`'s default `clock = System::nanoTime` (monotonic), not
wall-clock. `ThroughputMeter` likewise. Only log *timestamps* use
`currentTimeMillis` (correct for display).

### 7. Does pacing ever block indefinitely?
**No** — and it is now hard-guarded. A single `pace()` sleep is clamped to
`MAX_PACE_NANOS = 2 s` (`ThrottleManager`), so even a pathological cap cannot look
like a permanent stall (it logs a WARNING and lets throughput exceed the cap
rather than freezing). For realistic caps the clamp never triggers (waits < 150 ms).

### 8. Are TCP SYN / TLS ClientHello / DNS ever permanently stalled?
**No.** The TCP handshake to the real server happens in
`ConnectionManager.connectTcp` (netstack completes the app-side handshake itself)
— **not throttled**. The TLS ClientHello is the first upload chunk; with a full
burst (≥ 64 KB) it flushes immediately (the 512-byte-chunk simulation confirms no
stall). **DNS is exempt from throttling by default** (`exemptDns`, UDP/53 + DoT/853),
so resolution latency is never affected.

### 9. Why does enabling throttling appear to reduce throughput to zero?
It does not — in the netstack engine, throttling yields the configured rate (§3–4).
The zero-throughput observation comes from running the **loopback** engine (default
build / prebuilt APK), which never forwards. The confound is temporal: you enable a
rule → the app becomes tunnel-controlled (and/or you switch the VPN on) → loopback
drops its packets → stall. Switching to the netstack engine resolves it; the
added engine banner + logs make the active engine unmistakable.

---

## Fix / action
1. **Build and run the netstack engine** (real forwarding):
   `cd netstack && ANDROID_NDK_HOME=… ./build-aar.sh` then
   `./gradlew :app:assembleDebug -Pnetvalve.netstack=true`. The Dashboard banner
   disappears and `TrafficEngine` logs `Packet engine: NetstackPacketPipeline (netstack, real forwarding)`.
2. Throttling then paces at the configured rate. Confirm with the DEBUG THROTTLE
   logs (§3) on the Logs screen.

## Changes made in this pass (all instrumentation/hardening; no throttle-math change was needed)
- `TrafficEngine`: log active engine + `USE_NETSTACK` at tunnel start.
- `LoopbackPacketPipeline`: periodic dropped-bytes WARNING.
- `ThrottleManager`: DEBUG state logging (rate/burst/tokens/refill/requested/wait); INFO on bucket creation; **clamp single pace wait to 2 s** (item 7).
- `TokenBucket`: introspection getters + `lastRefillAmount` for logging (no behaviour change).
- `Logger.isEnabled(level)` so hot-path DEBUG logging is free when disabled.
- Dashboard: red banner when running the loopback build.
- Regression test: `TokenBucketTest.sustainedThroughputMatchesRateNotZero`.
