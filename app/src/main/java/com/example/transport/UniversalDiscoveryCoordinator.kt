package com.example.transport

import com.example.core.protocol.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates discovery across registered adapters without assuming that every
 * TransportType has an implementation. Peer identity is the deduplication key;
 * each peer retains a separate endpoint for every transport.
 */
class UniversalDiscoveryCoordinator(
    private val transports: List<MeshTransport>,
    private val scope: CoroutineScope,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
) {
    private val adapters = transports.distinctBy { it.transportType }
    private val endpoints = ConcurrentHashMap<String, ConcurrentHashMap<TransportType, DiscoveredEndpoint>>()
    private val collectors = mutableListOf<Job>()

    private val _peers = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    val peers: StateFlow<List<DiscoveredEndpoint>> = _peers.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    init {
        require(staleAfterMs > 0L) { "Stale timeout must be positive" }
        adapters.forEach { adapter ->
            collectors += scope.launch {
                adapter.events.collect { event ->
                    when (event) {
                        is TransportEvent.PeerFound -> record(event.endpoint)
                        is TransportEvent.ConnectionChanged -> if (!event.connected) {
                            // Keep the discovery record until it naturally expires; a
                            // peer can still be reachable over another transport.
                        }
                        else -> Unit
                    }
                    _events.emit(event)
                }
            }
        }
    }

    suspend fun startAll(): Map<TransportType, Result<Unit>> = start(adapters.map { it.transportType }.toSet())

    /** Starts only explicitly enabled adapters; disabled radios are never started implicitly. */
    suspend fun start(types: Set<TransportType>): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.transportType in types }.forEach { adapter ->
            results[adapter.transportType] = runCatching { adapter.start() }
        }
        return results
    }

    /** Connects through the adapter that owns this endpoint's transport. */
    suspend fun connect(endpoint: DiscoveredEndpoint): Result<Boolean> {
        val adapter = adapters.firstOrNull { it.transportType == endpoint.transportType }
            ?: return Result.failure(IllegalArgumentException("No adapter for " + endpoint.transportType))
        return runCatching { adapter.connect(endpoint) }
    }

    suspend fun discoverAll(): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.isRunning }.forEach { adapter ->
            results[adapter.transportType] = runCatching { adapter.triggerDiscovery() }
        }
        pruneExpired()
        return results
    }

    suspend fun stopAll(): Map<TransportType, Result<Unit>> = stop(adapters.map { it.transportType }.toSet())

    suspend fun stop(types: Set<TransportType>): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.transportType in types }.forEach { adapter ->
            results[adapter.transportType] = runCatching { adapter.stop() }
        }
        return results
    }

    fun endpointsFor(peerId: String): List<DiscoveredEndpoint> =
        endpoints[peerId]?.values?.sortedBy { it.transportType.name }.orEmpty()

    @Synchronized
    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        endpoints.entries.forEach { peer ->
            peer.value.entries.forEach { endpoint ->
                if (endpoint.value.lastSeenAt < now - staleAfterMs) {
                    peer.value.remove(endpoint.key, endpoint.value)
                }
            }
            if (peer.value.isEmpty()) endpoints.remove(peer.key, peer.value)
        }
        publish()
    }

    @Synchronized
    private fun record(endpoint: DiscoveredEndpoint) {
        if (endpoint.deviceId.isBlank() || endpoint.address.isBlank()) return
        val peerPaths = endpoints.getOrPut(endpoint.deviceId) { ConcurrentHashMap() }
        val current = peerPaths[endpoint.transportType]
        if (current == null || endpoint.lastSeenAt >= current.lastSeenAt) {
            peerPaths[endpoint.transportType] = endpoint
        }
        publish()
    }

    private fun publish() {
        _peers.value = endpoints.values.flatMap { it.values }
            .sortedWith(compareBy<DiscoveredEndpoint> { it.deviceId }.thenBy { it.transportType.name })
    }

    fun close() {
        collectors.forEach { it.cancel() }
        collectors.clear()
        endpoints.clear()
        publish()
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 90_000L
    }
}
