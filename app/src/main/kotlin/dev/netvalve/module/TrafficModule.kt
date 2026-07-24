package dev.netvalve.module

import dev.netvalve.data.model.Direction
import dev.netvalve.network.FlowContext

/** Inputs handed to a module when a new flow opens. */
data class FlowOpenInput(
    val ctx: FlowContext,
    val uid: Int,
)

/**
 * Mutable verdict a module chain folds into. Modules *tighten* it: caps take the
 * minimum, and any module may veto with [block]. This makes composition
 * order-independent for the common case and easy to reason about.
 */
class FlowVerdictBuilder {
    var blocked: Boolean = false
    var downloadBytesPerSec: Long? = null
    var uploadBytesPerSec: Long? = null
    var warnThresholdPercent: Int? = null
    val tags: MutableMap<String, String> = HashMap()

    fun block() { blocked = true }

    fun restrictDownload(bps: Long?) {
        if (bps == null) return
        downloadBytesPerSec = downloadBytesPerSec?.let { minOf(it, bps) } ?: bps
    }

    fun restrictUpload(bps: Long?) {
        if (bps == null) return
        uploadBytesPerSec = uploadBytesPerSec?.let { minOf(it, bps) } ?: bps
    }

    fun build() = FlowVerdict(blocked, downloadBytesPerSec, uploadBytesPerSec, warnThresholdPercent, tags.toMap())
}

/** Immutable outcome of running the module chain for a flow. */
data class FlowVerdict(
    val blocked: Boolean,
    val downloadBytesPerSec: Long?,
    val uploadBytesPerSec: Long?,
    val warnThresholdPercent: Int?,
    val tags: Map<String, String>,
) {
    val isThrottled: Boolean get() = downloadBytesPerSec != null || uploadBytesPerSec != null
}

/**
 * A pluggable unit of traffic policy/observation. This is the extension point
 * that lets NetValve grow — per-app quotas, domain filtering, parental control,
 * firewall rules, adaptive throttling — **without modifying the VPN engine or
 * the relay**. New behaviour = new `TrafficModule` bound into the Hilt multiset.
 *
 * Hooks:
 *  - [onFlowOpen]  contribute to the verdict (block / cap / tag). Must be fast.
 *  - [onBytes]     observe throughput per chunk (accounting, quotas). Hot path —
 *                  keep O(1) and allocation-free.
 *  - [onFlowClose] cleanup / final accounting.
 *
 * Lower [priority] runs first. All hooks have safe no-op defaults so a module
 * only implements what it needs.
 */
interface TrafficModule {
    val name: String
    val priority: Int get() = 0

    fun onFlowOpen(input: FlowOpenInput, verdict: FlowVerdictBuilder) {}
    fun onBytes(ctx: FlowContext, uid: Int, direction: Direction, bytes: Int) {}
    fun onFlowClose(ctx: FlowContext, uid: Int) {}
}

/**
 * Runs the registered modules in priority order. Injected as a set via Hilt
 * multibindings; the set can be empty (then flows are unrestricted) or contain
 * any number of modules.
 */
class ModuleChain(modules: Set<TrafficModule>) {
    private val ordered = modules.sortedBy { it.priority }

    fun evaluate(input: FlowOpenInput): FlowVerdict {
        val builder = FlowVerdictBuilder()
        for (m in ordered) {
            runCatching { m.onFlowOpen(input, builder) }
        }
        return builder.build()
    }

    fun onBytes(ctx: FlowContext, uid: Int, direction: Direction, bytes: Int) {
        for (m in ordered) runCatching { m.onBytes(ctx, uid, direction, bytes) }
    }

    fun onFlowClose(ctx: FlowContext, uid: Int) {
        for (m in ordered) runCatching { m.onFlowClose(ctx, uid) }
    }
}
