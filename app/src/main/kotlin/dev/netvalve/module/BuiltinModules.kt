package dev.netvalve.module

import dev.netvalve.data.model.Direction
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.network.FlowContext
import dev.netvalve.rules.RuleEngine

/**
 * The built-in module that turns resolved rules into a verdict. This is the ONLY
 * place the rule engine feeds the flow path — proving that "block" and "throttle"
 * are themselves modules, not special-cased in the relay. Runs first (priority 0)
 * so later modules can further tighten its decision.
 */
class DefaultPolicyModule(
    private val ruleEngine: RuleEngine,
) : TrafficModule {
    override val name: String = "default-policy"
    override val priority: Int = 0

    override fun onFlowOpen(input: FlowOpenInput, verdict: FlowVerdictBuilder) {
        val policy = ruleEngine.policyForUid(input.uid)
        if (policy.blocked) verdict.block()
        verdict.restrictDownload(policy.downloadBytesPerSec)
        verdict.restrictUpload(policy.uploadBytesPerSec)
        verdict.warnThresholdPercent = policy.warnThresholdPercent
        policy.matchedRuleId?.let { verdict.tags["rule"] = it }
    }
}

/**
 * An observer module (contributes no policy) that records connection lifecycle
 * to the log. Demonstrates the [TrafficModule.onFlowOpen]/[onFlowClose] hooks for
 * pure side-effect modules. Runs last (high priority value) so it can see tags
 * added by earlier modules.
 */
class ConnectionLogModule(
    private val logger: Logger,
) : TrafficModule {
    override val name: String = "connection-log"
    override val priority: Int = 1_000

    override fun onFlowOpen(input: FlowOpenInput, verdict: FlowVerdictBuilder) {
        val rule = verdict.tags["rule"]
        if (rule != null) {
            logger.d(LogCategory.RULE_MATCH, "matched $rule for ${input.ctx.shortKey()}", uid = input.uid)
        }
        logger.d(
            LogCategory.CONNECTION_OPEN,
            "${input.ctx.protocol} ${input.ctx.destinationAddress.hostAddress}:${input.ctx.destinationPort}" +
                if (verdict.blocked) " BLOCKED" else "",
            uid = input.uid,
        )
    }

    override fun onFlowClose(ctx: FlowContext, uid: Int) {
        logger.d(LogCategory.CONNECTION_CLOSE, ctx.shortKey(), uid = uid)
    }

    override fun onBytes(ctx: FlowContext, uid: Int, direction: Direction, bytes: Int) { /* no-op */ }
}
