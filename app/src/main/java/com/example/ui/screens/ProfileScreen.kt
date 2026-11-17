package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.data.profile.LocalUserProfile
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan

@Composable
fun ProfileScreen(
    profile: LocalUserProfile,
    onProfileChange: (LocalUserProfile) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassPanel(accentColor = NeonCyan, cornerRadius = 26.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                NeonCyan.copy(alpha = 0.14f),
                                ElectricTeal.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(NeonCyan.copy(alpha = 0.24f), ElectricTeal.copy(alpha = 0.18f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(profile.avatarEmoji.ifBlank { "◈" }, style = MaterialTheme.typography.displaySmall)
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        profile.displayName.ifBlank { "Your MeshLink profile" },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        profile.status.ifBlank { "Ready to connect" },
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.size(10.dp))
                    MeshPillBadge("LOCAL PROFILE", MeshEmerald, Icons.Filled.Lock)
                }
            }
        }

        GlassPanel(accentColor = ElectricTeal) {
            SectionTitle("Profile details", "How your identity appears on this device")
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = profile.displayName,
                onValueChange = { onProfileChange(profile.copy(displayName = it.take(48))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display name") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                supportingText = { Text("${profile.displayName.length}/48 characters") },
                singleLine = true
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = profile.avatarEmoji,
                onValueChange = { onProfileChange(profile.copy(avatarEmoji = it.take(8))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Avatar emoji") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                supportingText = { Text("Use an emoji or short symbol") },
                singleLine = true
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = profile.status,
                onValueChange = { onProfileChange(profile.copy(status = it.take(32))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Status") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                singleLine = true
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = profile.about,
                onValueChange = { onProfileChange(profile.copy(about = it.take(160))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("About") },
                supportingText = { Text("${profile.about.length}/160 characters") },
                minLines = 3,
                maxLines = 4
            )
        }

        GlassPanel(accentColor = NeonCyan) {
            SectionTitle("Privacy controls", "Choose what your profile is allowed to share")
            PrivacyToggle(
                title = "Share display name",
                description = "Allow your display name to be included in profile information shared with peers.",
                checked = profile.shareDisplayName,
                onCheckedChange = { onProfileChange(profile.copy(shareDisplayName = it)) }
            )
            PrivacyToggle(
                title = "Allow contact requests",
                description = "Allow other MeshLink peers to request a connection.",
                checked = profile.allowContactRequests,
                onCheckedChange = { onProfileChange(profile.copy(allowContactRequests = it)) }
            )
            PrivacyToggle(
                title = "Show online status",
                description = "A future presence feature can use this preference. It does not currently broadcast presence.",
                checked = profile.showOnlineStatus,
                onCheckedChange = { onProfileChange(profile.copy(showOnlineStatus = it)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = MeshEmerald)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Your profile stays on this device unless a future sharing action explicitly sends it. These preferences do not alter your cryptographic identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            enabled = profile.displayName.isNotBlank()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Save profile")
        }
    }
}

@Composable
private fun PrivacyToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
