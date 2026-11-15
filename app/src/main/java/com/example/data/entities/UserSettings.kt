package com.example.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    val biometricLockEnabled: Boolean = false,
    val requireAuthOnLaunch: Boolean = true,
    val lockTimeoutMinutes: Int = 0,
    val biometricPromptTitle: String = "MeshLink Vault",
    val biometricPromptSubtitle: String = "Verify fingerprint or device PIN to access offline mesh storage",
    val fallbackPasscode: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

typealias UserSettingsEntity = UserSettings
