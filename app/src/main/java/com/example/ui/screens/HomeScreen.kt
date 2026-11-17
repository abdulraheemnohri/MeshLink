package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.permissions.HardwareCapabilityReport
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.SettingsEntity
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.PulseStatusDot
import com.example.ui.components.SectionTitle
import com.example.ui.navigation.MeshRoute
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    identity: DeviceIdentityEntity?,
    peers: List<PeerEntity>,
    pendingPackets: List<PendingPacketEntity>,
    transfers: List<FileTransferEntity>,
    settings: SettingsEntity,
    capabilities: HardwareCapabilityReport,
    isRelayRunning: Boolean,
    onToggleRelay: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    onToggleEmergency: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val connectedCount = peers.count { it.isConnected && !it.blocked }
    val activeTransfers = transfers.count { it.status == "TRANSFERRING" || it.status == "QUEUED" }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_mesh_hero_1794753593844),
                    contentDescription = "MeshLink live network banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseStatusDot(
                                active = isRelayRunning,
                                activeColor = if (settings.emergencyModeActive) MeshCrimson else MeshEmerald
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (settings.emergencyModeActive) {
                                    "EMERGENCY BEACON ACTIVE"
                                } else if (isRelayRunning) {
                                    "MESH RELAY ONLINE"
                                } else {
                                    "RELAY STANDBY"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (settings.emergencyModeActive) MeshCrimson else NeonCyan
                            )
                        }
                        Switch(
                            checked = isRelayRunning,
                            onCheckedChange = onToggleRelay,
                            modifier = Modifier.testTag("home_relay_switch")
                        )
                    }

                    Column {
                        Text(
                            text = identity?.alias ?: "MeshLink Node",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "ID ${identity?.deviceId ?: "..."} • Strategy ${settings.defaultRoutingStrategy}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            GlassPanel(accentColor = NeonCyan) {
                SectionTitle(
                    title = "Quick Actions",
                    subtitle = "Jump straight into the most-used mesh workflows"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onNavigate(MeshRoute.DISCOVER) },
                        modifier = Modifier.testTag("home_quick_discover")
                    ) { Text("Discover peers") }
                    OutlinedButton(
                        onClick = { onNavigate(MeshRoute.CHATS) },
                        modifier = Modifier.testTag("home_quick_chats")
                    ) { Text("Open chats") }
                    OutlinedButton(
                        onClick = { onNavigate(MeshRoute.FILES) },
                        modifier = Modifier.testTag("home_quick_files")
                    ) { Text("Share files") }
                    OutlinedButton(
                        onClick = { onNavigate(MeshRoute.DIAGNOSTICS) },
                        modifier = Modifier.testTag("home_quick_diagnostics")
                    ) { Text("Diagnostics") }
                }
            }

            GlassPanel(accentColor = ElectricTeal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hardware Radios",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    OutlinedButton(
                        onClick = onScanNow,
                        modifier = Modifier.testTag("home_scan_button")
                    ) {
                        Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan Mesh")
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeshPillBadge(
                        text = if (capabilities.bleSupported) "BLE ${if (capabilities.bluetoothEnabled) "ON" else "OFF"}" else "No BLE",
                        color = if (capabilities.bleSupported && capabilities.bluetoothEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Filled.Bluetooth
                    )
                    MeshPillBadge(
                        text = if (capabilities.wifiDirectSupported) "Wi-Fi Direct" else "No P2P",
                        color = if (capabilities.wifiDirectSupported && capabilities.wifiEnabled) ElectricTeal else MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Filled.Wifi
                    )
                    MeshPillBadge(
                        text = if (capabilities.wifiAwareSupported) "Wi-Fi Aware" else "Aware N/A",
                        color = if (capabilities.wifiAwareSupported) MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Filled.CellTower
                    )
                    MeshPillBadge(
                        text = capabilities.localLanIp ?: "LAN Offline",
                        color = if (capabilities.localLanIp != null) MeshEmerald else MeshAmber,
                        icon = Icons.Filled.Hub
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "Connected",
                    value = connectedCount.toString(),
                    accent = MeshEmerald,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(MeshRoute.CONNECTIONS) }
                        .testTag("metric_connected")
                )
                MetricTile(
                    label = "DTN Queue",
                    value = pendingPackets.size.toString(),
                    accent = MeshAmber,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(MeshRoute.ROUTING) }
                        .testTag("metric_dtn_queue")
                )
                MetricTile(
                    label = "Transfers",
                    value = activeTransfers.toString(),
                    accent = NeonCyan,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(MeshRoute.TRANSFERS) }
                        .testTag("metric_transfers")
                )
            }

            // Network Bridge Status Banner
            GlassPanel(
                accentColor = if (settings.bridgeEnabled) MeshEmerald else ElectricTeal,
                modifier = Modifier
                    .clickable { onNavigate(MeshRoute.BRIDGE) }
                    .testTag("home_bridge_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Filled.CompareArrows,
                            contentDescription = "Network Bridge",
                            tint = if (settings.bridgeEnabled) MeshEmerald else ElectricTeal,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (settings.bridgeEnabled) {
                                    "Network Bridge ACTIVE (${settings.bridgeNetworkA} ⇄ ${settings.bridgeNetworkB})"
                                } else {
                                    "Network Bridge Standby"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (settings.bridgeEnabled) MeshEmerald else MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "${settings.bridgePacketsForwarded} cross-network packets bridged • Tap to configure",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MeshPillBadge(
                        text = if (settings.bridgeEnabled) "ON" else "OFF",
                        color = if (settings.bridgeEnabled) MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassPanel(
                accentColor = if (settings.emergencyModeActive) MeshCrimson else MeshAmber,
                modifier = Modifier.clickable { onNavigate(MeshRoute.EMERGENCY) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MeshLink Emergency Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Send high-priority SOS broadcasts with best-effort delivery ACKs. Tap to open console.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onToggleEmergency,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.emergencyModeActive) MeshCrimson else MeshAmber,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.testTag("home_emergency_button")
                    ) {
                        Icon(Icons.Filled.MedicalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (settings.emergencyModeActive) "Stop" else "SOS")
                    }
                }
            }

            SectionTitle(
                title = "Mesh Modules",
                subtitle = "Direct access to every decentralized subsystem"
            )

            val shortcuts = listOf(
                Triple("Discover", MeshRoute.DISCOVER, Icons.Filled.Radar),
                Triple("Network Map", MeshRoute.NETWORK_MAP, Icons.Filled.Hub),
                Triple("Bridge", MeshRoute.BRIDGE, Icons.Filled.CompareArrows),
                Triple("Emergency", MeshRoute.EMERGENCY, Icons.Filled.MedicalServices),
                Triple("Connections", MeshRoute.CONNECTIONS, Icons.Filled.SwapVert),
                Triple("File Sharing", MeshRoute.FILES, Icons.Filled.FolderZip),
                Triple("Transfers", MeshRoute.TRANSFERS, Icons.Filled.CloudQueue),
                Triple("Voice PTT", MeshRoute.VOICE, Icons.Filled.GraphicEq),
                Triple("Location", MeshRoute.LOCATION, Icons.Filled.LocationOn),
                Triple("Identity QR", MeshRoute.IDENTITY, Icons.Filled.QrCode2),
                Triple("Security", MeshRoute.SECURITY, Icons.Filled.Security),
                Triple("Routing", MeshRoute.ROUTING, Icons.Filled.AltRoute),
                Triple("Diagnostics", MeshRoute.DIAGNOSTICS, Icons.Filled.Memory),
                Triple("Settings", MeshRoute.SETTINGS, Icons.Filled.Settings)
            )

            shortcuts.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { (title, route, icon) ->
                        QuickModuleTile(
                            title = title,
                            icon = icon,
                            onClick = { onNavigate(route) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_$route")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassPanel(accentColor = accent, modifier = modifier) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()).togetherWith(
                    slideOutVertically { -it / 2 } + fadeOut()
                )
            },
            label = "dashboard_metric"
        ) { animatedValue ->
            Text(
                text = animatedValue,
                style = MaterialTheme.typography.headlineMedium,
                color = accent
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickModuleTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        accentColor = NeonCyan.copy(alpha = 0.5f),
        cornerRadius = 16.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = NeonCyan,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
        }
    }
}
