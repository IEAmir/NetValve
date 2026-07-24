# How NetValve throttling works

## The token bucket

Each controlled app has, per direction, a **token bucket** (`throttle/TokenBucket.kt`):

- **capacity** `C` (bytes) — the maximum burst.
- **rate** `R` (bytes/second) — the sustained throughput.
- **tokens** `t` — current allowance (bytes). Starts full (`t = C`).

Tokens accrue continuously at `R`. Sending `n` bytes spends `n` tokens. The
long-run average throughput is bounded by `R`; short bursts up to `C` are allowed.

### Lazy refill (no timers)
We never run a refill timer. Instead, on each operation we compute how many tokens
*would* have accrued since the last operation using a monotonic clock:

```
dt      = now - lastNanos                    // nanoseconds elapsed
accrued = dt / 1e9 * R                        // bytes earned since last op
t       = min(C, t + accrued)                 // never exceed capacity
```

This is allocation-free and O(1) — essential on the packet hot path.

### Pacing, not polling, not dropping
Callers do **not** spin or drop. They call `reserveNanos(n)`, which subtracts `n`
from the balance (allowing it to go **negative** — i.e. take on debt) and returns
how long to wait for that debt to be repaid at rate `R`:

```
t -= n
if (t >= 0) return 0                          // allowance was available
deficit = -t
waitNanos = ceil(deficit / R * 1e9)           // time until debt clears
```

`ThrottleManager.pace()` then simply `delay(waitNanos)`. Because the relay
coroutine suspends:

- **TCP**: we stop reading the socket, the OS receive window fills, and the sender
  slows down — real end-to-end backpressure, zero packet loss.
- **UDP**: datagrams are paced through a small bounded queue (below).

`rate <= 0` means **unlimited**: `reserveNanos` returns `0` immediately, so an
unshaped flow pays essentially no overhead.

## Insertion points (explicit)

```
UPLOAD    app → VPN TUN → netstack → relay → [pace(uid, UPLOAD)]   → upstream.write() → Internet
DOWNLOAD  Internet → upstream.read() → [pace(uid, DOWNLOAD)] → relay → netstack → VPN TUN → app
```

Implemented in `network/FlowSupervisor.kt`:
- **Upload**: read from the app-side stream → `pace(uploadBucket, n)` → write to
  the protected upstream socket → record stats.
- **Download**: read from the upstream socket → `pace(downloadBucket, n)` → write
  to the app-side stream → record stats.

The bucket is selected per `(uid, direction)` by `ThrottleManager`, sized from the
effective policy for that app at flow-open. When a rule or device state changes,
`RuleEngine.revision` fires and every live bucket's rate is refreshed **in place**,
so a cap edit takes effect on in-flight connections without reconnecting.

### Burst sizing
`capacity = max(rate / 4, 64 KiB)` (~250 ms of burst, floored so a full app-layer
write never deadlocks). Tunable in `ThrottleManager.burstFor`.

## UDP: paced, not dropped

UDP has no backpressure, so we smooth instead of block:

1. Datagrams from the app enter a bounded `PacingQueue` (256 KiB by default).
2. A pacer coroutine dequeues, `pace()`s on the upload bucket, then sends upstream.
3. Only when the queue is already full (the app is *sustainably* over its cap) is a
   datagram dropped — `DROP_OLDEST` by default — and every drop is counted and
   logged. This protects VoIP/gaming/streaming under normal bursts while bounding
   memory under abuse.

**DNS (UDP/53, DoT/853) is exempt from throttling by default** (`exemptDns`), so
name resolution — and therefore connection setup latency for every app — is never
delayed by a cap.

## Unit conversions (exact, tested)

| Unit | Bytes/second for value `v` |
|---|---|
| `kbps` | `v * 1000 / 8` |
| `Mbps` | `v * 1_000_000 / 8` |
| `KB/s` | `v * 1024` |
| `MB/s` | `v * 1024 * 1024` |

Bit-rates use SI (decimal, as ISPs quote); byte-rates use IEC (binary, as file
managers display). Asserted in `PersistenceTest.bandwidthUnitConversionsAreExact`.

## Worked example
Cap an app's download to **2 Mbps** → `R = 250,000 B/s`, `C = max(62,500, 65,536) =
65,536 B`. The app may burst ~64 KB instantly, after which sustained throughput
settles to 250 KB/s. A 16 KB relay chunk when the bucket is empty waits
`16,384 / 250,000 ≈ 66 ms`.
