package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entities.DeviceIdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceIdentityDao {
    @Query("SELECT * FROM device_identity LIMIT 1")
    fun observeIdentity(): Flow<DeviceIdentityEntity?>

    @Query("SELECT * FROM device_identity LIMIT 1")
    suspend fun getIdentityOnce(): DeviceIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: DeviceIdentityEntity)

    @Update
    suspend fun update(identity: DeviceIdentityEntity)

    @Delete
    suspend fun delete(identity: DeviceIdentityEntity)

    @Query("DELETE FROM device_identity")
    suspend fun clearAll()
}
