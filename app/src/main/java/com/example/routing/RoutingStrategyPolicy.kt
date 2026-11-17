package com.example.routing

import com.example.core.protocol.RoutingStrategy

/**
 * Pure policy for selecting and enforcing MeshLink's five delivery modes.
 * Radio selection is intentionally separate and remains TransportPolicyManager's job.
 */
object RoutingStrategyPolicy {

    fun resolveAutonomous(
        hasDirectEndpoint: Boolean,
        batteryPercent: Int,
        activePeerCount: Int
    ): RoutingStrategy = when {
        batteryPercent in 0..20 -> RoutingStrategy.STORE_AND_FORWARD
        hasDirectEndpoint -> RoutingStrategy.DIRECT
        activePeerCount >= 3 -> RoutingStrategy.SPRAY_AND_WAIT
        activePeerCount > 0 -> RoutingStrategy.EPIDEMIC
        else -> RoutingStrategy.STORE_AND_FORWARD
    }

    fun allowsRelay(strategy: RoutingStrategy): Boolean =
        strategy != RoutingStrategy.DIRECT

    fun allowsOfflineQueue(strategy: RoutingStrategy): Boolean =
        strategy != RoutingStrategy.DIRECT

    fun usesEpidemicFanout(strategy: RoutingStrategy): Boolean =
        strategy == RoutingStrategy.EPIDEMIC

    fun usesSprayBudget(strategy: RoutingStrategy): Boolean =
        strategy == RoutingStrategy.SPRAY_AND_WAIT
}
