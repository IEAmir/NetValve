# NetValve — Agent Handoff & Development Log

> **Purpose**: This file lets a new engineer or AI agent continue this project
> with zero prior context. It records what was built, how it was verified, every
> problem hit (and its fix), the exact environment, and the prioritized remaining
> work. Read this first; then `README.md` → `docs/ARCHITECTURE.md`.

- **Project**: NetValve — root-free Android per-app traffic controller (local `VpnService` tunnel, per-app throttle/block/schedule, stats, logs).
- **State**: ✅ All required deliverables complete. ✅ Compiles. ✅ APK built. ✅ 33/33 unit tests pass. ⏳ One optional native artifact (gVisor netstack AAR) is scripted but not yet built (needs Go + NDK).
- **Origin thread**: Hyperagent thread `cmrwbx7hi0zbz07ad96qne4ye` (https://hyperagent.com/thread/cmrwbx7hi0zbz07ad96qne4ye), 2026-07-22.

---

## 1. Current status at a glance

| Acceptance criterion | Status | Evidence |
|---|---|---|
| Compiles successfully | ✅ | `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (see §3 log, `docs/BUILD_VERIFICATION.md`) |
| Lists installed apps | ✅ | `PackageManagerAppRepository` + Apps screen (search, system-app filter) |
| Starts/stops a VPN session | ✅ | `NetValveVpnService` + `VpnController` (+pause/resume, restart, onRevoke) |
| Applies throttling rules to selected apps | ✅ | `RuleEngine` → `ThrottleManager` token buckets in `FlowSupervisor` relay |
| Persists selections and settings | ✅ | DataStore JSON; round-trip covered by `PersistenceTest` |
| Live stats in UI | ✅ | `StatsCollector` 1 Hz sampling → Dashboard/Stats screens |
| No main-thread blocking / leaks | ✅ (design + StrictMode debug) | `StrictModeConfig`, IO dispatchers, suspend-based pacing |
| Unit tests (rules, token bucket, persistence) | ✅ 33/33 | 7 suites, run standalone-JVM **and** under Gradle/AGP |
| Instrumentation test | ✅ compiles (needs device to run) | `AppSelectionFlowTest` |
| README + sample rules + throttling explainer | ✅ | `README.md`, `docs/sample-rules.json`, `docs/THROTTLING.md` |

**The one caveat** (by design, documented): the default build uses the pure-Kotlin
**loopback** engine, which drives everything except actual upstream forwarding.
Real forwarding requires building the **gVisor netstack AAR** (`netstack/build-aar.sh`,
needs Go 1.22+ + Android NDK) and building with `-Pnetvalve.netstack=true`.
This split is intentional so the project compiles/tests without a native toolchain.

## 2. Requirements provenance (what shaped the design)

The user's spec plus **12 explicit change requests** (all implemented):
1. Mature stack instead of custom TCP → **gVisor netstack** via gomobile bridge (tun2socks rejected: loses source 4-tuple → breaks per-app attribution; lwIP rejected: heavy C glue). See `netstack/README.md`.
2. Explicit throttle insertion points → documented pipelines in `docs/THROTTLING.md` + `FlowSupervisor` comments. Upload: app→TUN→netstack→relay→**bucket**→upstream.write. Download: upstream.read→**bucket**→relay→netstack→TUN.
3. UDP **paced not dropped** → `PacingQueue` (bounded, tail-drop last resort); DNS exempt by default.
4. Generic policy engine → `RuleCondition` (network/roaming/charging/battery/screen/foreground/time/day) × `RuleAction` (Allow/Block/Throttle); `AppRule` is *compiled* to generic rules (`RuleCompiler`).
5. VPN coexistence → `onRevoke`, replace-consent flow, boot re-arm (`BootReceiver`), process-death recovery (STICKY + persisted enabled flag).
6. Richer stats → live/avg/peak/session throughput, active/throttled/blocked conns, DNS count, connect latency, last reset (`StatsCollector`).
7. Leveled logging + export → `NetValveLogger` (ring buffer + Room, 250 ms rate-limit per hot category, share-sheet export).
8. Battery/OEM → `BatteryOptimizations` (Doze exemption prompt, vendor deep-links for Xiaomi/Huawei/Oppo/Vivo/Samsung), typed FGS `specialUse`.
9. UID fallback chain → `UidResolver`: cache → `getConnectionOwnerUid` (wrapped, never throws out) → single-app inference → `UID_UNKNOWN` bucket. Unit-tested.
10. IPv6 explicit → routed into tunnel always (no bypass); `RELAY` (default) or `FAST_REJECT` (immediate RST/ICMPv6 → no timeouts). `Ipv6Mode` in settings.
11. Plugin architecture → `TrafficModule` hooks (flow-open/bytes/close) via Hilt multibindings; block+throttle are themselves modules (`DefaultPolicyModule`); DNS→IP cache ready for domain rules. See `docs/EXTENDING.md`.
12. Performance targets → 300+ conns, idle CPU <2%, overhead <5%, RAM <50 MB, no busy-wait, zero ANR; test protocol in `docs/LIMITATIONS.md`.

## 3. Development log (chronological)

All times local (UTC+3:30), 2026-07-22.

| Time | Event |
|---|---|
| ~20:31 | Plan v1 drafted (custom userspace TCP stack). |
| ~20:44 | User requested 12 changes; plan v2 rewrote engine around gVisor netstack, generic policy engine, UDP pacing, etc. Approved ~20:51. |
| ~20:55 | Toolchain installed in sandbox: Temurin **JDK 17.0.19**, **Kotlin 2.0.21** compiler, **Gradle 8.10.2**, JUnit console 1.10.2. Gradle wrapper generated (needed `--no-validate-url` because the wrapper task's URL HEAD check fails behind the egress proxy). |
| 21:00–21:40 | Wrote build system (version catalog, engine-swap source sets), full domain model, policy engine, throttle core, network layer, plugin pipeline, loopback + netstack engines, Go bridge (`bridge.go`, `conn.go`, `build-aar.sh`), data layer, service layer, DI. |
| 21:40–22:00 | Compose UI (5 screens + ViewModels + theme + navigation), 7 unit-test files, 1 instrumentation test. |
| ~22:05 | **Standalone JVM verification**: 32 framework-free core files compiled with `kotlinc`; **33/33 tests green** via JUnit console. Fixed nothing in core logic — it was correct first pass. |
| ~22:10 | Added stable `@SerialName` discriminators to sealed `RuleCondition`/`RuleAction` (refactor-proof persistence); regenerated + validated `docs/sample-rules.json` **by executing the real serializers**. |
| ~22:15 | Docs written: README, ARCHITECTURE, THROTTLING, LIMITATIONS, EXTENDING, netstack/README, LICENSE (Apache-2.0), .gitignore. Static cross-checks: all `R.*` refs resolve; imports/annotations audited. |
| ~21:52 | First SDK download attempt returned a **48-byte "Session token revoked" page** instead of the zip (transient sandbox egress hiccup) → BadZipFile. |
| ~22:03 | Retry succeeded (146 MB cmdline-tools 11076708). |
| ~22:05 | `sdkmanager` failed: *IO exception while downloading manifest*. Root cause: sandbox does **TLS interception** (`CN=whoami-sandbox-ca`); the CA is in the system PEM bundle but **not** in the JDK truststore. Fix: extracted the CA from `/etc/pki/tls/certs/ca-bundle.crt` (cert #146) and `keytool -importcert` into `$JAVA_HOME/lib/security/cacerts` (backup at `cacerts.orig`). Java TLS then worked (HTTP 200). |
| ~22:06 | Licenses re-accepted (first `--licenses` run had silently accepted nothing while the manifest fetch was failing). Installed `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` (AGP later auto-added build-tools 34.0.0). |
| ~22:07 | **Build #1**: Gradle daemon **OOM-killed** at `:app:kspDebugKotlin` (daemon + Kotlin daemon + KSP exceeded the 4 GB sandbox). Fix: single-JVM config in `gradle.properties` → `org.gradle.daemon=false`, `kotlin.compiler.execution.strategy=in-process`, `-Xmx2600m`, incremental off. |
| ~22:09 | Pre-build audit caught 2 latent errors before the compiler did: missing `import dev.netvalve.throttle.ThrottleManager` in `TrafficEngine.kt`; invalid `import androidx.compose.foundation.lazy.item` in Dashboard/Stats screens (`item` is a member fn of `LazyListScope`, not importable). Fixed. |
| ~22:11 | **Build #2**: one real compile error — `BatteryOptimizations.kt:71` destructuring failed because an explicit `Pair<...>?` type annotation defeated the `?: return` smart-cast. Fix: drop the annotation, let inference smart-cast. |
| ~22:13 | **Build #3: BUILD SUCCESSFUL (1m 10s)** → `app-debug.apk` (60,665,802 bytes; copy at `prebuilt/app-debug.apk`). |
| ~22:15 | `:app:testDebugUnitTest` → **33 tests, 0 failures** (7 suites). `:app:compileDebugAndroidTestKotlin` → SUCCESS. |
| ~22:19 | Packaged `NetValve.zip` (sources + docs + wrapper + APK; build dirs & `local.properties` excluded) and delivered. |
| ~22:31 | This handoff written. |

## 4. Environment

### 4a. This sandbox (if you are continuing in the same workspace)
Everything lives under `/agent/workspace`:

```
/agent/workspace/NetValve            ← the project (this repo)
/agent/workspace/tools/env.sh        ← source this: JAVA_HOME, PATH(+kotlinc), JUNIT_JAR
/agent/workspace/tools/jdk-17.0.19+10       (JDK; cacerts PATCHED with whoami-sandbox-ca; original = cacerts.orig)
/agent/workspace/tools/kotlinc              (Kotlin 2.0.21 CLI)
/agent/workspace/tools/gradle-8.10.2        (Gradle dist; wrapper also works)
/agent/workspace/tools/gradle-home          (GRADLE_USER_HOME — ~1.6 GB warm dependency cache)
/agent/workspace/tools/android-sdk          (cmdline-tools, platform-35, build-tools 35.0.0 & 34.0.0, platform-tools)
/agent/workspace/tools/libs                 (plain-JVM jars for standalone core testing)
/tmp/gradle-build*.log, /tmp/gradle-test.log  (raw build logs from the session)
```

Canonical build invocation used here:
```bash
export ANDROID_HOME=/agent/workspace/tools/android-sdk
export GRADLE_USER_HOME=/agent/workspace/tools/gradle-home
source /agent/workspace/tools/env.sh
cd /agent/workspace/NetValve
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
```
`local.properties` (`sdk.dir=/agent/workspace/tools/android-sdk`) exists in the
workspace copy but is intentionally **excluded from the zip**.

**Sandbox rules of thumb** (4 GB RAM, 2 vCPU):
- Keep `gradle.properties` as-is (single JVM, no daemons, no parallel). Re-enabling the daemon/Kotlin-daemon **will** OOM at KSP.
- If any *Java* process hits TLS errors on a new JDK, re-import the sandbox CA (see §3, 22:05 entry).
- Network is HTTP/HTTPS only; `curl` works out of the box.

### 4b. Fresh machine (portable reproduction)
JDK 17 + Android SDK (compileSdk 35) is all you need for the default build:
```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
For the production engine: Go 1.22+, NDK, then `cd netstack && ./build-aar.sh`,
then `./gradlew :app:assembleDebug -Pnetvalve.netstack=true`.
On a normal machine you may restore daemon/parallel settings in `gradle.properties` for speed.

## 4c. Throttling "stall" investigation (2026-07-23)

A user reported that enabling any bandwidth limit stalled traffic to zero.
**Root cause: the loopback engine (default/prebuilt build) never forwards
upstream — not the throttle logic.** The token bucket was proven correct via a
faithful JVM simulation (real `TokenBucket` + `RuleCompiler` + `PolicyEvaluator`
through the exact `pace()` loop): every realistic cap achieves ≈1.0× the rate,
never zero. Full write-up + the 9-point verification is in
**`docs/DEBUG_THROTTLING.md`**. Instrumentation added this pass: engine identity +
`BuildConfig.USE_NETSTACK` logged at tunnel start; loopback periodic dropped-byte
WARNING; `ThrottleManager` DEBUG state logs + a 2 s single-wait clamp;
`Logger.isEnabled`; a Dashboard "loopback" banner; regression test
`TokenBucketTest.sustainedThroughputMatchesRateNotZero`. If throttling ever
"stalls" again: first confirm the engine (Logs screen / banner), then read that doc.

## 4d. Netstack AAR built + integrated (2026-07-23)

The production gVisor engine is now **built and integrated** (was previously
source-only). Key facts:
- Engine = **`github.com/sagernet/gvisor`** fork (upstream `gvisor.dev/gvisor` is
  NOT plain-go/gomobile buildable — Bazel-generated lists/refcounts/marshal aren't
  committed; `pkg/sync` ships `.tmpl.s` templates). `bridge.go`/`conn.go` import the
  fork; `go.mod` pins `v0.0.0-20250811.0-sing-box-mod.1`.
- `netstack/build-aar.sh` (verified) → `app/libs/netstack.aar` (3.8 MB, arm64
  `libgojni.so`). Needs `ANDROID_NDK_HOME` + `GOTOOLCHAIN=auto` (auto-fetches Go ≥1.25).
- `./gradlew :app:assembleDebug -Pnetvalve.netstack=true` → **BUILD SUCCESSFUL**;
  `prebuilt/app-netstack-arm64-debug.apk` (66 MB) contains the engine.
- Two adapter fixes vs. current fork API: `udp.NewForwarder` handler returns
  `(handled bool)`; `TCPConn.read` is `long` + EOF-as-exception → converted in
  `NetstackPacketPipeline.TcpConnStream`.
- Validation: forwarding+throttling proven on **real sockets** (host) + a runnable
  **on-device** test (`ThrottleForwardingInstrumentedTest`). Physical-device 3-app
  run is NOT possible in this sandbox (no device/KVM) — see `docs/NETSTACK_EVIDENCE.md`
  for the Firebase Test Lab command + manual protocol. Do NOT fabricate device results.

## 4e. "Works then stops after a few seconds" fix (2026-07-24)

Netstack engine forwarded traffic but stalled after seconds. **Root cause: relay
thread‑pool starvation** — 2 blocking coroutines per flow on `Dispatchers.IO`
(64‑thread cap); a busy page's concurrent keep‑alive flows exhaust it (~31 flows
→ stall). **Fix:** relay scope now uses `Dispatchers.IO.limitedParallelism(512)`
(`TrafficEngine.RELAY_PARALLELISM`). Reproduced on real sockets: 64→31/120 served,
512→120/120. Also hardened the Go bridge: panic‑recover in both forwarders, wired
the `Log` callback to the Kotlin Logger, and a 5 s liveness beacon
(`[netstack] netstack alive: tcpFlows/udpFlows`) for triage. Full write‑up +
device confirmation steps: `docs/NETSTACK_EVIDENCE.md` (Runtime fix section).
Rebuilt AAR + `prebuilt/app-netstack-arm64-debug.apk`. Future scale/RAM: NIO
upstreams instead of blocking sockets.

## 5. Gotchas & tribal knowledge (read before editing)

1. **Engine source-set swap**: `app/build.gradle.kts` adds `src/loopback/kotlin` *or* `src/netstack/kotlin` based on `-Pnetvalve.netstack=true`. Both define `dev.netvalve.di.EnginePipelineModule` — never let both into one compilation.
2. **`LazyListScope.item` is not importable** — don't re-add `import androidx.compose.foundation.lazy.item` (only `items` is a top-level extension). Bit us once.
3. **Kotlin smart-cast**: explicit nullable type annotations on `val x: T? = when{...} ?: return` patterns defeat smart-casts (the `BatteryOptimizations` fix). Prefer inference.
4. **gomobile naming**: Go `bridge` package binds to Java `dev.netvalve.bridge`; `NewTunnel` → `Bridge.newTunnel`, methods lower-cased, `(int, error)` → `int` + checked exception. `NetstackPacketPipeline.kt` must match the AAR's `classes.jar` if the Go API changes.
5. **gVisor has no stable API** — `go.mod` pins a placeholder commit; when first building the AAR run `go mod tidy` / `go get gvisor.dev/gvisor@<commit>` and expect small call-site fixes in `bridge.go` (forwarder signatures move). `build-aar.sh` runs `go build ./...` first so drift fails fast.
6. **Per-app list is establish-time-fixed** (platform): selection changes → `VpnActions.ACTION_RESTART` (seamless rebuild). Cap/rule edits do NOT need a restart (live bucket refresh via `RuleEngine.revision`).
7. **Never route around IPv6** — it is always routed into the TUN (RELAY or FAST_REJECT); "don't add the v6 route" would create a shaping bypass.
8. **`NetValveLogger` rate-limits** DEBUG/INFO in hot categories (250 ms per category+uid) — don't "fix" missing repeated lines; WARN/ERROR always pass.
9. **Room `exportSchema = false`** intentionally (no schema dir); flip it on if you start shipping migrations.
10. **Loopback engine adopts the TUN fd** (`ParcelFileDescriptor.adoptFd`) and `TrafficEngine` hands it `detachFd()` — ownership is the engine's; don't also close it in the service.
11. **Do not throttle DNS** by default (`exemptDns=true`) — resolution latency multiplies into every app's connect time.
12. The two same-named `EnginePipelineModule` files are the ONLY duplicated symbol; everything else is single-source.

## 6. Remaining work (prioritized)

1. **Build the netstack AAR** (unblocks real forwarding — the only missing piece of the production path):
   Go 1.22+, `export ANDROID_NDK_HOME=...`, `cd netstack && ./build-aar.sh`, fix any gVisor API drift, then `./gradlew :app:assembleDebug -Pnetvalve.netstack=true`. Smoke-test on device: throttle a browser to 2 Mbps, run a speed test.
2. **On-device validation** (needs emulator/device):
   `./gradlew :app:connectedDebugAndroidTest`; then the manual protocol in `docs/LIMITATIONS.md` (300+ conns, CPU/RAM targets, revoke/reboot flows, background-only rule with usage access).
3. **Warning-threshold notifications**: `AppRule.warnThresholdPercent` is persisted and surfaced in the verdict (`FlowVerdict.warnThresholdPercent`), but no notification fires yet. Implement as a `TrafficModule` observing `onBytes` + a daily/session usage store, posting a notification at the threshold. (Deliberately left as the first exercise of the plugin API.)
4. **Stage-2 niceties**: netstack retransmit counters into stats (bridge exposes `Stats()` cheaply), per-app schedule UI for multiple windows, quota module (sketch in `docs/EXTENDING.md`), domain filtering on the existing `DnsCache`.
5. **Polish**: app icon densities beyond adaptive XML, per-app detail chart, localization (all strings currently in code for detail screens — move to `strings.xml` if localizing).

## 7. Testing map

- `TokenBucketTest` — refill math, burst cap, debt pacing, live rate update (fake clock).
- `PacingQueueTest` — FIFO, DROP_NEWEST/DROP_OLDEST, oversize, byte accounting.
- `PolicyEvaluatorTest` — precedence (block>throttle, allow-override), condition gating, midnight-wrapping windows.
- `RuleCompilerTest` — AppRule→PolicyRule compilation incl. background-only and schedules; global default action.
- `UidResolverFallbackTest` — cache, -1 fallback, single-app inference, exception safety.
- `PersistenceTest` — exact unit conversions; settings/rule/sealed-hierarchy JSON round-trips.
- `FormatTest` — bytes/rate/duration formatting.
- `AppSelectionFlowTest` (androidTest) — Compose render + selection toggle flow.

Standalone-JVM re-run (no Gradle): see `docs/BUILD_VERIFICATION.md` §core; jars in `/agent/workspace/tools/libs`.

## 8. File inventory (grouped)

- **Root**: `README.md`, `HANDOFF.md` (this), `LICENSE`, `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` (low-mem tuned), `gradle/libs.versions.toml`, wrapper.
- **app/**: `build.gradle.kts` (engine flag, Hilt/KSP/Compose), `proguard-rules.pro`, `src/main/AndroidManifest.xml` (VpnService + typed FGS + boot receiver).
- **app/src/main/kotlin/dev/netvalve/**
  - `MainActivity.kt` (consent/permission launchers → `AppActions`), `NetValveApp.kt`, `StrictModeConfig.kt`
  - `data/model/` — serializable domain (enums, `BandwidthLimit`+units, `RuleCondition`, `RuleAction`, `AppRule`+`Schedule`, `GlobalSettings`, `EvaluationContext`, `InstalledApp`)
  - `data/datastore/`, `data/db/` — DataStore singleton + JSON config; Room entities/DAOs/db
  - `repository/` — interfaces + DataStore/Room/PackageManager impls
  - `rules/` — `PolicyEvaluator` (pure), `RuleCompiler`, `RuleEngine` (orchestrator), `DeviceState`(+monitor iface), `PolicyModels`
  - `throttle/` — `TokenBucket` (lazy refill), `PacingQueue`, `ThrottleManager` (live refresh, `pace()`)
  - `network/` — `PacketPipeline`(+factory) boundary, `FlowContract` (streams, `TunnelConfig`, `SocketProtector`), `FlowSupervisor` (relay + insertion points), `ConnectionManager` (protected upstreams), `UidResolver`, `DnsCache`
  - `module/` — `TrafficModule` API + `ModuleChain` + `DefaultPolicyModule`/`ConnectionLogModule`
  - `stats/` — `StatsCollector`, `ThroughputMeter`, snapshot models
  - `log/` — `Logger` API + `NetValveLogger`
  - `service/` — `NetValveVpnService`, `VpnController`, `TrafficEngine` (session assembly), `DeviceStateMonitorImpl`, `BatteryOptimizations`, `BootReceiver`, `NotificationHelper`, `TunnelState`
  - `di/` — `DataModule`, `RepositoryModule`, `EngineModule`, `ModulesModule`, `Qualifiers`
  - `ui/` — theme, navigation(+`AppActions`), components, dashboard, apps, appdetail (`RuleDraft` mapper), stats, logs
- **app/src/loopback|netstack/** — the two swappable engines (each with its own `EnginePipelineModule`).
- **app/src/test|androidTest/** — tests (§7).
- **netstack/** — `bridge.go`, `conn.go`, `go.mod`, `build-aar.sh`, `README.md`.
- **docs/** — `ARCHITECTURE.md`, `THROTTLING.md`, `LIMITATIONS.md`, `EXTENDING.md`, `BUILD_VERIFICATION.md`, `sample-rules.json` (generated by executing the real serializers).
- **prebuilt/** — `app-debug.apk` (loopback engine, built 2026-07-22).

## 9. Quick continuation playbooks

**Rebuild + tests (sandbox)** — §4a commands.

**Add a feature module** (quota/domain/etc.): implement `TrafficModule`, bind
`@Provides @IntoSet` in `di/ModulesModule.kt`, unit-test pure logic. Full guide:
`docs/EXTENDING.md`.

**Change a rule field**: model in `data/model/AppRule.kt` (+`@SerialName` stability),
compile in `rules/RuleCompiler.kt`, edit UI in `ui/appdetail/RuleDraft.kt` + screen,
round-trip test in `PersistenceTest`.

**Debug why an app isn't throttled**: Logs screen at DEBUG → look for
`RULE_MATCH`/`CONNECTION_OPEN` lines with the app's uid; check `UidResolver`
failures (Unknown bucket ⇒ attribution fell through); confirm the app is in the
controlled set (selection mode!) and the rule's conditions match current device state.
