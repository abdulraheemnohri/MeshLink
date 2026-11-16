package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.protocol.TransportType
import com.example.transport.AvailableTransport
import com.example.transport.DeliveryPlan
import com.example.transport.TransportPolicy
import com.example.transport.TransportPolicyManager
import com.example.transport.TransportSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransportPolicyManagerTest {
    private val manager by lazy {
        TransportPolicyManager(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun sameDeviceUsesLocalPlanWithoutRadio() {
        val plan = manager.plan("node_a", "node_a", emptyList())
        assertTrue(plan is DeliveryPlan.LocalDevice)
    }

    @Test
    fun automaticPrefersConfiguredLanAndListsFallbacks() {
        val policy = TransportPolicy(
            mode = TransportSelectionMode.AUTOMATIC,
            preferred = listOf(TransportType.LOCAL_LAN, TransportType.BLE),
            allowFallback = true
        )
        val plan = manager.plan(
            "node_a",
            "node_b",
            listOf(
                AvailableTransport(TransportType.BLE, true, true, 100),
                AvailableTransport(TransportType.LOCAL_LAN, true, true, 100)
            ),
            policy
        ) as DeliveryPlan.Network

        assertEquals(TransportType.LOCAL_LAN, plan.primary)
        assertEquals(listOf(TransportType.BLE), plan.fallback)
    }

    @Test
    fun strictOnlyModeNeverReturnsFallback() {
        val policy = TransportPolicy(
            mode = TransportSelectionMode.ONLY,
            preferred = listOf(TransportType.BLE),
            allowFallback = true
        )
        val plan = manager.plan(
            "node_a",
            "node_b",
            listOf(
                AvailableTransport(TransportType.LOCAL_LAN, true, true, 100),
                AvailableTransport(TransportType.BLE, true, true, 100)
            ),
            policy
        ) as DeliveryPlan.Network

        assertEquals(TransportType.BLE, plan.primary)
        assertTrue(plan.fallback.isEmpty())
    }

    @Test
    fun unavailableOrOversizedTransportsAreRejected() {
        val plan = manager.plan(
            "node_a",
            "node_b",
            listOf(
                AvailableTransport(TransportType.BLE, true, true, 900),
                AvailableTransport(TransportType.LOCAL_LAN, false, true, 100)
            ),
            TransportPolicy()
        )
        assertTrue(plan is DeliveryPlan.Unavailable)
    }

    @Test
    fun disabledTransportsAreNeverSelected() {
        val policy = TransportPolicy(disabled = setOf(TransportType.LOCAL_LAN))
        val plan = manager.plan(
            "node_a",
            "node_b",
            listOf(AvailableTransport(TransportType.LOCAL_LAN, true, true, 10)),
            policy
        )
        assertTrue(plan is DeliveryPlan.Unavailable)
    }
}
