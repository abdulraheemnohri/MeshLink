package com.example.transport

import com.example.core.protocol.TransportType
import kotlinx.coroutines.CancellationException
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

data class TransportHealthSnapshot(
    val transportType: TransportType,
    val running: Boolean,
    val lastStartedAt: Long? = null,
    val lastDiscoveryAt: Long? = null,
    val lastError: String? = null
)

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
    private val health = ConcurrentHashMap<TransportType, TransportHealthSnapshot>()
    private val _transportHealth = MutableStateFlow<List<TransportHealthSnapshot>>(emptyList())
    val transportHealth: StateFlow<List<TransportHealthSnapshot>> = _transportHealth.asStateFlow()

    private val _peers = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    val peers: StateFlow<List<DiscoveredEndpoint>> = _peers.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    init {
        require(staleAfterMs > 0L) { "Stale timeout must be positive" }
        adapters.forEach { adapter ->
            health[adapter.transportType] = TransportHealthSnapshot(adapter.transportType, adapter.isRunning)
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
        publishHealth()
    }

    suspend fun startAll(): Map<TransportType, Result<Unit>> = start(adapters.map { it.transportType }.toSet())

    /** Starts only explicitly enabled adapters; disabled radios are never started implicitly. */
    suspend fun start(types: Set<TransportType>): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.transportType in types }.forEach { adapter ->
            val result = captureAdapterResult { adapter.start() }
            results[adapter.transportType] = result
            val previous = health[adapter.transportType] ?: TransportHealthSnapshot(adapter.transportType, false)
            health[adapter.transportType] = previous.copy(
                running = adapter.isRunning,
                lastStartedAt = if (result.isSuccess) System.currentTimeMillis() else previous.lastStartedAt,
                lastError = result.exceptionOrNull()?.message
            )
        }
        publishHealth()
        return results
    }

    /** Connects through the adapter that owns this endpoint's transport. */
    suspend fun connect(endpoint: DiscoveredEndpoint): Result<Boolean> {
        val adapter = adapters.firstOrNull { it.transportType == endpoint.transportType }
            ?: return Result.failure(IllegalArgumentException("No adapter for " + endpoint.transportType))
        return captureAdapterResult { adapter.connect(endpoint) }
    }

    suspend fun discoverAll(): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.isRunning }.forEach { adapter ->
            val result = captureAdapterResult { adapter.triggerDiscovery() }
            results[adapter.transportType] = result
            val previous = health[adapter.transportType] ?: TransportHealthSnapshot(adapter.transportType, adapter.isRunning)
            health[adapter.transportType] = previous.copy(
                running = adapter.isRunning,
                lastDiscoveryAt = if (result.isSuccess) System.currentTimeMillis() else previous.lastDiscoveryAt,
                lastError = result.exceptionOrNull()?.message
            )
        }
        publishHealth()
        pruneExpired()
        return results
    }

    suspend fun stopAll(): Map<TransportType, Result<Unit>> = stop(adapters.map { it.transportType }.toSet())

    suspend fun stop(types: Set<TransportType>): Map<TransportType, Result<Unit>> {
        val results = linkedMapOf<TransportType, Result<Unit>>()
        adapters.filter { it.transportType in types }.forEach { adapter ->
            val result = captureAdapterResult { adapter.stop() }
            results[adapter.transportType] = result
            val previous = health[adapter.transportType] ?: TransportHealthSnapshot(adapter.transportType, adapter.isRunning)
            health[adapter.transportType] = previous.copy(
                running = adapter.isRunning,
                lastError = result.exceptionOrNull()?.message
            )
        }
        publishHealth()
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

        // A transport may first report a hardware address (for example a bonded
        // Bluetooth MAC) and later learn the authenticated MeshLink device ID.
        // Replace the provisional key for the same adapter/address to avoid
        // showing one physical peer twice in the discovery UI.
        endpoints.entries.forEach { entry ->
            if (entry.key != endpoint.deviceId) {
                entry.value.entries.removeIf { (type, current) ->
                    type == endpoint.transportType && current.address.equals(endpoint.address, ignoreCase = true)
                }
                if (entry.value.isEmpty()) endpoints.remove(entry.key, entry.value)
            }
        }
        val peerPaths = endpoints.getOrPut(endpoint.deviceId) { ConcurrentHashMap() }
        val current = peerPaths[endpoint.transportType]
        if (current == null || endpoint.lastSeenAt >= current.lastSeenAt) {
            peerPaths[endpoint.transportType] = endpoint
        }
        publish()
    }

    /**
     * Isolate ordinary adapter failures without swallowing structured cancellation.
     * Service shutdown and caller cancellation must stop this coordinator operation.
     */
    private suspend fun <T> captureAdapterResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private fun publishHealth() {
        _transportHealth.value = adapters.map { adapter ->
            val snapshot = health[adapter.transportType]
                ?: TransportHealthSnapshot(adapter.transportType, adapter.isRunning)
            snapshot.copy(running = adapter.isRunning)
        }.sortedBy { it.transportType.name }
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
        publishHealth()
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 90_000L
    }
}
