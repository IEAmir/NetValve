# NetValve architecture

## Layers (module map)

```
ui/          Compose screens + ViewModels (MVVM, StateFlow)
service/     VpnService, VpnController, TrafficEngine, DeviceStateMonitor, boot, notification, battery/OEM
network/     PacketPipeline boundary, FlowSupervisor, ConnectionManager, UidResolver, DnsCache, contracts
throttle/    TokenBucket, PacingQueue, ThrottleManager
rules/       PolicyEvaluator, RuleCompiler, RuleEngine, PolicyModels, DeviceState
module/      TrafficModule plugin API + ModuleChain + built-in modules
stats/       StatsCollector, ThroughputMeter, snapshot models
log/         Logger (levels, ring buffer, rate-limit, export)
data/        model/ (serializable domain), datastore/, db/ (Room)
repository/  interfaces + DataStore/Room/PackageManager implementations
di/          Hilt modules (Data, Repository, Engine, Modules, engine pipeline)
utils/       formatting helpers
```

## Design principles

1. **Product logic is framework-free.** Everything in `rules/`, `throttle/`,
   `stats/`, `log/`, `module/`, `network/` (contracts + supervisor + resolver) and
   `data/model/` avoids Android types, so it is unit-testable on a plain JVM and
   the packet path is portable.

2. **The packet engine sits behind one interface.** `PacketPipeline` is the only
   seam that touches protocol machinery. Two implementations are chosen at build
   time (Gradle `-Pnetvalve.netstack`):
   - `netstack` — a thin Kotlin adapter over a **gVisor netstack** AAR
     (production). No TCP/IP is implemented in Kotlin.
   - `loopback` — a pure-Kotlin development/CI stub (accounts + attributes, does
     not forward).

3. **Rules are a generic policy engine, not a bandwidth-only feature.** A rule is
   *conditions → action*. Conditions (`RuleCondition`) cover network type, roaming,
   charging, battery level, screen, foreground, time window, day-of-week. Actions
   (`RuleAction`) are `Allow`/`Block`/`Throttle`. The friendly per-app `AppRule` is
   *compiled* into generic `PolicyRule`s (`RuleCompiler.kt`), keeping the UI simple
   and the engine open to new condition/action types without redesign.

4. **Extensibility via a module pipeline.** `TrafficModule`s (Hilt multiset) get
   `onFlowOpen`/`onBytes`/`onFlowClose` hooks. Block and throttle ship as the
   `DefaultPolicyModule`; adding quotas/domain-filtering/etc. is a new module, not
   an engine change.

## Data flow of one connection

1. A controlled app opens a socket. The OS routes it into the TUN (allow/deny list
   set at `establish()`).
2. The engine (`PacketPipeline`) terminates the flow and calls
   `FlowHandler.onTcpFlow`/`onUdpFlow` with the original 4-tuple + a byte stream.
3. `FlowSupervisor` resolves the **UID** (`UidResolver`, with fallback), asks the
   **ModuleChain** for a **verdict** (block / caps / tags).
4. If blocked → TCP RST / UDP drop, logged, counted.
5. Else → `ConnectionManager` dials a **protected** upstream socket; two relay
   coroutines copy bytes each way through the **token buckets**
   (`ThrottleManager`), updating `StatsCollector` and notifying modules.
6. `RuleEngine` recomputes effective policy on rule/device-state changes and bumps
   a `revision` so live buckets refresh without reconnecting.

## State, persistence, lifecycle

- **Settings & rules** → DataStore (JSON via kotlinx.serialization).
- **Selection** → DataStore (string set).
- **Stats & logs** → Room; per-app byte totals are checkpointed periodically and
  reloaded on start so they survive process death.
- **Tunnel state** is persisted (`GlobalSettings.enabled`) so a sticky foreground
  service and the boot receiver can restore it.
- **DI**: everything is wired with Hilt singletons; an application-scoped
  `CoroutineScope` backs `RuleEngine`/`ThrottleManager`/`StatsCollector`/`Logger`;
  each tunnel session gets its own IO `SupervisorJob` scope in `TrafficEngine`.

## Threading
- No blocking work on the main thread (StrictMode enforces this in debug).
- Relay uses `Dispatchers.IO`; blocking socket reads live there.
- Throttling suspends (never busy-waits).
- UI reads immutable `StateFlow` snapshots; stats are sampled ~1 Hz.
