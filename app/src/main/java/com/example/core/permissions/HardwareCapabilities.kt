package com.example.core.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

data class HardwareCapabilityReport(
    val bleSupported: Boolean,
    val bleAdvertisingSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothClassicSupported: Boolean,
    val wifiDirectSupported: Boolean,
    val wifiAwareSupported: Boolean,
    val wifiEnabled: Boolean,
    val localLanIp: String?,
    val nfcSupported: Boolean,
    val nfcEnabled: Boolean,
    val gpsSupported: Boolean,
    val microphoneSupported: Boolean,
    val hasBluetoothPermissions: Boolean,
    val hasNearbyWifiPermission: Boolean,
    val hasLocationPermission: Boolean,
    val hasAudioPermission: Boolean,
    val hasNotificationPermission: Boolean
)

object HardwareCapabilities {
    /** Runtime permissions required to use enabled radio transports on this Android version. */
    fun transportRuntimePermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Android 11 and earlier require location permission for BLE discovery.
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // Wi-Fi Direct / Aware discovery is location-gated on older releases.
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.distinct()
    }

    fun cameraRuntimePermissions(): List<String> = listOf(Manifest.permission.CAMERA)

    fun bluetoothRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun nearbyWifiRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun locationRuntimePermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun audioRuntimePermissions(): List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    fun notificationRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    /** Union of optional runtime permissions for all user-visible MeshLink features. */
    fun requiredRuntimePermissions(): List<String> = (
        cameraRuntimePermissions() +
            bluetoothRuntimePermissions() +
            nearbyWifiRuntimePermissions() +
            locationRuntimePermissions() +
            audioRuntimePermissions() +
            notificationRuntimePermissions()
        ).distinct()

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) &&
                hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasNearbyWifiPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun inspect(context: Context): HardwareCapabilityReport {
        val pm = context.packageManager
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val bleSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val bleAdvertisingSupported = runCatching {
            bleSupported && btAdapter?.isMultipleAdvertisementSupported == true
        }.getOrDefault(false)

        val wifiAwareSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        } else {
            false
        }

        val gpsSupported = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ||
            locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        return HardwareCapabilityReport(
            bleSupported = bleSupported,
            bleAdvertisingSupported = bleAdvertisingSupported,
            bluetoothEnabled = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false),
            bluetoothClassicSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            wifiDirectSupported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            wifiAwareSupported = wifiAwareSupported,
            wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false),
            localLanIp = resolveLocalIpv4(),
            nfcSupported = nfcAdapter != null,
            nfcEnabled = runCatching { nfcAdapter?.isEnabled == true }.getOrDefault(false),
            gpsSupported = gpsSupported,
            microphoneSupported = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            hasBluetoothPermissions = hasBluetoothPermissions(context),
            hasNearbyWifiPermission = hasNearbyWifiPermission(context),
            hasLocationPermission = hasLocationPermission(context),
            hasAudioPermission = hasPermission(context, Manifest.permission.RECORD_AUDIO),
            hasNotificationPermission = notifGranted
        )
    }

    fun resolveLocalIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank()) return host
                    }
                }
            }
            null
        }.getOrNull()
    }
}
