package com.example

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.TransportType
import com.example.transport.DiscoveredEndpoint
import com.example.transport.MeshTransport
import com.example.transport.TransportEvent
import com.example.transport.UniversalDiscoveryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalDiscoveryCoordinatorTest {
    private class FakeTransport(
        override val transportType: TransportType,
        var failStart: Boolean = false
    ) : MeshTransport {
        private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
        override val events = mutableEvents
        override var isRunning: Boolean = false
            private set

        suspend fun announce(endpoint: DiscoveredEndpoint) {
            mutableEvents.emit(TransportEvent.PeerFound(endpoint))
        }

        override suspend fun start() {
            if (failStart) error("radio unavailable")
            isRunning = true
        }

        override suspend fun stop() {
            isRunning = false
        }

        override suspend fun triggerDiscovery() {
            check(isRunning) { "transport is stopped" }
        }

        override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean = false
    }

    private fun endpoint(type: TransportType, address: String, seen: Long) = DiscoveredEndpoint(
        deviceId = "peer-1",
        alias = "Peer",
        signingPublicKey = "signing-key",
        agreementPublicKey = "agreement-key",
        transportType = type,
        address = address,
        port = 0,
        lastSeenAt = seen
    )

    @Test
    fun retainsSeparatePathsForSamePeerAndKeepsNewestPathPerTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lan = FakeTransport(TransportType.LOCAL_LAN)
        val ble = FakeTransport(TransportType.BLE)
        val coordinator = UniversalDiscoveryCoordinator(listOf(lan, ble), scope)

        lan.announce(endpoint(TransportType.LOCAL_LAN, "192.168.1.2", 100L))
        lan.announce(endpoint(TransportType.LOCAL_LAN, "192.168.1.3", 200L))
        ble.announce(endpoint(TransportType.BLE, "AA:BB:CC:DD", 150L))
        yield()

        val paths = coordinator.endpointsFor("peer-1")
        assertEquals(2, paths.size)
        assertEquals("192.168.1.3", paths.first { it.transportType == TransportType.LOCAL_LAN }.address)
        assertEquals("AA:BB:CC:DD", paths.first { it.transportType == TransportType.BLE }.address)
        assertEquals(2, coordinator.peers.value.size)

        coordinator.close()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun prunesOnlyExpiredPathsAndRemovesPeerWhenNoPathsRemain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lan = FakeTransport(TransportType.LOCAL_LAN)
        val coordinator = UniversalDiscoveryCoordinator(listOf(lan), scope, staleAfterMs = 100L)

        lan.announce(endpoint(TransportType.LOCAL_LAN, "192.168.1.2", 950L))
        yield()
        coordinator.pruneExpired(now = 1_000L)
        assertTrue(coordinator.endpointsFor("peer-1").isNotEmpty())

        coordinator.pruneExpired(now = 1_051L)
        assertTrue(coordinator.endpointsFor("peer-1").isEmpty())
        assertTrue(coordinator.peers.value.isEmpty())
        coordinator.close()
        scope.coroutineContext[SupervisorJob]?.cancel()
    }

    @Test
    fun explicitSelectionNeverStartsDisabledAdapter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lan = FakeTransport(TransportType.LOCAL_LAN)
        val ble = FakeTransport(TransportType.BLE)
        val coordinator = UniversalDiscoveryCoordinator(listOf(lan, ble), scope)

        val results = coordinator.start(setOf(TransportType.LOCAL_LAN))
        assertTrue(results.getValue(TransportType.LOCAL_LAN).isSuccess)
        assertFalse(results.containsKey(TransportType.BLE))
        assertTrue(lan.isRunning)
        assertFalse(ble.isRunning)

        coordinator.stop(setOf(TransportType.LOCAL_LAN))
        assertFalse(lan.isRunning)
        assertFalse(ble.isRunning)
        coordinator.close()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun adapterFailuresDoNotPreventOtherRadiosStarting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failed = FakeTransport(TransportType.BLE, failStart = true)
        val working = FakeTransport(TransportType.LOCAL_LAN)
        val coordinator = UniversalDiscoveryCoordinator(listOf(failed, working), scope)

        val results = coordinator.startAll()
        assertFalse(results.getValue(TransportType.BLE).isSuccess)
        assertTrue(results.getValue(TransportType.LOCAL_LAN).isSuccess)
        assertTrue(working.isRunning)

        val scanResults = coordinator.discoverAll()
        assertTrue(scanResults.getValue(TransportType.LOCAL_LAN).isSuccess)
        assertFalse(scanResults.containsKey(TransportType.BLE))
        coordinator.stopAll()
        coordinator.close()
        scope.coroutineContext[SupervisorJob]?.cancel()
    }
}
