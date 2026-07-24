# Limitations, platform constraints, and tradeoffs

NetValve is intentionally honest about what a **non-root, local** traffic
controller can and cannot do on Android. Everything here is also reflected in code
comments near the relevant logic.

## 1. One VPN at a time (platform rule)
Android allows a single active `VpnService`. Consequences and handling:
- Starting **another** VPN app revokes ours → `NetValveVpnService.onRevoke()`
  stops the engine cleanly, persists `enabled=false`, and surfaces a *Revoked*
  status. No crash, no zombie tunnel.
- Starting **NetValve** while another VPN is active shows the system's
  replace-VPN consent dialog (standard `VpnService.prepare`).
- After reboot, the tunnel re-arms only if it was enabled, auto-start is on, and
  consent still stands (`BootReceiver`).

## 2. Changing the controlled-app set requires a tunnel rebuild
`addAllowedApplication`/`addDisallowedApplication` are fixed at `establish()`.
Editing the selection while running therefore needs a **rebuild** — NetValve does
a seamless `ACTION_RESTART` (stop engine → re-establish → restart). Editing a
*rule's caps/conditions* does **not** need a rebuild; it applies live via
`RuleEngine.revision`.

## 3. Per-app attribution
Non-root attribution uses `ConnectivityManager.getConnectionOwnerUid` (API 29+),
which is why **minSdk is 29**. It is racy and imperfect, so `UidResolver` layers:
1. a short-TTL cache per 4-tuple;
2. the kernel lookup, wrapped so exceptions never reach the packet loop;
3. single-controlled-app inference (if exactly one app is controlled);
4. an **Unknown** bucket (global default policy) otherwise.

The engine never crashes on attribution failure; failures are counted and
rate-limited in the log. On some OEM ROMs a fraction of flows may be *Unknown* —
they still flow and are shaped by the global default.

## 4. IPv6 behavior (explicit)
IPv6 is **always routed into the tunnel** (so it cannot bypass shaping), with two
modes (`GlobalSettings.ipv6Mode`):
- **RELAY (default)** — IPv6 TCP/UDP is terminated, attributed, and shaped exactly
  like IPv4 by the netstack engine.
- **FAST_REJECT** — IPv6 flows are refused immediately (TCP RST / ICMPv6
  unreachable) so apps fall back to IPv4 within milliseconds. This exists so that
  on networks where upstream v6 misbehaves, users never see long connection
  timeouts. It is *reject-fast*, not *silently-drop* (which would cause the very
  timeouts we want to avoid).

Why not "just don't route v6"? If v6 isn't routed into the TUN, the OS sends it
directly, unshaped — a bypass. Routing + fast-reject is the correct
non-leaky degrade.

## 5. Loopback engine does not forward
The default build uses the pure-Kotlin `loopback` engine so the project compiles,
runs, and is testable **without** the Go/NDK toolchain. It establishes the tunnel,
attributes UIDs, records outbound byte stats, applies block-by-drop, and drives the
whole UI — but it does **not** forward upstream, so controlled apps have no
connectivity under it. Real forwarding + shaping requires the **netstack** engine
(`-Pnetvalve.netstack=true` after `netstack/build-aar.sh`).

## 6. DNS
DNS is forwarded and, by default, **exempt from throttling** to protect latency.
NetValve observes DNS to populate an IP→hostname cache for future modules but does
not currently rewrite DNS. If the user has **Private DNS (DoT)** enabled system-wide
to an external resolver, encrypted DNS still traverses the tunnel but is opaque to
domain inspection — expected and documented.

## 7. Battery / Doze / OEM ROMs
A foreground `VpnService` is largely Doze-exempt while running, but aggressive OEM
ROMs (Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Vivo, Samsung/One UI) still kill
background apps and block auto-start. NetValve **detects** battery-optimization
state and offers the standard exemption prompt plus **per-vendor** guidance and, if
resolvable, a deep link to the vendor's auto-start settings (`BatteryOptimizations`).
It cannot silently defeat these (no device-admin, no root) — by design (non-goal).

## Tradeoffs chosen (and why)

| Decision | Alternative | Why chosen |
|---|---|---|
| **gVisor netstack** for the engine | Hand-written TCP stack; tun2socks; lwIP | Requirement: use a mature stack. netstack preserves the original 4-tuple (needed for per-app attribution), unlike a SOCKS boundary; lwIP needs heavy C glue. |
| Throttling in **Kotlin** at the socket boundary | Throttle inside Go | Keeps all product logic testable in Kotlin and the shaping insertion point singular and explicit. |
| **Suspend-to-pace** | Drop packets | Preserves app stability (VoIP/streaming/gaming); TCP backpressure does the work. |
| **DataStore for rules, Room for stats/logs** | All-Room | Rules/settings are small structured blobs (atomic JSON); stats/logs are append/aggregate — Room fits. |
| **Two build source sets** for the engine | Single module requiring Go always | Lets reviewers build/test the whole app + UI without the native toolchain. |
| **minSdk 29** | Lower | `getConnectionOwnerUid` (attribution) and modern `VpnService`/FGS semantics require it. |

## Performance targets

| Target | Approach in code |
|---|---|
| **300+ concurrent TCP connections** | One coroutine pair per flow on `Dispatchers.IO`; netstack multiplexes; no thread-per-connection. |
| **Idle CPU < 2%** | Lazy-refill buckets (no timers), suspend-based pacing, stats sampled ~1 Hz, no busy loops anywhere. |
| **Throughput overhead < 5% unthrottled** | Unlimited direction → `null` bucket → `pace()` returns immediately; 16 KB relay chunks. |
| **RAM < 50 MB normal** | 16 KB relay buffers, bounded UDP queue (256 KB), flow-table + DNS cache capped with eviction, bounded log ring + Room trim. |
| **No busy waiting** | All waits are `delay()`/suspension; verified by design. |
| **Zero ANRs** | No main-thread I/O (StrictMode in debug); `startForeground` posted immediately on START. |

### Suggested on-device test protocol
1. Select a heavy app (e.g. a browser or a speed-test app), cap download to 2 Mbps,
   run a speed test → confirm sustained ≈ 2 Mbps and smooth (no stalls).
2. Open 300+ parallel connections (e.g. `iperf3 -P` or many browser tabs) →
   watch **Stats → Active** and confirm CPU/RAM in Android Studio Profiler stay
   within targets.
3. Toggle "background only", background the app → confirm shaping engages;
   foreground it → confirm it lifts (requires usage access).
4. Start another VPN → confirm NetValve shows *Revoked* and stops cleanly.
5. Leave running 30 min screen-off → confirm the tunnel survives (battery
   exemption granted).
