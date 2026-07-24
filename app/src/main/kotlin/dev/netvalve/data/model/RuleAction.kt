package dev.netvalve.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What to do with a flow once its rule matches. Modeled as a sealed hierarchy so
 * new behaviours (e.g. redirect, tag, mark-metered) can be added later without
 * changing the evaluator's public contract — only [dev.netvalve.rules.PolicyEvaluator]
 * needs to learn how to fold a new action into an [EffectivePolicy].
 *
 * Stable [SerialName]s keep persisted rules refactor-proof.
 */
@Serializable
sealed interface RuleAction {

    /** Explicitly permit, with no shaping. Useful as an override / exception. */
    @Serializable
    @SerialName("allow")
    data object Allow : RuleAction

    /** Drop the flow: TCP is reset, UDP is silently dropped. */
    @Serializable
    @SerialName("block")
    data object Block : RuleAction

    /**
     * Shape the flow. `null` in either direction means "do not cap this
     * direction" (so a rule can throttle download only, for instance).
     */
    @Serializable
    @SerialName("throttle")
    data class Throttle(
        val download: BandwidthLimit? = null,
        val upload: BandwidthLimit? = null,
    ) : RuleAction
}
