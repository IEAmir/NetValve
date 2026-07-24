# Extending NetValve

NetValve is built so new traffic behaviour is added **without touching the VPN
engine or the relay**. There are two extension surfaces.

## 1. Add a new policy *condition* or *action* (generic engine)

Conditions and actions are sealed, serializable hierarchies.

**New condition** (e.g. "metered network only"):
1. Add a subclass in `data/model/RuleCondition.kt`:
   ```kotlin
   @Serializable
   data class RequireMetered(val metered: Boolean) : RuleCondition {
       override fun matches(ctx: EvaluationContext) = ctx.metered == metered
       override fun describe() = if (metered) "on metered" else "on unmetered"
   }
   ```
2. Add the backing field to `EvaluationContext` and populate it in
   `DeviceState.toEvaluationContext` (+ `DeviceStateMonitorImpl`).
3. Done — `PolicyEvaluator` already evaluates any condition; no evaluator change.

**New action** (e.g. `MarkMetered`): add it to `RuleAction`, then teach
`PolicyEvaluator.fold` and `FlowVerdict` how to carry it. This is the only place
the engine learns a new *kind* of outcome.

## 2. Add a new *module* (recommended for features)

Implement `TrafficModule` and bind it into the Hilt multiset. The `ModuleChain`
runs every module in priority order for each flow.

```kotlin
class QuotaModule @Inject constructor(
    private val quotas: QuotaStore,       // your own persistence
) : TrafficModule {
    override val name = "daily-quota"
    override val priority = 10            // after DefaultPolicyModule (0)

    override fun onFlowOpen(input: FlowOpenInput, verdict: FlowVerdictBuilder) {
        if (quotas.isExceeded(input.uid)) verdict.block()   // veto
    }

    override fun onBytes(ctx: FlowContext, uid: Int, direction: Direction, bytes: Int) {
        quotas.add(uid, bytes)             // hot path: keep O(1)
    }
}
```

Register it:
```kotlin
// di/ModulesModule.kt
@Provides @IntoSet
fun provideQuotaModule(store: QuotaStore): TrafficModule = QuotaModule(store)
```

That is the entire integration — no changes to `FlowSupervisor`, `PacketPipeline`,
or `ThrottleManager`.

### Hooks
| Hook | When | Notes |
|---|---|---|
| `onFlowOpen(input, verdict)` | new flow, after UID attribution | contribute block/caps/tags; runs by ascending `priority`. |
| `onBytes(ctx, uid, dir, bytes)` | each relayed chunk | **hot path** — O(1), allocation-free. |
| `onFlowClose(ctx, uid)` | flow ends | final accounting/cleanup. |

`FlowVerdictBuilder` folds contributions: caps take the **minimum** across modules,
`block()` is a veto — so composition is order-independent for the common case.

## Ready-to-build directions

- **Per-app quotas** — `onBytes` accounting + `onFlowOpen` veto (sketch above).
- **Domain filtering / firewall** — a `DnsCache` (IP→hostname) is already populated
  from observed DNS. A `DomainFilterModule` can consult it in `onFlowOpen` and
  block by hostname pattern.
- **Parental controls** — schedule + domain modules combined, gated by a PIN
  screen.
- **Adaptive throttling** — a module that watches `StatsCollector` throughput/RTT
  and calls `ThrottleManager` to adjust caps dynamically (the buckets already
  support live `updateRate`).
- **Alternative engines** — implement `PacketPipeline` (+ `PacketPipelineFactory`)
  in a new source set to swap in a different stack; nothing else changes.

## Testing your extension
Keep new logic framework-free where possible and unit-test it like the core (see
`app/src/test`). Modules that only use `TrafficModule` hooks + your own stores are
pure JVM and need no emulator.
