package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.protocol.RoutingStrategy
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.PeerEntity
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.PulseStatusDot
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatsHubScreen(
    peers: List<PeerEntity>,
    groups: List<GroupEntity>,
    allMessages: List<MessageEntity>,
    onOpenPrivateChat: (String) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    onCreateGroup: (String, String, String, List<PeerEntity>) -> Unit,
    onJoinGroupByUri: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Direct (${peers.count { !it.blocked }})") },
                    modifier = Modifier.testTag("tab_direct_chats")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Groups (${groups.size})") },
                    modifier = Modifier.testTag("tab_group_chats")
                )
            }

            if (selectedTab == 0) {
                val activePeers = peers.filter { !it.blocked }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activePeers.isEmpty()) {
                        item {
                            GlassPanel(accentColor = NeonCyan) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ChatBubbleOutline,
                                        contentDescription = "No direct conversations",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "No peers paired yet",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Discover a nearby MeshLink phone or import a peer QR URI to begin E2EE offline messaging.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(activePeers, key = { it.deviceId }) { peer ->
                            val lastMsg = allMessages.firstOrNull { it.conversationId == peer.deviceId }
                            GlassPanel(
                                accentColor = if (peer.isConnected) MeshEmerald else ElectricTeal,
                                modifier = Modifier
                                    .clickable { onOpenPrivateChat(peer.deviceId) }
                                    .testTag("open_chat_${peer.deviceId}")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        PulseStatusDot(active = peer.isConnected)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = peer.alias,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                if (peer.verifiedByQr) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.VerifiedUser,
                                                        contentDescription = "Verified",
                                                        tint = MeshEmerald,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = lastMsg?.body ?: "Tap to start E2EE chat (${peer.activeTransport})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    if (lastMsg != null) {
                                        MeshPillBadge(
                                            text = lastMsg.deliveryStatus,
                                            color = if (lastMsg.deliveryStatus == "DELIVERED") MeshEmerald else MeshAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showJoinGroupDialog = true },
                                    enabled = false,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("join_group_uri_button")
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Join Invite URI")
                                }
                                Button(
                                    onClick = { showCreateGroupDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("create_group_top_button")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("New Community")
                                }
                            }
                        }

                        if (groups.isEmpty()) {
                            item {
                                GlassPanel(accentColor = ElectricTeal) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Group,
                                            contentDescription = "No groups",
                                            tint = ElectricTeal,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "No Community Groups Yet",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "Create an encrypted AES-256 community or event channel with offline message storage and invite codes.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(groups, key = { it.groupId }) { group ->
                                val lastMsg = allMessages.firstOrNull { it.conversationId == group.groupId }
                                GlassPanel(
                                    accentColor = ElectricTeal,
                                    modifier = Modifier
                                        .clickable { onOpenGroupChat(group.groupId) }
                                        .testTag("open_group_${group.groupId}")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                                MeshPillBadge(group.category, ElectricTeal)
                                            }
                                            Text(
                                                text = lastMsg?.body ?: group.description.ifBlank { "Invite ${group.inviteCode} • Epoch ${group.keyEpoch}" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        MeshPillBadge("Epoch ${group.keyEpoch}", NeonCyan, Icons.Filled.Key)
                                    }
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = { showCreateGroupDialog = true },
                        containerColor = NeonCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                            .testTag("create_group_fab")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create community group")
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        var groupDesc by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("COMMUNITY") }
        val selectedMembers = remember { mutableStateListOf<PeerEntity>() }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("Create Community / Event Group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("COMMUNITY", "EVENT", "NEIGHBORHOOD", "RESCUE").forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupDesc,
                        onValueChange = { groupDesc = it },
                        label = { Text("Event / Community Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (peers.isNotEmpty()) {
                        Text("Invite Peers:", style = MaterialTheme.typography.labelMedium)
                        peers.filter { !it.blocked }.take(5).forEach { peer ->
                            val checked = selectedMembers.contains(peer)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedMembers.remove(peer) else selectedMembers.add(peer)
                                    }
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(peer.alias, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateGroup(groupName, groupDesc, category, selectedMembers.toList())
                        showCreateGroupDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showJoinGroupDialog) {
        var inviteDraft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinGroupDialog = false },
            title = { Text("Join Community Group") },
            text = {
                OutlinedTextField(
                    value = inviteDraft,
                    onValueChange = { inviteDraft = it },
                    label = { Text("Paste meshlink://group?...") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onJoinGroupByUri(inviteDraft)
                        showJoinGroupDialog = false
                    }
                ) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinGroupDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivateChatScreen(
    peer: PeerEntity?,
    messages: List<MessageEntity>,
    onSendMessage: (String, RoutingStrategy, Int) -> Unit,
    onReact: (MessageEntity, String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var selectedStrategy by remember { mutableStateOf(RoutingStrategy.AUTONOMOUS) }
    var ttlHours by remember { mutableIntStateOf(24) }
    val messageListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .imePadding()
        ) {
            // Security & Routing Strip
            GlassPanel(accentColor = if (peer?.verifiedByQr == true) MeshEmerald else NeonCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "End-to-end encrypted",
                            tint = MeshEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "ECDH + AES-256-GCM • ${peer?.activeTransport ?: "OFFLINE"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = "Expiry",
                            tint = MeshAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "TTL ${ttlHours}h",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshAmber,
                            modifier = Modifier.clickable {
                                ttlHours = when (ttlHours) {
                                    1 -> 12
                                    12 -> 24
                                    24 -> 72
                                    else -> 1
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        RoutingStrategy.AUTONOMOUS,
                        RoutingStrategy.DIRECT,
                        RoutingStrategy.STORE_AND_FORWARD,
                        RoutingStrategy.EPIDEMIC,
                        RoutingStrategy.SPRAY_AND_WAIT
                    ).forEach { strat ->
                        FilterChip(
                            selected = selectedStrategy == strat,
                            onClick = { selectedStrategy = strat },
                            label = { Text(strat.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = messageListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Messages are signed & end-to-end encrypted. Offline messages remain queued in Store & Forward until delivery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(messages, key = { it.messageId }) { msg ->
                        MessageBubble(msg = msg, onReact = { emoji -> onReact(msg, emoji) })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4000) },
                    placeholder = { Text("Encrypted mesh message...") },
                    supportingText = { Text("${draft.length}/4000") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("private_chat_input"),
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = draft.isNotBlank() && peer != null,
                    onClick = {
                        val outgoing = draft.trim()
                        if (outgoing.isNotEmpty()) onSendMessage(outgoing, selectedStrategy, ttlHours)
                        draft = ""
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(NeonCyan)
                        .testTag("private_chat_send")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send encrypted message",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun GroupChatScreen(
    group: GroupEntity?,
    members: List<GroupMemberEntity>,
    allPeers: List<PeerEntity>,
    inviteUri: String,
    messages: List<MessageEntity>,
    onSendMessage: (String) -> Unit,
    onInvitePeer: (PeerEntity) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleRole: (GroupMemberEntity) -> Unit,
    onRotateKey: () -> Unit,
    onRetryKeyUpdates: () -> Unit,
    onReact: (MessageEntity, String) -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    val messageListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .imePadding()
        ) {
            GlassPanel(accentColor = ElectricTeal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${group?.name ?: "Group"} • ${members.size} members",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${group?.category ?: "COMMUNITY"} • Epoch ${group?.keyEpoch ?: 1} • Offline AES-256",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(
                            onClick = { showInviteDialog = true },
                            modifier = Modifier.testTag("group_invite_qr_button")
                        ) {
                            Icon(Icons.Filled.QrCode2, contentDescription = "Group invitation security notice", tint = NeonCyan)
                        }
                        IconButton(
                            onClick = { showMembersDialog = true },
                            modifier = Modifier.testTag("group_members_button")
                        ) {
                            Icon(Icons.Filled.Group, contentDescription = "Manage Members", tint = ElectricTeal)
                        }
                        IconButton(onClick = onRotateKey) {
                            Icon(Icons.Filled.Key, contentDescription = "Rotate Group Key", tint = MeshAmber)
                        }
                        IconButton(onClick = onRetryKeyUpdates) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Retry Pending Group Key Updates", tint = ElectricTeal)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = messageListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    MessageBubble(msg = msg, onReact = { emoji -> onReact(msg, emoji) })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4000) },
                    placeholder = { Text("Broadcast to encrypted community...") },
                    supportingText = { Text("${draft.length}/4000") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("group_chat_input"),
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = draft.isNotBlank() && group != null,
                    onClick = {
                        val outgoing = draft.trim()
                        if (outgoing.isNotEmpty()) onSendMessage(outgoing)
                        draft = ""
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElectricTeal)
                        .testTag("group_chat_send")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send group message",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }
    }

    if (showInviteDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Invite to ${group.name}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Secure invitation required",
                        tint = MeshAmber,
                        modifier = Modifier.size(38.dp)
                    )
                    Text("Group QR invitations are temporarily disabled", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "The old QR format exposed the group encryption key to anyone who copied or photographed it. Invite a trusted peer from Manage Members instead. A recipient-specific encrypted QR invitation will return after secure key delivery is implemented.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Group ID: ${group.groupId} • Key epoch: ${group.keyEpoch}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("Done") }
            }
        )
    }

    if (showMembersDialog) {
        val memberIds = members.map { it.peerId }.toSet()
        val inviteCandidates = allPeers.filter { it.verifiedByQr && !it.blocked && !memberIds.contains(it.deviceId) }
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            title = { Text("Members (${members.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Role changes are disabled until role updates can be synchronized and acknowledged by every group member.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    members.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.peerAlias, style = MaterialTheme.typography.bodyMedium)
                                Text(member.role, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                            }
                            Row {
                                IconButton(onClick = { onToggleRole(member) }, enabled = false) {
                                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Toggle role", tint = ElectricTeal)
                                }
                                IconButton(onClick = { onRemoveMember(member.peerId) }) {
                                    Icon(Icons.Filled.PersonRemove, contentDescription = "Remove member", tint = MeshCrimson)
                                }
                            }
                        }
                    }
                    if (inviteCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Invite Paired Peers:", style = MaterialTheme.typography.labelMedium)
                        inviteCandidates.take(4).forEach { peer ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(peer.alias, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { onInvitePeer(peer) }) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = "Invite peer", tint = MeshEmerald)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMembersDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MessageBubble(
    msg: MessageEntity,
    onReact: (String) -> Unit
) {
    val timeStr = remember(msg.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt))
    }
    val bubbleColor = when {
        msg.messageKind == "EMERGENCY" -> MeshCrimson.copy(alpha = 0.22f)
        msg.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isOutgoing) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = if (msg.isOutgoing) 18.dp else 6.dp, topEnd = if (msg.isOutgoing) 6.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(bubbleColor)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!msg.isOutgoing) {
                Text(
                    text = msg.senderAlias,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan
                )
            }
            Text(
                text = msg.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$timeStr • ${msg.deliveryStatus}${if (msg.hopCount > 0) " (${msg.hopCount}h)" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("👍", "🔥", "✅", "🚨").forEach { emoji ->
                    val active = msg.reactionsCsv.split(",").contains(emoji)
                    Text(
                        text = emoji,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) NeonCyan.copy(alpha = 0.28f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                            )
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
