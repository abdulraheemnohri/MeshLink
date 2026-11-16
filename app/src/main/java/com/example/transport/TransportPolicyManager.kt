package com.example.transport

import android.content.Context
import com.example.core.protocol.TransportType

/**
 * User-controlled transport policy. It selects only registered, running adapters
 * with a discovered endpoint; it never treats an enum value as an implemented adapter.
 */
enum class TransportSelectionMode { AUTOMATIC, PREFER, ONLY }

data class TransportPolicy(
    val mode: TransportSelectionMode = TransportSelectionMode.AUTOMATIC,
    val preferred: List<TransportType> = listOf(
        TransportType.LOCAL_LAN,
        TransportType.WIFI_DIRECT,
        TransportType.WIFI_AWARE,
        TransportType.BLE
    ),
    val disabled: Set<TransportType> = emptySet(),
    val allowFallback: Boolean = true
)

data class AvailableTransport(
    val type: TransportType,
    val running: Boolean,
    val discovered: Boolean,
    val payloadBytes: Int,
    val signalStrengthDbm: Int? = null,
    val recentFailures: Int = 0
)

sealed interface DeliveryPlan {
    data object LocalDevice : DeliveryPlan
    data class Network(val primary: TransportType, val fallback: List<TransportType>) : DeliveryPlan
    data class Unavailable(val reason: String) : DeliveryPlan
}

class TransportPolicyManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TransportPolicy {
        val mode = runCatching {
            TransportSelectionMode.valueOf(prefs.getString(KEY_MODE, null) ?: TransportSelectionMode.AUTOMATIC.name)
        }.getOrDefault(TransportSelectionMode.AUTOMATIC)
        val preferred = decodeTypes(prefs.getString(KEY_PREFERRED, null))
            .ifEmpty { TransportPolicy().preferred }
        val disabled = decodeTypes(prefs.getString(KEY_DISABLED, null)).toSet()
        return TransportPolicy(mode, preferred, disabled, prefs.getBoolean(KEY_FALLBACK, true))
    }

    fun save(policy: TransportPolicy) {
        prefs.edit()
            .putString(KEY_MODE, policy.mode.name)
            .putString(KEY_PREFERRED, policy.preferred.distinct().joinToString(",") { it.name })
            .putString(KEY_DISABLED, policy.disabled.joinToString(",") { it.name })
            .putBoolean(KEY_FALLBACK, policy.allowFallback)
            .apply()
    }

    fun plan(
        localDeviceId: String,
        destinationDeviceId: String,
        candidates: Collection<AvailableTransport>,
        policy: TransportPolicy = load()
    ): DeliveryPlan {
        if (localDeviceId.isNotBlank() && localDeviceId == destinationDeviceId) {
            return DeliveryPlan.LocalDevice
        }

        val usable = candidates.asSequence()
            .filter { it.running && it.discovered && it.type !in policy.disabled }
            .filter { it.payloadBytes in 0..it.type.maxPayloadBytes }
            .filter { it.recentFailures < MAX_RECENT_FAILURES }
            .distinctBy { it.type }
            .toList()

        if (policy.mode == TransportSelectionMode.ONLY) {
            val allowed = policy.preferred.toSet()
            val strict = usable.filter { it.type in allowed }
            val chosen = rank(strict, policy.preferred).firstOrNull()
                ?: return DeliveryPlan.Unavailable("Selected transport is unavailable or cannot carry this packet")
            return DeliveryPlan.Network(chosen.type, emptyList())
        }

        val ordered = rank(usable, policy.preferred)
        val primary = ordered.firstOrNull()
            ?: return DeliveryPlan.Unavailable("No enabled, discovered transport can carry this packet")
        val fallback = if (policy.mode == TransportSelectionMode.PREFER && !policy.allowFallback) {
            emptyList()
        } else if (policy.mode == TransportSelectionMode.AUTOMATIC && !policy.allowFallback) {
            emptyList()
        } else {
            ordered.drop(1).map { it.type }
        }
        return DeliveryPlan.Network(primary.type, fallback)
    }

    private fun rank(
        candidates: List<AvailableTransport>,
        preferred: List<TransportType>
    ): List<AvailableTransport> {
        val preferenceIndex = preferred.withIndex().associate { it.value to it.index }
        return candidates.sortedWith(
            compareBy<AvailableTransport> { preferenceIndex[it.type] ?: Int.MAX_VALUE }
                .thenBy { it.recentFailures }
                .thenByDescending { it.signalStrengthDbm ?: -110 }
                .thenByDescending { it.type.maxPayloadBytes }
        )
    }

    private fun decodeTypes(raw: String?): List<TransportType> = raw.orEmpty()
        .split(',')
        .mapNotNull { name -> runCatching { TransportType.valueOf(name) }.getOrNull() }

    companion object {
        private const val PREFS = "meshlink_transport_policy"
        private const val KEY_MODE = "mode"
        private const val KEY_PREFERRED = "preferred"
        private const val KEY_DISABLED = "disabled"
        private const val KEY_FALLBACK = "fallback"
        private const val MAX_RECENT_FAILURES = 3
    }
}
