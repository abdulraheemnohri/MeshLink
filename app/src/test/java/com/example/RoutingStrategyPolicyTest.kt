package com.example

import com.example.core.protocol.RoutingStrategy
import com.example.routing.RoutingStrategyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingStrategyPolicyTest {

    @Test
    fun autonomousPrefersStoreAndForwardAtLowBattery() {
        assertEquals(
            RoutingStrategy.STORE_AND_FORWARD,
            RoutingStrategyPolicy.resolveAutonomous(
                hasDirectEndpoint = true,
                batteryPercent = 20,
                activePeerCount = 5
            )
        )
    }

    @Test
    fun autonomousUsesDirectWhenDestinationIsReachableAndBatteryIsHealthy() {
        assertEquals(
            RoutingStrategy.DIRECT,
            RoutingStrategyPolicy.resolveAutonomous(
                hasDirectEndpoint = true,
                batteryPercent = 80,
                activePeerCount = 1
            )
        )
    }

    @Test
    fun autonomousUsesSprayAndWaitForDensePeerSet() {
        assertEquals(
            RoutingStrategy.SPRAY_AND_WAIT,
            RoutingStrategyPolicy.resolveAutonomous(
                hasDirectEndpoint = false,
                batteryPercent = 80,
                activePeerCount = 3
            )
        )
    }

    @Test
    fun autonomousUsesEpidemicForSmallAvailableRelaySet() {
        assertEquals(
            RoutingStrategy.EPIDEMIC,
            RoutingStrategyPolicy.resolveAutonomous(
                hasDirectEndpoint = false,
                batteryPercent = 80,
                activePeerCount = 2
            )
        )
    }

    @Test
    fun autonomousFallsBackToStoreAndForwardWhenNoPeersExist() {
        assertEquals(
            RoutingStrategy.STORE_AND_FORWARD,
            RoutingStrategyPolicy.resolveAutonomous(
                hasDirectEndpoint = false,
                batteryPercent = 80,
                activePeerCount = 0
            )
        )
    }

    @Test
    fun directIsTheOnlyModeThatDisablesRelayAndOfflineQueue() {
        RoutingStrategy.entries.forEach { strategy ->
            assertEquals(
                strategy != RoutingStrategy.DIRECT,
                RoutingStrategyPolicy.allowsRelay(strategy)
            )
            assertEquals(
                strategy != RoutingStrategy.DIRECT,
                RoutingStrategyPolicy.allowsOfflineQueue(strategy)
            )
        }
    }

    @Test
    fun fanoutModesAreExplicitAndDoNotOverlap() {
        assertTrue(RoutingStrategyPolicy.usesEpidemicFanout(RoutingStrategy.EPIDEMIC))
        assertFalse(RoutingStrategyPolicy.usesEpidemicFanout(RoutingStrategy.SPRAY_AND_WAIT))
        assertTrue(RoutingStrategyPolicy.usesSprayBudget(RoutingStrategy.SPRAY_AND_WAIT))
        assertFalse(RoutingStrategyPolicy.usesSprayBudget(RoutingStrategy.EPIDEMIC))
    }

    @Test
    fun allFiveModesRemainAvailable() {
        assertEquals(
            setOf("DIRECT", "STORE_AND_FORWARD", "EPIDEMIC", "SPRAY_AND_WAIT", "AUTONOMOUS"),
            RoutingStrategy.entries.map { it.name }.toSet()
        )
    }
}
