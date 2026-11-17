package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.permissions.HardwareCapabilityReport
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.RoutingStrategy
import com.example.core.protocol.TransportType
import com.example.transport.TransportPolicy
import com.example.transport.TransportHealthSnapshot
import com.example.transport.TransportSelectionMode
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import com.example.data.entities.UserSettings
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.QrCodePreview
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IdentityScreen(
    identity: DeviceIdentityEntity?,
    identityUri: String,
    capabilities: HardwareCapabilityReport,
    onUpdateAlias: (String) -> Unit,
    onImportPeerUri: (String) -> Unit
) {
    val context = LocalContext.current
    var aliasDraft by remember(identity?.alias) { mutableStateOf(identity?.alias ?: "") }
    var showImportDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var uriDraft by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Device Cryptographic Identity",
                        subtitle = "ECDSA P-256 signing + ECDH P-256 key agreement"
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodePreview(content = identityUri)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Node ID: ${identity?.deviceId ?: "..."}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonCyan
                    )
                    Text(
                        text = "SHA-256 Fingerprint:\n${identity?.fingerprint ?: "..."}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    MeshPillBadge(
                        text = if (capabilities.nfcSupported && capabilities.nfcEnabled) {
                            "NFC Tap-to-Pair Ready"
                        } else if (capabilities.nfcSupported) {
                            "NFC Disabled in System Settings"
                        } else {
                            "NFC Not Present — Use QR / URI"
                        },
                        color = if (capabilities.nfcEnabled) MeshEmerald else MeshAmber,
                        icon = Icons.Filled.Nfc
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("MeshLink Peer URI", identityUri))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("copy_identity_uri")
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy URI")
                        }
                        OutlinedButton(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, identityUri)
                                }
                                context.startActivity(Intent.createChooser(share, "Share MeshLink Node URI"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showQrScanner = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan QR with Camera")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("identity_import_button")
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import & Verify Peer URI")
                    }
                }
            }

            item {
                GlassPanel(accentColor = ElectricTeal) {
                    Text("Node Display Alias", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aliasDraft,
                            onValueChange = { aliasDraft = it },
                            label = { Text("Local Alias") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onUpdateAlias(aliasDraft) }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onResult = { scanned ->
                showQrScanner = false
                onImportPeerUri(scanned)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Peer Identity") },
            text = {
                OutlinedTextField(
                    value = uriDraft,
                    onValueChange = { uriDraft = it },
                    label = { Text("Paste meshlink://peer?...") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportPeerUri(uriDraft)
                        uriDraft = ""
                        showImportDialog = false
                    }
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SecurityScreen(
    settings: SettingsEntity,
    userSettings: UserSettings = UserSettings(),
    peers: List<PeerEntity>,
    securityEvents: List<SecurityEventEntity>,
    onUpdateSettings: ((SettingsEntity) -> SettingsEntity) -> Unit,
    onToggleBiometric: (Boolean) -> Unit = {},
    onLockNow: () -> Unit = {},
    onToggleBlock: (PeerEntity) -> Unit
) {
    val blockedPeers = peers.filter { it.blocked }
    val isLockedEnabled = userSettings.biometricLockEnabled || settings.biometricLockEnabled
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = MeshEmerald) {
                    SectionTitle(
                        title = "Zero-Trust Security & Privacy",
                        subtitle = "End-to-end encrypted payloads • Relay nodes see ciphertext only"
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = NeonCyan)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Require App Unlock on Launch", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Uses Android device biometric or credential confirmation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isLockedEnabled,
                            onCheckedChange = { enabled ->
                                onToggleBiometric(enabled)
                                onUpdateSettings { it.copy(biometricLockEnabled = enabled) }
                            },
                            modifier = Modifier.testTag("security_biometric_switch")
                        )
                    }

                    if (isLockedEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vault lock is armed and active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshEmerald
                            )
                            OutlinedButton(
                                onClick = onLockNow,
                                modifier = Modifier.testTag("security_lock_now_button")
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lock Now")
                            }
                        }
                    }
                }
            }

            if (blockedPeers.isNotEmpty()) {
                item { SectionTitle(title = "Blocked & Revoked Peers") }
                items(blockedPeers, key = { it.deviceId }) { peer ->
                    GlassPanel(accentColor = MeshCrimson) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${peer.alias} (${peer.deviceId})", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onToggleBlock(peer) }) { Text("Unblock") }
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Security Audit Log",
                    subtitle = "${securityEvents.size} local cryptographic & replay events"
                )
            }

            items(securityEvents, key = { it.id }) { event ->
                val ts = remember(event.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))
                }
                GlassPanel(
                    accentColor = when (event.severity) {
                        "ALERT" -> MeshCrimson
                        "WARN" -> MeshAmber
                        else -> NeonCyan
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MeshPillBadge(
                            text = event.eventType,
                            color = if (event.severity == "ALERT") MeshCrimson else NeonCyan
                        )
                        Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(event.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingScreen(
    settings: SettingsEntity,
    routes: List<RouteEntity>,
    pendingPackets: List<PendingPacketEntity>,
    onUpdateSettings: ((SettingsEntity) -> SettingsEntity) -> Unit,
    onFlushQueue: () -> Unit,
    onPurgeExpired: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Hybrid DTN Routing Engine",
                        subtitle = "Direct • Store-and-Forward • Epidemic • Spray-and-Wait"
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutingStrategy.entries.forEach { strategy ->
                            FilterChip(
                                selected = settings.defaultRoutingStrategy == strategy.name,
                                onClick = {
                                    onUpdateSettings { it.copy(defaultRoutingStrategy = strategy.name) }
                                },
                                label = { Text(strategy.label) },
                                modifier = Modifier.testTag("routing_strategy_${strategy.name}")
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val activeStrategy = runCatching {
                        RoutingStrategy.valueOf(settings.defaultRoutingStrategy)
                    }.getOrDefault(RoutingStrategy.AUTONOMOUS)
                    Text(
                        text = activeStrategy.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Hop Limit (TTL): ${settings.defaultHopLimit} hops", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = settings.defaultHopLimit.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(defaultHopLimit = v.toInt()) } },
                        valueRange = 1f..12f,
                        steps = 10
                    )
                    Text("Spray-and-Wait Copies (L): ${settings.sprayCopies}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = settings.sprayCopies.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(sprayCopies = v.toInt()) } },
                        valueRange = 2f..10f,
                        steps = 7
                    )
                }
            }

            item {
                GlassPanel(accentColor = MeshAmber) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("DTN Buffer (${pendingPackets.size} pending)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Note: Relay nodes cannot guarantee downstream delivery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onFlushQueue,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Flush Queue")
                        }
                        OutlinedButton(
                            onClick = onPurgeExpired,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Purge Expired")
                        }
                    }
                }
            }

            item {
                SectionTitle(title = "Known Mesh Routes (${routes.size})")
            }

            items(routes, key = { "${it.destinationId}_${it.nextHopId}" }) { route ->
                GlassPanel(accentColor = ElectricTeal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Dst ${route.destinationId} → Next ${route.nextHopId}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${route.transportType} • ${route.hopCount} hop(s) • Quality ${route.qualityScore}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.AltRoute, contentDescription = null, tint = ElectricTeal)
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    capabilities: HardwareCapabilityReport,
    transportHealth: List<TransportHealthSnapshot> = emptyList(),
    isRelayRunning: Boolean,
    loopbackResult: String?,
    networkEvents: List<NetworkEventEntity>,
    onRefreshCapabilities: () -> Unit,
    onRunLoopbackTest: () -> Unit,
    onClearLogs: () -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefreshCapabilities() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = NeonCyan) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionTitle(
                            title = "Hardware & Transport Matrix",
                            subtitle = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    CapabilityRow("Bluetooth LE", capabilities.bleSupported && capabilities.bluetoothEnabled && capabilities.hasBluetoothPermissions, if (!capabilities.hasBluetoothPermissions) "Nearby Devices permission missing" else if (capabilities.bleAdvertisingSupported) "Adv+Scan" else "Scan Only")
                    CapabilityRow("Bluetooth Classic", capabilities.bluetoothClassicSupported && capabilities.bluetoothEnabled && capabilities.hasBluetoothPermissions, if (!capabilities.bluetoothClassicSupported) "Not supported" else if (!capabilities.bluetoothEnabled) "Bluetooth off" else if (!capabilities.hasBluetoothPermissions) "Nearby Devices permission missing" else "RFCOMM ready")
                    CapabilityRow("Wi-Fi Direct (P2P)", capabilities.wifiDirectSupported && capabilities.wifiEnabled && capabilities.hasNearbyWifiPermission, if (!capabilities.hasNearbyWifiPermission) "Nearby Wi-Fi permission missing" else "Socket P2P")
                    CapabilityRow("Wi-Fi Aware (NAN)", capabilities.wifiAwareSupported && capabilities.hasNearbyWifiPermission, if (!capabilities.hasNearbyWifiPermission) "Nearby Wi-Fi permission missing" else "API 26+ NAN")
                    CapabilityRow("Local LAN / Hotspot", capabilities.localLanIp != null, capabilities.localLanIp ?: "No IPv4")
                    CapabilityRow("NFC NDEF", capabilities.nfcSupported && capabilities.nfcEnabled, if (capabilities.nfcSupported) "Present" else "Absent")
                    CapabilityRow("Hardware GPS", capabilities.gpsSupported, if (capabilities.hasLocationPermission) "Permitted" else "No Perm")
                    CapabilityRow("Bluetooth permission", capabilities.hasBluetoothPermissions, if (capabilities.hasBluetoothPermissions) "Granted" else "Grant Nearby Devices")
                    CapabilityRow("Nearby Wi-Fi permission", capabilities.hasNearbyWifiPermission, if (capabilities.hasNearbyWifiPermission) "Granted" else "Grant Nearby Wi-Fi")
                    CapabilityRow("Notifications", capabilities.hasNotificationPermission, if (capabilities.hasNotificationPermission) "Granted" else "Notification permission missing")
                    CapabilityRow("Foreground Relay", isRelayRunning, if (isRelayRunning) "Running" else "Stopped")
                    Spacer(Modifier.height(12.dp))
                    SectionTitle(title = "Transport Runtime Health")
                    if (transportHealth.isEmpty()) {
                        Text("No transport adapters are registered.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        transportHealth.forEach { status ->
                            val detail = when {
                                !status.lastError.isNullOrBlank() -> "Error: ${status.lastError}"
                                status.running && status.lastDiscoveryAt != null -> "Running · Last scan " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(status.lastDiscoveryAt))
                                status.running -> "Running · Not scanned yet"
                                else -> "Stopped"
                            }
                            CapabilityRow(status.transportType.name, status.running && status.lastError.isNullOrBlank(), detail)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(HardwareCapabilities.transportRuntimePermissions().toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Grant Bluetooth & Nearby Wi-Fi Permissions")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRunLoopbackTest,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diagnostics_selftest_button")
                        ) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("MLP Crypto Test")
                        }
                        OutlinedButton(
                            onClick = onRefreshCapabilities,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-Probe")
                        }
                    }

                    if (!loopbackResult.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = loopbackResult,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (loopbackResult.startsWith("PASS")) MeshEmerald else MeshCrimson,
                            modifier = Modifier.testTag("diagnostics_selftest_result")
                        )
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Network Event Console (${networkEvents.size})",
                    trailing = {
                        TextButton(onClick = onClearLogs) { Text("Clear") }
                    }
                )
            }

            items(networkEvents, key = { it.id }) { ev ->
                val ts = remember(ev.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ev.timestamp))
                }
                GlassPanel(accentColor = ElectricTeal.copy(alpha = 0.5f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MeshPillBadge("${ev.transportType}:${ev.eventType}", ElectricTeal)
                        Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(ev.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, ready: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = if (ready) "Ready" else "Standby",
                tint = if (ready) MeshEmerald else MeshAmber,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private data class RuntimePermissionGroup(
    val title: String,
    val description: String,
    val permissions: List<String>
)

@Composable
fun SettingsScreen(
    settings: SettingsEntity,
    userSettings: UserSettings = UserSettings(),
    isRelayRunning: Boolean,
    onToggleRelay: (Boolean) -> Unit,
    onToggleBiometric: (Boolean) -> Unit = {},
    onLockNow: () -> Unit = {},
    transportPolicy: TransportPolicy = TransportPolicy(),
    onUpdateTransportPolicy: (TransportPolicy) -> Unit = {},
    onUpdateSettings: ((SettingsEntity) -> SettingsEntity) -> Unit
) {
    var emergencyDraft by remember(settings.emergencyMessage) { mutableStateOf(settings.emergencyMessage) }
    var permissionRefresh by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val permissionGroups = remember {
        listOf(
            RuntimePermissionGroup("Camera", "Scan peer identity QR codes.", HardwareCapabilities.cameraRuntimePermissions()),
            RuntimePermissionGroup("Bluetooth / Nearby Devices", "BLE scan and advertising, Bluetooth Classic connections, and Nearby peer discovery.", HardwareCapabilities.bluetoothRuntimePermissions()),
            RuntimePermissionGroup("Nearby Wi-Fi", "Wi-Fi Direct and Wi-Fi Aware discovery. Android may group this with Nearby Devices.", HardwareCapabilities.nearbyWifiRuntimePermissions()),
            RuntimePermissionGroup("Location", "GPS coordinates, location sharing, and legacy Wi-Fi/Bluetooth discovery where Android requires it.", HardwareCapabilities.locationRuntimePermissions()),
            RuntimePermissionGroup("Microphone", "Voice push-to-talk recording. This permission is optional.", HardwareCapabilities.audioRuntimePermissions()),
            RuntimePermissionGroup("Notifications", "Relay-service status and transfer/connection notifications.", HardwareCapabilities.notificationRuntimePermissions())
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionRefresh++ }
    val isBiometricActive = userSettings.biometricLockEnabled || settings.biometricLockEnabled

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Security & Biometric Protection",
                        subtitle = "Hardware-backed biometric or device PIN lock for local vault"
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Biometric Lock on Launch",
                        subtitle = "Enforce fingerprint, face, or device PIN to access offline mesh storage",
                        checked = isBiometricActive,
                        onCheckedChange = { enabled ->
                            onToggleBiometric(enabled)
                            onUpdateSettings { it.copy(biometricLockEnabled = enabled) }
                        }
                    )
                    if (isBiometricActive) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vault lock is armed and active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshEmerald
                            )
                            Button(
                                onClick = onLockNow,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("settings_lock_now_button")
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lock Vault Now")
                            }
                        }
                    }
                }
            }

            item {
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Appearance & Theme",
                        subtitle = "Dark-first obsidian or crisp technical light theme"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DARK", "LIGHT", "SYSTEM").forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                label = { Text(mode) },
                                modifier = Modifier.testTag("theme_chip_$mode")
                            )
                        }
                    }
                }
            }

            item {
                GlassPanel(accentColor = MeshAmber) {
                    SectionTitle(
                        title = "Android Permission Manager",
                        subtitle = "Grant only the capabilities you want to use. Optional permissions are not requested automatically."
                    )
                    Spacer(Modifier.height(8.dp))
                    val permissionStates = remember(permissionRefresh, permissionGroups) {
                        permissionGroups.map { group ->
                            group to group.permissions.filterNot { permission ->
                                HardwareCapabilities.hasPermission(context, permission)
                            }
                        }
                    }
                    permissionStates.forEach { (group, missing) ->
                        val grantedCount = group.permissions.size - missing.size
                        val isGranted = missing.isEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(group.title, style = MaterialTheme.typography.titleSmall)
                                Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when {
                                        group.permissions.isEmpty() -> "Not required on this Android version"
                                        isGranted -> "Granted"
                                        else -> "$grantedCount of ${group.permissions.size} permissions granted"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isGranted) MeshEmerald else MeshAmber
                                )
                            }
                            OutlinedButton(
                                enabled = missing.isNotEmpty(),
                                onClick = { permissionLauncher.launch(missing.toTypedArray()) },
                                modifier = Modifier.testTag("permission_request_button")
                            ) { Text(if (isGranted) "Granted" else "Allow") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Open Android App Settings") }
                    Text(
                        "File sharing uses Android's system document picker, so broad storage access is not required. NFC, biometric, network, wake-lock and foreground-service declarations are handled by Android without runtime permission dialogs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                GlassPanel(accentColor = ElectricTeal) {
                    SectionTitle(title = "Active Transports & Relay Service")
                    Spacer(Modifier.height(6.dp))
                    SettingSwitchRow(
                        title = "Foreground Relay Service",
                        subtitle = "Persistent notification keeps local relay running",
                        checked = isRelayRunning,
                        onCheckedChange = onToggleRelay
                    )
                    SettingSwitchRow(
                        title = "Local Wi-Fi LAN / Hotspot",
                        subtitle = "UDP:45820 discovery & TCP:45821 packet frames",
                        checked = settings.localLanEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(localLanEnabled = c) } }
                    )
                    SettingSwitchRow(
                        title = "Bluetooth LE Mesh",
                        subtitle = "Low-power beacon & small packet GATT relay",
                        checked = settings.bleEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(bleEnabled = c) } }
                    )
                    SettingSwitchRow(
                        title = "Bluetooth Classic (RFCOMM)",
                        subtitle = "Connects to already-bonded peers; Android Nearby Devices permission required",
                        checked = settings.bluetoothClassicEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(bluetoothClassicEnabled = c) } }
                    )
                    SettingSwitchRow(
                        title = "Google Nearby Connections",
                        subtitle = "Cross-device discovery and encrypted MeshLink packets; requires Google Play services",
                        checked = settings.nearbyConnectionsEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(nearbyConnectionsEnabled = c) } }
                    )
                    SettingSwitchRow(
                        title = "Wi-Fi Direct (P2P)",
                        subtitle = "High-throughput peer-to-peer Wi-Fi links",
                        checked = settings.wifiDirectEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(wifiDirectEnabled = c) } }
                    )
                    SettingSwitchRow(
                        title = "Wi-Fi Aware (NAN)",
                        subtitle = "Hardware-supported neighbor awareness networking",
                        checked = settings.wifiAwareEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(wifiAwareEnabled = c) } }
                    )
                }
            }

            item {
                GlassPanel(accentColor = MeshAmber) {
                    Text("Emergency SOS Message", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emergencyDraft,
                        onValueChange = { emergencyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onUpdateSettings { it.copy(emergencyMessage = emergencyDraft) } },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save SOS Template")
                    }
                }
            }

            item {
                GlassPanel(accentColor = ElectricTeal) {
                    SectionTitle(
                        title = "Universal Network Manager",
                        subtitle = "Choose how MeshLink selects real, discovered transport adapters"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TransportSelectionMode.AUTOMATIC,
                            TransportSelectionMode.PREFER,
                            TransportSelectionMode.ONLY
                        ).forEach { mode ->
                            FilterChip(
                                selected = transportPolicy.mode == mode,
                                onClick = { onUpdateTransportPolicy(transportPolicy.copy(mode = mode)) },
                                label = { Text(mode.name) }
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "Allow fallback",
                        subtitle = "Only applies to Automatic and Prefer modes",
                        checked = transportPolicy.allowFallback,
                        onCheckedChange = { onUpdateTransportPolicy(transportPolicy.copy(allowFallback = it)) }
                    )
                    Text("Enabled transport preferences", style = MaterialTheme.typography.titleSmall)
                    val transportLabels = listOf(
                        TransportType.LOCAL_LAN to "Same WLAN / LAN / compatible hotspot",
                        TransportType.WIFI_DIRECT to "Wi-Fi Direct",
                        TransportType.WIFI_AWARE to "Wi-Fi Aware",
                        TransportType.BLE to "Bluetooth LE",
                        TransportType.HOTSPOT to "Hotspot (LAN adapter path)",
                        TransportType.NFC to "NFC identity handoff (not a data transport)",
                        TransportType.QR to "QR identity handoff (not a data transport)",
                        TransportType.BLUETOOTH_CLASSIC to "Bluetooth Classic RFCOMM (bonded peers)",
                        TransportType.NEARBY_CONNECTIONS to "Google Nearby Connections"
                    ).toMap()
                    val orderedTransportTypes = (transportPolicy.preferred + transportLabels.keys).distinct()
                        .filter { it in transportLabels }
                    orderedTransportTypes.forEachIndexed { index, type ->
                        val label = transportLabels.getValue(type)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SettingSwitchRow(
                                title = label,
                                subtitle = if (type in setOf(TransportType.NFC, TransportType.QR))
                                    "Identity handoff only; not a data transport"
                                else "Preference only; requires supported hardware, permission and a discovered endpoint",
                                checked = type !in transportPolicy.disabled,
                                onCheckedChange = { enabled ->
                                    onUpdateTransportPolicy(
                                        transportPolicy.copy(
                                            disabled = if (enabled) transportPolicy.disabled - type
                                            else transportPolicy.disabled + type
                                        )
                                    )
                                }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val reordered = orderedTransportTypes.toMutableList()
                                        val moved = reordered.removeAt(index)
                                        reordered.add(index - 1, moved)
                                        onUpdateTransportPolicy(transportPolicy.copy(preferred = reordered))
                                    }
                                ) { Text("Move up") }
                                TextButton(
                                    enabled = index < orderedTransportTypes.lastIndex,
                                    onClick = {
                                        val reordered = orderedTransportTypes.toMutableList()
                                        val moved = reordered.removeAt(index)
                                        reordered.add(index + 1, moved)
                                        onUpdateTransportPolicy(transportPolicy.copy(preferred = reordered))
                                    }
                                ) { Text("Move down") }
                            }
                        }
                    }
                    Text(
                        "Same-device delivery is handled locally without Bluetooth or Wi-Fi. WLAN, LAN and hotspot share the Local LAN IP adapter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                GlassPanel(accentColor = MeshEmerald) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = MeshEmerald)
                        Spacer(Modifier.width(8.dp))
                        Text("Open-Source Zero-Server Manifesto", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "MeshLink uses no central servers, no mandatory accounts, no ads, and no telemetry. All messages and files are encrypted locally using MeshLink Protocol v1 (MLP/1).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
