package com.example

import android.Manifest
import com.example.core.permissions.HardwareCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionCatalogTest {

    @Test
    fun `all optional feature permissions are catalogued`() {
        val permissions = HardwareCapabilities.requiredRuntimePermissions()
        assertTrue(permissions.contains(Manifest.permission.CAMERA))
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(permissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `transport startup does not demand camera microphone or notifications`() {
        val permissions = HardwareCapabilities.transportRuntimePermissions()
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(permissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertFalse(permissions.contains(Manifest.permission.CAMERA))
        assertFalse(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `legacy and optional permissions are exposed as separate feature groups`() {
        assertTrue(HardwareCapabilities.cameraRuntimePermissions().contains(Manifest.permission.CAMERA))
        assertTrue(HardwareCapabilities.audioRuntimePermissions().contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(HardwareCapabilities.locationRuntimePermissions().contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(HardwareCapabilities.notificationRuntimePermissions().contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}
