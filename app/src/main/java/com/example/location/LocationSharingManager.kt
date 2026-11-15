package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.example.core.permissions.HardwareCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalGpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double,
    val speedMps: Float,
    val provider: String,
    val timestamp: Long
)

data class RelativeVector(
    val distanceMeters: Float,
    val bearingDegrees: Float
)

class LocationSharingManager(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentFix = MutableStateFlow<LocalGpsFix?>(null)
    val currentFix: StateFlow<LocalGpsFix?> = _currentFix.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _currentFix.value = location.toFix()
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(): Boolean {
        if (!HardwareCapabilities.hasLocationPermission(context)) return false
        val lm = locationManager ?: return false
        _isTracking.value = true

        val lastGps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val lastNet = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        (lastGps ?: lastNet)?.let { _currentFix.value = it.toFix() }

        if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3_000L,
                    2f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5_000L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }
        return true
    }

    fun stopUpdates() {
        _isTracking.value = false
        runCatching { locationManager?.removeUpdates(listener) }
    }

    fun computeRelativeVector(peerLat: Double, peerLon: Double): RelativeVector? {
        val fix = _currentFix.value ?: return null
        val results = FloatArray(2)
        Location.distanceBetween(fix.latitude, fix.longitude, peerLat, peerLon, results)
        val bearing = ((results[1] % 360f) + 360f) % 360f
        return RelativeVector(distanceMeters = results[0], bearingDegrees = bearing)
    }

    private fun Location.toFix(): LocalGpsFix {
        return LocalGpsFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            altitudeMeters = altitude,
            speedMps = speed,
            provider = provider ?: "gps",
            timestamp = time
        )
    }
}
