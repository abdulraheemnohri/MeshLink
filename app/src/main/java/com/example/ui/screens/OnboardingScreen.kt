package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.permissions.HardwareCapabilities
import com.example.core.permissions.HardwareCapabilityReport
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan

@Composable
fun OnboardingScreen(
    defaultAlias: String,
    capabilities: HardwareCapabilityReport,
    onRefreshPermissions: () -> Unit,
    onComplete: (String) -> Unit
) {
    var alias by rememberSaveable(defaultAlias) { mutableStateOf(defaultAlias) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_onboarding_node_1794753595473),
                        contentDescription = "MeshLink multi-hop relay illustration",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.05f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        MeshPillBadge(
                            text = "ZERO-SERVER • E2EE • OFFLINE",
                            color = NeonCyan,
                            icon = Icons.Filled.Security
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                GlassPanel(accentColor = NeonCyan) {
                    Text(
                        text = "1. Local Cryptographic Identity",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your ECDSA + ECDH P-256 keys stay exclusively inside Android KeyStore on this phone. No email, phone number, or SIM required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Node Alias") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_alias_input")
                    )
                }

                GlassPanel(accentColor = ElectricTeal) {
                    Text(
                        text = "2. Radio & Sensor Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Grant only the capabilities you plan to use. Nearby-device and Wi-Fi permissions enable discovery; location and microphone are for location sharing and push-to-talk. You can continue without optional permissions and enable them later in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PermissionStatusRow(
                        icon = Icons.Filled.Bluetooth,
                        label = "Bluetooth LE Discovery & Relay",
                        granted = capabilities.hasBluetoothPermissions
                    )
                    PermissionStatusRow(
                        icon = Icons.Filled.Wifi,
                        label = "Nearby Wi-Fi Direct / Aware",
                        granted = capabilities.hasNearbyWifiPermission
                    )
                    PermissionStatusRow(
                        icon = Icons.Filled.LocationOn,
                        label = "Offline GPS Coordinates",
                        granted = capabilities.hasLocationPermission
                    )
                    PermissionStatusRow(
                        icon = Icons.Filled.Mic,
                        label = "Push-to-Talk Microphone",
                        granted = capabilities.hasAudioPermission
                    )
                    PermissionStatusRow(
                        icon = Icons.Filled.Notifications,
                        label = "Foreground Relay Service Notice",
                        granted = capabilities.hasNotificationPermission
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(
                                HardwareCapabilities.requiredRuntimePermissions().toTypedArray()
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("grant_permissions_button")
                    ) {
                        Text("Grant Mesh Permissions")
                    }
                }

                Button(
                    onClick = { onComplete(alias) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("onboarding_complete_button")
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_cta),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    label: String,
    granted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (granted) MeshEmerald else MeshAmber,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Granted",
                tint = MeshEmerald,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = "Needed",
                style = MaterialTheme.typography.labelSmall,
                color = MeshAmber
            )
        }
    }
}
