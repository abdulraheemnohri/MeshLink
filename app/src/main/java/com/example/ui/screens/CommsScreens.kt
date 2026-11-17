package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.PeerEntity
import com.example.location.LocalGpsFix
import com.example.location.RelativeVector
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan
import com.example.voice.VoiceRecordingItem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FileSharingScreen(
    peers: List<PeerEntity>,
    transfers: List<FileTransferEntity>,
    onSendFile: (Uri, String) -> Unit,
    onOpenTransferManager: () -> Unit
) {
    val activePeers = peers.filter { !it.blocked }
    var selectedPeerId by remember(activePeers) {
        mutableStateOf(activePeers.firstOrNull()?.deviceId)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val peerId = selectedPeerId
        if (uri != null && peerId != null) {
            onSendFile(uri, peerId)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val peerId = selectedPeerId
        if (uri != null && peerId != null) {
            onSendFile(uri, peerId)
        }
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
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Offline Chunked File Sharing",
                        subtitle = "Zero-permission Photo Picker & SAF • 32KB chunks • SHA-256 integrity"
                    )
                    Spacer(Modifier.height(10.dp))
                    if (activePeers.isEmpty()) {
                        Text(
                            text = "Pair or discover a peer first to choose a file recipient.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshAmber
                        )
                    } else {
                        Text("Recipient Peer:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activePeers.take(4).forEach { peer ->
                                FilterChip(
                                    selected = selectedPeerId == peer.deviceId,
                                    onClick = { selectedPeerId = peer.deviceId },
                                    label = { Text(peer.alias) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            enabled = selectedPeerId != null,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("pick_media_button")
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Photo / Video")
                        }
                        OutlinedButton(
                            onClick = { documentPicker.launch(arrayOf("*/*")) },
                            enabled = selectedPeerId != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("pick_doc_button")
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Docs / ZIP")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle(title = "Recent Transfers (${transfers.size})")
                    OutlinedButton(onClick = onOpenTransferManager) {
                        Text("Queue Manager")
                    }
                }
            }

            items(transfers.take(10), key = { it.transferId }) { xfer ->
                TransferCard(xfer = xfer, onPause = {}, onResume = {}, showControls = false)
            }
        }
    }
}

@Composable
fun TransferManagerScreen(
    transfers: List<FileTransferEntity>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    val activeCount = transfers.count { it.status == "TRANSFERRING" }
    val verifiedCount = transfers.count { it.status == "VERIFIED" || it.status == "COMPLETED" }
    val attentionCount = transfers.count { it.status == "PAUSED" || it.status == "FAILED" }
    val visibleTransfers = remember(transfers, searchQuery, selectedFilter) {
        transfers.filter { transfer ->
            val query = searchQuery.trim()
            val matchesQuery = query.isBlank() ||
                transfer.fileName.contains(query, ignoreCase = true) ||
                transfer.peerAlias.contains(query, ignoreCase = true) ||
                transfer.transportType.contains(query, ignoreCase = true) ||
                transfer.status.contains(query, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "ACTIVE" -> transfer.status == "TRANSFERRING"
                "ATTENTION" -> transfer.status == "PAUSED" || transfer.status == "FAILED"
                "VERIFIED" -> transfer.status == "VERIFIED" || transfer.status == "COMPLETED"
                else -> true
            }
            matchesQuery && matchesFilter
        }
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
                GlassPanel(accentColor = NeonCyan) {
                    SectionTitle(
                        title = "Transfer Queue & Checkpoints",
                        subtitle = "Live database progress • acknowledged chunks • end-to-end SHA-256 verification"
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransferMetric("Active", activeCount.toString(), NeonCyan, Modifier.weight(1f))
                        TransferMetric("Verified", verifiedCount.toString(), MeshEmerald, Modifier.weight(1f))
                        TransferMetric("Attention", attentionCount.toString(), MeshAmber, Modifier.weight(1f))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(120) },
                    modifier = Modifier.fillMaxWidth().testTag("transfer_search"),
                    singleLine = true,
                    label = { Text("Search transfers") },
                    placeholder = { Text("File name, peer, transport or status") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            androidx.compose.material3.TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear")
                            }
                        }
                    }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL", "ACTIVE", "ATTENTION", "VERIFIED").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = {
                                Text(
                                    when (filter) {
                                        "ATTENTION" -> "Needs attention"
                                        else -> filter.lowercase().replaceFirstChar { it.uppercase() }
                                    }
                                )
                            },
                            modifier = Modifier.testTag("transfer_filter_$filter")
                        )
                    }
                }
            }
            if (visibleTransfers.isEmpty()) {
                item {
                    GlassPanel(accentColor = ElectricTeal) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.CloudUpload,
                                contentDescription = null,
                                tint = ElectricTeal,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (transfers.isEmpty()) "No file transfers yet" else "No matching transfers",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (transfers.isEmpty()) "Choose a verified peer in Files to send a document or media." else "Try another search or filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(visibleTransfers, key = { it.transferId }) { xfer ->
                    TransferCard(
                        xfer = xfer,
                        onPause = { onPause(xfer.transferId) },
                        onResume = { onResume(xfer.transferId) },
                        showControls = true
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(CircleShape.copy(all = false))
            .background(color.copy(alpha = 0.09f))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransferCard(
    xfer: FileTransferEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    showControls: Boolean
) {
    val progress = (xfer.completedChunks.toFloat() / xfer.totalChunks.coerceAtLeast(1).toFloat())
        .coerceIn(0f, 1f)
    val isVerified = xfer.status == "VERIFIED" || xfer.status == "COMPLETED"
    val statusColor = when (xfer.status) {
        "VERIFIED", "COMPLETED" -> MeshEmerald
        "PAUSED" -> MeshAmber
        "FAILED" -> MeshCrimson
        else -> NeonCyan
    }
    val completedBytes = (xfer.totalBytes * progress).toLong().coerceIn(0L, xfer.totalBytes)
    GlassPanel(accentColor = statusColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(xfer.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${if (xfer.isOutgoing) "Sending to" else "Received from"} ${xfer.peerAlias}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MeshPillBadge(
                text = xfer.status,
                color = statusColor,
                icon = if (isVerified) Icons.Filled.Verified else null
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().testTag("transfer_progress_${xfer.transferId}"),
            color = statusColor
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${formatTransferBytes(completedBytes)} / ${formatTransferBytes(xfer.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${(progress * 100).toInt()}% • ${xfer.completedChunks.coerceIn(0, xfer.totalChunks)}/${xfer.totalChunks} chunks",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${xfer.transportType} • ${xfer.mimeType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (isVerified) "SHA-256 verified by the receiving peer"
                    else "SHA-256: ${xfer.sha256.take(18)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isVerified) MeshEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showControls && xfer.isOutgoing && !isVerified) {
                when (xfer.status) {
                    "TRANSFERRING" -> IconButton(
                        onClick = onPause,
                        modifier = Modifier.testTag("pause_transfer_${xfer.transferId}")
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause transfer", tint = MeshAmber)
                    }
                    "PAUSED", "FAILED" -> IconButton(
                        onClick = onResume,
                        modifier = Modifier.testTag("resume_transfer_${xfer.transferId}")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume transfer", tint = MeshEmerald)
                    }
                }
            }
        }
        if (xfer.status == "FAILED" && !xfer.isOutgoing) {
            Text(
                "This received file failed integrity checks. Ask the sender to send it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshCrimson
            )
        }
    }
}

private fun formatTransferBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "${bytes / 1024.0.let { "%.1f".format(java.util.Locale.ROOT, it) }} KB"
    if (bytes < 1024L * 1024L * 1024L) return "${bytes / (1024.0 * 1024.0).let { "%.1f".format(java.util.Locale.ROOT, it) }} MB"
    return "${bytes / (1024.0 * 1024.0 * 1024.0).let { "%.2f".format(java.util.Locale.ROOT, it) }} GB"
}

@Composable
fun VoiceScreen(
    peers: List<PeerEntity>,
    isRecording: Boolean,
    amplitude: Float,
    playingClipId: String?,
    clips: List<VoiceRecordingItem>,
    onStartPtt: () -> Boolean,
    onStopPtt: (String?) -> Unit,
    onPlayClip: (VoiceRecordingItem) -> Unit
) {
    val activePeers = peers.filter { !it.blocked }
    var targetPeerId by remember(activePeers) { mutableStateOf(activePeers.firstOrNull()?.deviceId) }
    val waveColor = if (isRecording) MeshCrimson else NeonCyan

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassPanel(accentColor = waveColor) {
                    SectionTitle(
                        title = "Push-to-Talk & Voice Mesh",
                        subtitle = "16kHz PCM AudioRecord • E2EE store-and-forward voice packets"
                    )
                    Spacer(Modifier.height(8.dp))
                    if (activePeers.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activePeers.take(4).forEach { peer ->
                                FilterChip(
                                    selected = targetPeerId == peer.deviceId,
                                    onClick = { targetPeerId = peer.deviceId },
                                    label = { Text(peer.alias) }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No peer selected — recordings save locally until a peer connects.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        val bars = 28
                        val step = size.width / bars
                        val mid = size.height / 2f
                        for (i in 0 until bars) {
                            val factor = if (isRecording) {
                                (0.2f + amplitude * 0.8f * (0.5f + 0.5f * sin(i * 0.9f))).coerceIn(0.12f, 1f)
                            } else {
                                0.12f
                            }
                            val h = size.height * factor * 0.45f
                            drawLine(
                                color = waveColor,
                                start = Offset(i * step + step / 2f, mid - h),
                                end = Offset(i * step + step / 2f, mid + h),
                                strokeWidth = 6f
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRecording) MeshCrimson.copy(alpha = 0.25f)
                                    else NeonCyan.copy(alpha = 0.18f)
                                )
                                .pointerInput(targetPeerId) {
                                    detectTapGestures(
                                        onPress = {
                                            if (onStartPtt()) {
                                                tryAwaitRelease()
                                                onStopPtt(targetPeerId)
                                            }
                                        }
                                    )
                                }
                                .testTag("voice_ptt_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = "Hold to transmit voice",
                                tint = if (isRecording) MeshCrimson else NeonCyan,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isRecording) "TRANSMITTING ENCRYPTED AUDIO..." else "Hold mic to record & transmit",
                        style = MaterialTheme.typography.labelMedium,
                        color = waveColor,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            item {
                SectionTitle(title = "Voice Clips (${clips.size})")
            }

            items(clips, key = { it.id }) { clip ->
                GlassPanel(accentColor = ElectricTeal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = ElectricTeal)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "${if (clip.isOutgoing) "Sent to" else "From"} ${clip.peerOrChannel}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${clip.durationMs / 1000.0}s PCM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { onPlayClip(clip) }) {
                            Icon(
                                imageVector = if (playingClipId == clip.id) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                                contentDescription = "Play voice clip",
                                tint = NeonCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationScreen(
    fix: LocalGpsFix?,
    isTracking: Boolean,
    peers: List<PeerEntity>,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onComputeVector: (PeerEntity) -> RelativeVector?,
    onShareLocation: (String?, Boolean) -> Unit
) {
    val peersWithLoc = peers.filter { it.lastLat != null && it.lastLon != null }
    val radarAccent = NeonCyan
    val peerAccent = ElectricTeal

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
                        title = "Offline Tactical GPS & Mesh Radar",
                        subtitle = "Pure hardware GPS without internet map tiles"
                    )
                    Spacer(Modifier.height(10.dp))
                    if (fix != null) {
                        Text(
                            text = "%.6f, %.6f".format(fix.latitude, fix.longitude),
                            style = MaterialTheme.typography.headlineSmall,
                            color = NeonCyan
                        )
                        Text(
                            text = "Accuracy ±%.1fm • Alt %.1fm • Provider %s".format(
                                fix.accuracyMeters,
                                fix.altitudeMeters,
                                fix.provider
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = if (isTracking) "Acquiring satellite/network fix..." else "GPS tracking paused",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshAmber
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (isTracking) onStopTracking() else onStartTracking() },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("location_track_button")
                        ) {
                            Icon(Icons.Filled.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isTracking) "Pause GPS" else "Acquire Fix")
                        }
                        OutlinedButton(
                            onClick = { onShareLocation(null, false) },
                            enabled = fix != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("location_share_button")
                        ) {
                            Text("Share Mesh")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onShareLocation(null, true) },
                        enabled = fix != null,
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCrimson),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("location_sos_button")
                    ) {
                        Icon(Icons.Filled.MedicalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Broadcast Emergency Location Beacon")
                    }
                }
            }

            item {
                GlassPanel(accentColor = ElectricTeal) {
                    Text("Offline Bearing Compass", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxR = minOf(size.width, size.height) * 0.44f
                        listOf(0.33f, 0.66f, 1.0f).forEach { r ->
                            drawCircle(
                                color = radarAccent.copy(alpha = 0.25f),
                                radius = maxR * r,
                                center = center,
                                style = Stroke(width = 2f)
                            )
                        }
                        drawLine(
                            color = radarAccent.copy(alpha = 0.25f),
                            start = Offset(center.x - maxR, center.y),
                            end = Offset(center.x + maxR, center.y)
                        )
                        drawLine(
                            color = radarAccent.copy(alpha = 0.25f),
                            start = Offset(center.x, center.y - maxR),
                            end = Offset(center.x, center.y + maxR)
                        )
                        drawCircle(color = radarAccent, radius = 10f, center = center)

                        peersWithLoc.forEach { peer ->
                            val vec = onComputeVector(peer)
                            if (vec != null) {
                                val distRatio = (vec.distanceMeters / 1000f).coerceIn(0.15f, 0.95f)
                                val rad = (vec.bearingDegrees - 90f) * (PI / 180.0)
                                val px = center.x + (maxR * distRatio * cos(rad)).toFloat()
                                val py = center.y + (maxR * distRatio * sin(rad)).toFloat()
                                drawCircle(color = peerAccent, radius = 12f, center = Offset(px, py))
                            }
                        }
                    }
                }
            }

            items(peersWithLoc, key = { it.deviceId }) { peer ->
                val vec = onComputeVector(peer)
                GlassPanel(accentColor = ElectricTeal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(peer.alias, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "%.5f, %.5f".format(peer.lastLat, peer.lastLon),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (vec != null) {
                            MeshPillBadge(
                                text = "%.0fm @ %.0f°".format(vec.distanceMeters, vec.bearingDegrees),
                                color = ElectricTeal
                            )
                        }
                    }
                }
            }
        }
    }
}
