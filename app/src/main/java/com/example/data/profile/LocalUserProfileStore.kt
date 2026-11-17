package com.example.data.profile

import android.content.Context

data class LocalUserProfile(
    val displayName: String = "",
    val about: String = "",
    val status: String = "Available",
    val avatarEmoji: String = "🙂",
    val shareDisplayName: Boolean = true,
    val allowContactRequests: Boolean = true,
    val showOnlineStatus: Boolean = false,
    val updatedAt: Long = 0L
)

class LocalUserProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meshlink_local_profile", Context.MODE_PRIVATE)

    fun load(): LocalUserProfile = LocalUserProfile(
        displayName = prefs.getString("display_name", "").orEmpty(),
        about = prefs.getString("about", "").orEmpty(),
        status = prefs.getString("status", "Available") ?: "Available",
        avatarEmoji = prefs.getString("avatar_emoji", "🙂") ?: "🙂",
        shareDisplayName = prefs.getBoolean("share_display_name", true),
        allowContactRequests = prefs.getBoolean("allow_contact_requests", true),
        showOnlineStatus = prefs.getBoolean("show_online_status", false),
        updatedAt = prefs.getLong("updated_at", 0L)
    )

    fun save(profile: LocalUserProfile): LocalUserProfile {
        val safe = profile.copy(
            displayName = profile.displayName.trim().take(48),
            about = profile.about.trim().take(160),
            status = profile.status.trim().take(32),
            avatarEmoji = profile.avatarEmoji.take(8),
            updatedAt = System.currentTimeMillis()
        )
        check(prefs.edit()
            .putString("display_name", safe.displayName)
            .putString("about", safe.about)
            .putString("status", safe.status)
            .putString("avatar_emoji", safe.avatarEmoji)
            .putBoolean("share_display_name", safe.shareDisplayName)
            .putBoolean("allow_contact_requests", safe.allowContactRequests)
            .putBoolean("show_online_status", safe.showOnlineStatus)
            .putLong("updated_at", safe.updatedAt)
            .commit()) { "Could not save local profile" }
        return safe
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "Could not clear local profile" }
    }
}
