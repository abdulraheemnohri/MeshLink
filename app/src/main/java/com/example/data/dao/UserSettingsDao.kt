package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entities.UserSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun observeUserSettings(): Flow<UserSettings?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getUserSettingsOnce(): UserSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: UserSettings)

    @Update
    suspend fun update(settings: UserSettings)

    @Query("UPDATE user_settings SET biometricLockEnabled = :enabled, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setBiometricLockEnabled(enabled: Boolean, updatedAt: Long = System.currentTimeMillis())
}
