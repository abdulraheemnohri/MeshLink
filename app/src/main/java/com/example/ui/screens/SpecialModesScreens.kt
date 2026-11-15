package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.protocol.TransportType
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.SettingsEntity
import com.example.location.LocalGpsFix
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.PulseStatusDot
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkBridgeScreen(
    settings: SettingsEntity,
    networkEvents: List<NetworkEventEntity>,
    onUpdateSettings: ((SettingsEntity) -> SettingsEntity) -> Unit
) {
    val bridgeTransports = listOf(
        TransportType.BLE,
        TransportType.WIFI_DIRECT,
        TransportType.WIFI_AWARE,
        TransportType.LOCAL_LAN,
        TransportType.HOTSPOT
    )
    val bridgeLogs = networkEvents.filter { it.eventType == "BRIDGE_FWD" || it.transportType == "BRIDGE" }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(
                    accentColor = if (settings.bridgeEnabled) MeshEmerald else NeonCyan
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            PulseStatusDot(active = settings.bridgeEnabled, activeColor = MeshEmerald)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (settings.bridgeEnabled) "Network Bridge ACTIVE" else "Network Bridge Standby",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (settings.bridgeEnabled) MeshEmerald else MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Connect two isolated local mesh clusters through this phone",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.bridgeEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { it.copy(bridgeEnabled = enabled) }
                            },
                            modifier = Modifier.testTag("bridge_enable_switch")
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MeshPillBadge(settings.bridgeNetworkA, NeonCyan)
                        Icon(
                            imageVector = Icons.Filled.CompareArrows,
                            contentDescription = "Bridge link",
                            tint = if (settings.bridgeEnabled) MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        MeshPillBadge(settings.bridgeNetworkB, ElectricTeal)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Bridged Packets Forwarded: ${settings.bridgePacketsForwarded}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonCyan
                    )
                }
            }

            item {
                GlassPanel(accentColor = NeonCyan) {
                    Text("Cluster A Transport", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bridgeTransports.forEach { t ->
                            FilterChip(
                                selected = settings.bridgeNetworkA == t.name,
                                onClick = { onUpdateSettings { it.copy(bridgeNetworkA = t.name) } },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag("bridge_net_a_${t.name}")
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Cluster B Transport", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bridgeTransports.forEach { t ->
                            FilterChip(
                                selected = settings.bridgeNetworkB == t.name,
                                onClick = { onUpdateSettings { it.copy(bridgeNetworkB = t.name) } },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag("bridge_net_b_${t.name}")
                            )
                        }
                    }
                    if (settings.bridgeNetworkA == settings.bridgeNetworkB) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Select two distinct transports for Cluster A and Cluster B to bridge separate local networks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshAmber
                        )
                    }
                }
            }

            item {
                GlassPanel(accentColor = ElectricTeal) {
                    SectionTitle(title = "Bridge Relay Policies")
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bridge Broadcast & Emergency Packets", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Forward community & SOS broadcasts across clusters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.bridgeAllowBroadcasts,
                            onCheckedChange = { c -> onUpdateSettings { it.copy(bridgeAllowBroadcasts = c) } }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Require QR-Verified Senders Only", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Only bridge traffic from trusted cryptographic peers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.bridgeRequireVerifiedPeers,
                            onCheckedChange = { c -> onUpdateSettings { it.copy(bridgeRequireVerifiedPeers = c) } }
                        )
                    }
                }
            }

            item {
                SectionTitle(title = "Bridge Forwarding Log (${bridgeLogs.size})")
            }

            if (bridgeLogs.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal.copy(alpha = 0.5f)) {
                        Text(
                            text = "No cross-network packets bridged yet. Enable the bridge when connected to peers on two distinct transports.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(bridgeLogs.take(25), key = { it.id }) { ev ->
                    GlassPanel(accentColor = MeshEmerald) {
                        Text(ev.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyModeScreen(
    settings: SettingsEntity,
    currentLocation: LocalGpsFix?,
    broadcasts: List<EmergencyBroadcastEntity>,
    onToggleEmergencyActive: (Boolean) -> Unit,
    onSendEmergencyBroadcast: (String, Boolean) -> Unit
) {
    var draft by remember(settings.emergencyMessage) { mutableStateOf(settings.emergencyMessage) }
    var attachGps by remember { mutableStateOf(true) }

    val presets = listOf(
        "Medical assistance needed at our location." to "Medical Aid",
        "Road/bridge blocked — reroute via alternate mesh path." to "Hazard Alert",
        "Offline community shelter & charging point open here." to "Shelter Open",
        "All clear at our node — standing by for relay." to "All Clear"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = if (settings.emergencyModeActive) MeshCrimson else MeshAmber) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            PulseStatusDot(
                                active = settings.emergencyModeActive,
                                activeColor = MeshCrimson
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (settings.emergencyModeActive) "EMERGENCY MODE ACTIVE" else "Emergency Mode Ready",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (settings.emergencyModeActive) MeshCrimson else MeshAmber
                                )
                                Text(
                                    text = "Priority-3 Epidemic & Store-and-Forward flood with best-effort delivery ACKs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.emergencyModeActive,
                            onCheckedChange = onToggleEmergencyActive,
                            modifier = Modifier.testTag("emergency_mode_switch")
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MeshAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Best-effort offline relay: intermediate devices cannot guarantee downstream delivery.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshAmber
                        )
                    }
                }
            }

            item {
                GlassPanel(accentColor = MeshCrimson) {
                    Text("Send High-Priority Broadcast", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (msg, label) ->
                            FilterChip(
                                selected = draft == msg,
                                onClick = { draft = msg },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Emergency Broadcast Message") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("emergency_message_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (currentLocation != null) {
                                    "Attach GPS (%.4f, %.4f)".format(currentLocation.latitude, currentLocation.longitude)
                                } else {
                                    "Attach GPS (acquire fix in Location)"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = attachGps, onCheckedChange = { attachGps = it })
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onSendEmergencyBroadcast(draft, attachGps) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeshCrimson,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("emergency_send_button")
                    ) {
                        Icon(Icons.Filled.MedicalServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Broadcast Emergency Alert")
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Emergency Broadcasts & ACKs (${broadcasts.size})",
                    subtitle = "Tracks best-effort DELIVERY_ACK receipts returned by nearby peers"
                )
            }

            if (broadcasts.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = MeshAmber, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("No emergency alerts sent or received", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                items(broadcasts, key = { it.messageId }) { item ->
                    val ts = remember(item.createdAt) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.createdAt))
                    }
                    val ackPeers = item.ackedByCsv
                        .split(",")
                        .filter { it.isNotBlank() }
                        .map { it.substringAfter(":", it) }

                    GlassPanel(
                        accentColor = if (item.ackCount > 0) MeshEmerald else MeshCrimson
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MeshPillBadge(
                                text = if (item.isOutgoing) "OUTGOING SOS" else "FROM ${item.senderAlias}",
                                color = MeshCrimson
                            )
                            Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.body, style = MaterialTheme.typography.bodyMedium)
                        if (item.latitude != null && item.longitude != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Coordinates: %.5f, %.5f".format(item.latitude, item.longitude),
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (item.isOutgoing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (item.ackCount > 0) Icons.Filled.Verified else Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = if (item.ackCount > 0) MeshEmerald else MeshAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (item.ackCount > 0) {
                                        "Acked by ${item.ackCount} peer(s): ${ackPeers.joinToString(", ")}"
                                    } else {
                                        "Dispatched to ${item.relayCount} link(s) • Awaiting peer ACKs"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.ackCount > 0) MeshEmerald else MeshAmber
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
