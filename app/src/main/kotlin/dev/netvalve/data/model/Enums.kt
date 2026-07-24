package dev.netvalve.data.model

import kotlinx.serialization.Serializable

/**
 * Direction of a byte stream, from the controlled app's point of view.
 *
 * [UPLOAD]   app -> internet (egress). Bytes read from the TUN, written upstream.
 * [DOWNLOAD] internet -> app (ingress). Bytes read upstream, written to the TUN.
 */
enum class Direction { UPLOAD, DOWNLOAD }

/** L4 protocol of an intercepted flow. */
enum class TransportProtocol { TCP, UDP, ICMP, OTHER }

/**
 * The active data transport, as reported by connectivity. Used both for live
 * stats and for the [RuleCondition.OnNetworkType] policy condition.
 */
enum class NetworkType { WIFI, MOBILE, ETHERNET, VPN, OTHER, NONE }

/**
 * When a per-app throttle should apply.
 *
 * [ALWAYS]          throttle whenever the app transfers data.
 * [BACKGROUND_ONLY] only throttle while the app is NOT in the foreground. This
 *                   compiles down to a [RuleCondition.RequireForeground] `false`
 *                   condition, so it is just sugar over the generic engine.
 */
enum class ThrottleMode { ALWAYS, BACKGROUND_ONLY }

/**
 * Which apps the tunnel governs.
 *
 * [ONLY_SELECTED]   only the chosen apps are routed into the VPN
 *                   (VpnService.Builder.addAllowedApplication).
 * [ALL_EXCEPT]      every app except the chosen ones is routed
 *                   (VpnService.Builder.addDisallowedApplication). NetValve
 *                   always disallows its own package in this mode to avoid loops.
 */
enum class SelectionMode { ONLY_SELECTED, ALL_EXCEPT }

/** How IPv6 traffic is treated inside the tunnel. See docs/LIMITATIONS.md. */
@Serializable
enum class Ipv6Mode {
    /** Relay IPv6 end-to-end (throttled + attributed like IPv4). Default. */
    RELAY,

    /**
     * Do not route IPv6 into the tunnel and immediately fail v6 connections so
     * apps fall back to IPv4 within milliseconds (no long timeouts).
     */
    FAST_REJECT,
}
