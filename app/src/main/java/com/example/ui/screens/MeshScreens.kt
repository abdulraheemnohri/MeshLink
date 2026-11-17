package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.entities.ConnectionEntity
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SettingsEntity
import com.example.transport.DiscoveredEndpoint
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.PulseStatusDot
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun DiscoverScreen(
    peers: List<PeerEntity>,
    discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    onScanNow: () -> Unit,
    onConnectPeer: (PeerEntity) -> Unit,
    onConnectEndpoint: (DiscoveredEndpoint) -> Unit = {},
    onOpenChat: (String) -> Unit,
    onImportPeerUri: (String) -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var importUriInput by remember { mutableStateOf("") }

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
                        title = "Nearby Mesh Nodes",
                        subtitle = "Scanning BLE, Wi-Fi Direct, Wi-Fi Aware & Local LAN"
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onScanNow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("discover_scan_button")
                        ) {
                            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan Now")
                        }
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("discover_import_qr_button")
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pair URI / QR")
                        }
                    }
                }
            }

            if (discoveredEndpoints.isNotEmpty()) {
                item {
                    val pathsByPeer = discoveredEndpoints.groupBy { it.deviceId }
                    GlassPanel(accentColor = ElectricTeal) {
                        SectionTitle(
                            title = "Live Transport Paths",
                            subtitle = "${pathsByPeer.size} discovered nodes • ${discoveredEndpoints.size} active endpoints"
                        )
                        Spacer(Modifier.height(8.dp))
                        pathsByPeer.forEach { (deviceId, paths) ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = paths.firstOrNull()?.alias?.ifBlank { deviceId } ?: deviceId,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                paths.sortedBy { it.transportType.name }.forEach { path ->
                                    val endpointLabel = if (path.port > 0) "${path.address}:${path.port}" else path.address
                                    val ageSeconds = ((System.currentTimeMillis() - path.lastSeenAt).coerceAtLeast(0L) / 1_000L)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${path.transportType.name}  •  ${endpointLabel}  •  seen ${ageSeconds}s ago",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (path.transportType == com.example.core.protocol.TransportType.BLUETOOTH_CLASSIC) {
                                            TextButton(onClick = { onConnectEndpoint(path) }) { Text("Connect") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (peers.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Radar,
                                contentDescription = "No peers discovered yet",
                                tint = NeonCyan,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Listening for nearby MeshLink phones",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Keep MeshLink open on another phone on the same Wi-Fi/Hotspot or within BLE / Wi-Fi Direct range, or pair via QR / NFC.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(peers, key = { it.deviceId }) { peer ->
                    GlassPanel(
                        accentColor = if (peer.blocked) MeshCrimson else if (peer.isConnected) MeshEmerald else ElectricTeal
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                PulseStatusDot(active = peer.isConnected && !peer.blocked)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = peer.alias,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        if (peer.verifiedByQr) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Filled.VerifiedUser,
                                                contentDescription = "Verified by QR",
                                                tint = MeshEmerald,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${peer.deviceId} • ${peer.activeTransport} • ${peer.signalStrengthDbm} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                IconButton(
                                    onClick = { onConnectPeer(peer) },
                                    modifier = Modifier.testTag("connect_peer_${peer.deviceId}")
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = "Handshake peer", tint = NeonCyan)
                                }
                                IconButton(
                                    onClick = { onOpenChat(peer.deviceId) },
                                    modifier = Modifier.testTag("chat_peer_${peer.deviceId}")
                                ) {
                                    Icon(Icons.Filled.Chat, contentDescription = "Open chat", tint = ElectricTeal)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Pair Peer via MeshLink URI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste a scanned meshlink://peer?... QR or NFC payload to establish a verified E2EE peer record.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = importUriInput,
                        onValueChange = { importUriInput = it },
                        label = { Text("meshlink://peer?...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportPeerUri(importUriInput)
                        importUriInput = ""
                        showImportDialog = false
                    }
                ) {
                    Text("Verify & Trust")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ConnectionsScreen(
    peers: List<PeerEntity>,
    connections: List<ConnectionEntity>,
    onDisconnect: (String) -> Unit,
    onToggleVerify: (PeerEntity) -> Unit,
    onToggleBlock: (PeerEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var peerFilter by remember { mutableStateOf("ALL") }
    val filteredPeers = peers.filter { peer ->
        val matchesQuery = searchQuery.isBlank() || peer.alias.contains(searchQuery, ignoreCase = true) || peer.deviceId.contains(searchQuery, ignoreCase = true) || peer.fingerprint.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (peerFilter) {
            "ONLINE" -> peer.isConnected && !peer.blocked
            "VERIFIED" -> peer.verifiedByQr && !peer.blocked
            "BLOCKED" -> peer.blocked
            else -> true
        }
        matchesQuery && matchesFilter
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionTitle(
                    title = "People & Trusted Peers",
                    subtitle = "${peers.count { it.isConnected }} online • ${peers.count { it.verifiedByQr && !it.blocked }} verified • ${peers.size} total"
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search people, device ID or fingerprint") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("ALL", "ONLINE", "VERIFIED", "BLOCKED").forEach { filter ->
                        TextButton(onClick = { peerFilter = filter }) {
                            Text(if (peerFilter == filter) "• $filter" else filter)
                        }
                    }
                }
            }

            if (peers.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal) {
                        Text(
                            text = "No peer links recorded yet. Discover nearby devices or pair via QR.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredPeers.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal) {
                        Text("No peers match this search or filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(filteredPeers, key = { it.deviceId }) { peer ->
                    GlassPanel(
                        accentColor = if (peer.blocked) MeshCrimson else if (peer.isConnected) MeshEmerald else MeshAmber
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.alias,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "FP: ${peer.fingerprint.take(20)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MeshPillBadge(
                                        text = if (peer.blocked) "BLOCKED" else if (peer.isConnected) peer.activeTransport else "OFFLINE",
                                        color = if (peer.blocked) MeshCrimson else if (peer.isConnected) MeshEmerald else MeshAmber
                                    )
                                    MeshPillBadge(
                                        text = "Rep ${peer.reputationScore}",
                                        color = NeonCyan
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { onToggleVerify(peer) }) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = "Toggle trust verification",
                                        tint = if (peer.verifiedByQr) MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (peer.isConnected) {
                                    IconButton(onClick = { onDisconnect(peer.deviceId) }) {
                                        Icon(
                                            imageVector = Icons.Filled.LinkOff,
                                            contentDescription = "Disconnect peer",
                                            tint = MeshAmber
                                        )
                                    }
                                }
                                IconButton(onClick = { onToggleBlock(peer) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Block,
                                        contentDescription = "Block or unblock peer",
                                        tint = if (peer.blocked) MeshCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (connections.isNotEmpty()) {
                item {
                    SectionTitle(title = "Connection History")
                }
                items(connections.take(20), key = { it.id }) { conn ->
                    GlassPanel(accentColor = NeonCyan.copy(alpha = 0.4f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${conn.peerAlias} (${conn.transportType})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = conn.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (conn.status == "CONNECTED") MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Endpoint ${conn.remoteAddress} • RSSI ${conn.rssiDbm} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkMapScreen(
    identity: DeviceIdentityEntity?,
    peers: List<PeerEntity>,
    routes: List<RouteEntity>,
    settings: SettingsEntity = SettingsEntity()
) {
    var selectedPeer by remember { mutableStateOf<PeerEntity?>(null) }
    val pulseTransition = rememberInfiniteTransition(label = "topology_pulse")
    val pulseProgress by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_ring"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceLine = MaterialTheme.colorScheme.outline

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle(
                title = "Interactive Mesh Topology",
                subtitle = if (settings.bridgeEnabled) {
                    "Bridge ACTIVE (${settings.bridgeNetworkA} ⇄ ${settings.bridgeNetworkB}) • ${peers.size} nodes"
                } else {
                    "${peers.size} known nodes • ${routes.size} routing paths"
                }
            )

            GlassPanel(
                accentColor = NeonCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(peers) {
                                detectTapGestures { tap ->
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val orbit = minOf(size.width, size.height) * 0.34f
                                    peers.forEachIndexed { idx, peer ->
                                        val angle = (2.0 * PI * idx / peers.size.coerceAtLeast(1)) - PI / 2
                                        val px = center.x + (orbit * cos(angle)).toFloat()
                                        val py = center.y + (orbit * sin(angle)).toFloat()
                                        if (hypot(tap.x - px, tap.y - py) <= 44f) {
                                            selectedPeer = peer
                                        }
                                    }
                                }
                            }
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = minOf(size.width, size.height) * 0.42f

                        // Concentric radar grid rings
                        listOf(0.35f, 0.65f, 0.95f).forEach { frac ->
                            drawCircle(
                                color = surfaceLine.copy(alpha = 0.28f),
                                radius = maxRadius * frac,
                                center = center,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                            )
                        }

                        // Expanding sonar wave
                        drawCircle(
                            color = primaryColor.copy(alpha = (1f - pulseProgress) * 0.35f),
                            radius = maxRadius * pulseProgress,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                        )

                        val orbit = minOf(size.width, size.height) * 0.34f
                        peers.forEachIndexed { idx, peer ->
                            val angle = (2.0 * PI * idx / peers.size.coerceAtLeast(1)) - PI / 2
                            val pos = Offset(
                                center.x + (orbit * cos(angle)).toFloat(),
                                center.y + (orbit * sin(angle)).toFloat()
                            )
                            val edgeColor = when {
                                peer.blocked -> MeshCrimson
                                peer.isConnected && peer.activeTransport.contains("LAN") -> primaryColor
                                peer.isConnected -> secondaryColor
                                else -> tertiaryColor
                            }
                            drawLine(
                                color = edgeColor.copy(alpha = if (peer.isConnected) 0.85f else 0.4f),
                                start = center,
                                end = pos,
                                strokeWidth = if (peer.isConnected) 4f else 2f,
                                pathEffect = if (peer.isConnected) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                            )
                            drawCircle(
                                color = edgeColor.copy(alpha = 0.25f),
                                radius = 26f,
                                center = pos
                            )
                            drawCircle(
                                color = edgeColor,
                                radius = 14f,
                                center = pos
                            )
                        }

                        // Local central node (with emerald outer bridge ring when bridging two networks)
                        if (settings.bridgeEnabled) {
                            drawCircle(
                                color = MeshEmerald.copy(alpha = 0.55f),
                                radius = 44f,
                                center = center,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                            )
                        }
                        drawCircle(color = primaryColor.copy(alpha = 0.25f), radius = 34f, center = center)
                        drawCircle(color = primaryColor, radius = 18f, center = center)
                    }

                    FlowRow(
                        modifier = Modifier.align(Alignment.BottomStart),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MeshPillBadge("Local: ${identity?.alias ?: "Node"}", NeonCyan, Icons.Filled.Hub)
                        if (settings.bridgeEnabled) {
                            MeshPillBadge(
                                "BRIDGE: ${settings.bridgeNetworkA} ⇄ ${settings.bridgeNetworkB}",
                                MeshEmerald,
                                Icons.Filled.CompareArrows
                            )
                        }
                        MeshPillBadge("Direct Link", ElectricTeal)
                        MeshPillBadge("DTN Cached", MeshAmber)
                    }
                }
            }

            if (selectedPeer != null) {
                val peer = selectedPeer!!
                GlassPanel(accentColor = ElectricTeal) {
                    Text(
                        text = "Inspected Node: ${peer.alias}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "ID ${peer.deviceId} • Transport ${peer.activeTransport} • Reputation ${peer.reputationScore} • Relayed ${peer.relaySuccessCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
