package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.profile.LocalUserProfile

@Composable
fun ProfileScreen(
    profile: LocalUserProfile,
    onProfileChange: (LocalUserProfile) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(profile.avatarEmoji, style = MaterialTheme.typography.displayMedium)
                Column {
                    Text(profile.displayName.ifBlank { "Your MeshLink profile" }, style = MaterialTheme.typography.titleLarge)
                    Text(profile.status, color = MaterialTheme.colorScheme.secondary)
                    Text("Stored locally on this device", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("PROFILE DETAILS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = profile.displayName,
            onValueChange = { onProfileChange(profile.copy(displayName = it.take(48))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            supportingText = { Text("${profile.displayName.length}/48 characters") },
            singleLine = true
        )
        OutlinedTextField(
            value = profile.avatarEmoji,
            onValueChange = { onProfileChange(profile.copy(avatarEmoji = it.take(8))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Avatar emoji") },
            supportingText = { Text("Use an emoji or short symbol") },
            singleLine = true
        )
        OutlinedTextField(
            value = profile.status,
            onValueChange = { onProfileChange(profile.copy(status = it.take(32))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Status") },
            singleLine = true
        )
        OutlinedTextField(
            value = profile.about,
            onValueChange = { onProfileChange(profile.copy(about = it.take(160))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("About") },
            supportingText = { Text("${profile.about.length}/160 characters") },
            minLines = 3,
            maxLines = 4
        )

        Text("PRIVACY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

        Text(
            "Your profile is local-only. These privacy switches are saved preferences; they do not automatically publish profile data or change cryptographic identity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = profile.displayName.isNotBlank()) {
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
