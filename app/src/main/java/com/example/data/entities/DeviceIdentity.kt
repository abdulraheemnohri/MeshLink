package com.example.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_identity")
data class DeviceIdentityEntity(
    @PrimaryKey val deviceId: String,
    val alias: String,
    val signingPublicKey: String,
    val agreementPublicKey: String,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
)

typealias DeviceIdentity = DeviceIdentityEntity
