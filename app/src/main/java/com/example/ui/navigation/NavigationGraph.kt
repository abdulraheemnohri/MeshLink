package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.ui.screens.ChatsHubScreen
import com.example.ui.screens.ConnectionsScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.EmergencyModeScreen
import com.example.ui.screens.FileSharingScreen
import com.example.ui.screens.GroupChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.IdentityScreen
import com.example.ui.screens.LocationScreen
import com.example.ui.screens.LockScreen
import com.example.ui.screens.NetworkBridgeScreen
import com.example.ui.screens.NetworkMapScreen
import com.example.ui.screens.PrivateChatScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.SecurityScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TransferManagerScreen
import com.example.ui.screens.VoiceScreen
import com.example.ui.viewmodel.MeshViewModel

@Composable
fun NavigationGraph(
    navController: NavHostController,
    viewModel: MeshViewModel,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val discoveredEndpoints by viewModel.discoveredEndpoints.collectAsStateWithLifecycle()
    val transportHealth by viewModel.transportHealth.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val emergencyBroadcasts by viewModel.emergencyBroadcasts.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val pendingPackets by viewModel.pendingPackets.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val securityEvents by viewModel.securityEvents.collectAsStateWithLifecycle()
    val networkEvents by viewModel.networkEvents.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val userSettings by viewModel.userSettings.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val transportPolicy by viewModel.transportPolicy.collectAsStateWithLifecycle()
    val isRelayRunning by viewModel.isRelayRunning.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val loopbackResult by viewModel.loopbackResult.collectAsStateWithLifecycle()
    val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()

    val isRecordingVoice by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val voiceAmplitude by viewModel.voiceAmplitude.collectAsStateWithLifecycle()
    val playingClipId by viewModel.playingClipId.collectAsStateWithLifecycle()
    val voiceClips by viewModel.voiceClips.collectAsStateWithLifecycle()

    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val isTrackingLocation by viewModel.isTrackingLocation.collectAsStateWithLifecycle()

    LaunchedEffect(isAppLocked) {
        if (isAppLocked && navController.currentDestination?.route != MeshRoute.LOCK) {
            navController.navigate(MeshRoute.LOCK) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isAppLocked) MeshRoute.LOCK else MeshRoute.DASHBOARD,
        modifier = modifier
    ) {
        composable(MeshRoute.LOCK) {
            LockScreen(
                biometricManager = viewModel.biometricManager,
                promptTitle = userSettings.biometricPromptTitle,
                promptSubtitle = userSettings.biometricPromptSubtitle,
                onUnlocked = {
                    viewModel.unlockApp()
                    navController.navigate(MeshRoute.DASHBOARD) {
                        popUpTo(MeshRoute.LOCK) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(MeshRoute.DASHBOARD) {
            HomeScreen(
                identity = identity,
                peers = peers,
                pendingPackets = pendingPackets,
                transfers = transfers,
                settings = settings,
                capabilities = capabilities,
                isRelayRunning = isRelayRunning,
                onToggleRelay = { enable ->
                    viewModel.updateSettings { it.copy(backgroundRelayEnabled = enable) }
                    if (enable) viewModel.startRelayService() else viewModel.stopRelayService()
                },
                onScanNow = { viewModel.triggerDiscoveryScan() },
                onToggleEmergency = { viewModel.triggerEmergencyModeBroadcast() },
                onNavigate = onNavigateTopLevel
            )
        }

        composable(MeshRoute.DISCOVERY) {
            DiscoverScreen(
                peers = peers,
                discoveredEndpoints = discoveredEndpoints,
                onScanNow = { viewModel.triggerDiscoveryScan() },
                onConnectPeer = { peer -> viewModel.connectPeer(peer) },
                onConnectEndpoint = { endpoint -> viewModel.connectEndpoint(endpoint) },
                onOpenChat = { peerId ->
                    navController.navigate(MeshRoute.privateChat(peerId))
                },
                onImportPeerUri = { uri -> viewModel.importPeerFromUri(uri) }
            )
        }

        composable(MeshRoute.CONNECTIONS) {
            ConnectionsScreen(
                peers = peers,
                connections = connections,
                onDisconnect = { peerId -> viewModel.disconnectPeer(peerId) },
                onToggleVerify = { peer -> viewModel.togglePeerVerified(peer) },
                onToggleBlock = { peer -> viewModel.togglePeerBlocked(peer) }
            )
        }

        composable(MeshRoute.NETWORK_MAP) {
            NetworkMapScreen(
                identity = identity,
                peers = peers,
                routes = routes,
                settings = settings
            )
        }

        composable(MeshRoute.BRIDGE) {
            NetworkBridgeScreen(
                settings = settings,
                networkEvents = networkEvents,
                onUpdateSettings = { fn -> viewModel.updateSettings(fn) }
            )
        }

        composable(MeshRoute.EMERGENCY) {
            EmergencyModeScreen(
                settings = settings,
                currentLocation = currentLocation,
                broadcasts = emergencyBroadcasts,
                onToggleEmergencyActive = { active ->
                    viewModel.updateSettings { it.copy(emergencyModeActive = active) }
                },
                onSendEmergencyBroadcast = { msg, attachGps ->
                    viewModel.sendEmergencyBroadcast(msg, attachGps)
                }
            )
        }

        composable(MeshRoute.CHATS) {
            ChatsHubScreen(
                peers = peers,
                groups = groups,
                allMessages = allMessages,
                onOpenPrivateChat = { peerId ->
                    navController.navigate(MeshRoute.privateChat(peerId))
                },
                onOpenGroupChat = { groupId ->
                    navController.navigate(MeshRoute.groupChat(groupId))
                },
                onCreateGroup = { name, desc, category, members ->
                    viewModel.createGroup(name, desc, category, members)
                },
                onJoinGroupByUri = { uri ->
                    viewModel.joinGroupFromUri(uri)
                }
            )
        }

        composable(
            route = MeshRoute.PRIVATE_CHAT,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { entry ->
            val peerId = entry.arguments?.getString("peerId") ?: ""
            val peer = peers.firstOrNull { it.deviceId == peerId }
            val messages by viewModel.observeConversation(peerId)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            PrivateChatScreen(
                peer = peer,
                messages = messages,
                onSendMessage = { text, strategy, ttl ->
                    viewModel.sendPrivateMessage(peerId, text, strategy, ttl)
                },
                onReact = { msg, emoji -> viewModel.toggleReaction(msg, emoji) }
            )
        }

        composable(
            route = MeshRoute.GROUP_CHAT,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { entry ->
            val groupId = entry.arguments?.getString("groupId") ?: ""
            val group = groups.firstOrNull { it.groupId == groupId }
            val members by viewModel.observeGroupMembers(groupId)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val messages by viewModel.observeConversation(groupId)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            GroupChatScreen(
                group = group,
                members = members,
                allPeers = peers,
                inviteUri = group?.let { viewModel.buildGroupInviteUri(it) } ?: "",
                messages = messages,
                onSendMessage = { text -> viewModel.sendGroupMessage(groupId, text) },
                onInvitePeer = { peer -> viewModel.invitePeerToGroup(groupId, peer) },
                onRemoveMember = { peerId -> viewModel.removeGroupMember(groupId, peerId) },
                onToggleRole = { member -> viewModel.toggleGroupMemberRole(groupId, member) },
                onRotateKey = { viewModel.rotateGroupKey(groupId) },
                onReact = { msg, emoji -> viewModel.toggleReaction(msg, emoji) }
            )
        }

        composable(MeshRoute.FILES) {
            FileSharingScreen(
                peers = peers,
                transfers = transfers,
                onSendFile = { uri, peerId -> viewModel.sendFileToPeer(uri, peerId) },
                onOpenTransferManager = { onNavigateTopLevel(MeshRoute.TRANSFERS) }
            )
        }

        composable(MeshRoute.TRANSFERS) {
            TransferManagerScreen(
                transfers = transfers,
                onPause = { id -> viewModel.pauseTransfer(id) },
                onResume = { id -> viewModel.resumeTransfer(id) }
            )
        }

        composable(MeshRoute.VOICE) {
            VoiceScreen(
                peers = peers,
                isRecording = isRecordingVoice,
                amplitude = voiceAmplitude,
                playingClipId = playingClipId,
                clips = voiceClips,
                onStartPtt = { viewModel.startPushToTalk() },
                onStopPtt = { peerId -> viewModel.stopPushToTalk(peerId) },
                onPlayClip = { clip -> viewModel.playVoiceClip(clip) }
            )
        }

        composable(MeshRoute.LOCATION) {
            LocationScreen(
                fix = currentLocation,
                isTracking = isTrackingLocation,
                peers = peers,
                onStartTracking = { viewModel.startLocationTracking() },
                onStopTracking = { viewModel.stopLocationTracking() },
                onComputeVector = { peer -> viewModel.computeVectorToPeer(peer) },
                onShareLocation = { targetPeerId, isEmergency ->
                    viewModel.broadcastLocation(targetPeerId, isEmergency)
                }
            )
        }

        composable(MeshRoute.IDENTITY) {
            IdentityScreen(
                identity = identity,
                identityUri = viewModel.buildLocalIdentityUri(),
                capabilities = capabilities,
                onUpdateAlias = { alias -> viewModel.updateAlias(alias) },
                onImportPeerUri = { uri -> viewModel.importPeerFromUri(uri) }
            )
        }

        composable(MeshRoute.SECURITY) {
            SecurityScreen(
                settings = settings,
                userSettings = userSettings,
                peers = peers,
                securityEvents = securityEvents,
                onUpdateSettings = { fn -> viewModel.updateSettings(fn) },
                onToggleBiometric = { enabled -> viewModel.setBiometricLockEnabled(enabled) },
                onLockNow = { viewModel.lockApp() },
                onToggleBlock = { peer -> viewModel.togglePeerBlocked(peer) }
            )
        }

        composable(MeshRoute.ROUTING) {
            RoutingScreen(
                settings = settings,
                routes = routes,
                pendingPackets = pendingPackets,
                onUpdateSettings = { fn -> viewModel.updateSettings(fn) },
                onFlushQueue = { viewModel.flushQueueNow() },
                onPurgeExpired = { viewModel.purgeExpiredNow() }
            )
        }

        composable(MeshRoute.DIAGNOSTICS) {
            DiagnosticsScreen(
                capabilities = capabilities,
                transportHealth = transportHealth,
                isRelayRunning = isRelayRunning,
                loopbackResult = loopbackResult,
                networkEvents = networkEvents,
                onRefreshCapabilities = { viewModel.refreshCapabilities() },
                onRunLoopbackTest = { viewModel.runLoopbackSelfTest() },
                onClearLogs = { viewModel.clearNetworkLogs() }
            )
        }

        composable(MeshRoute.PROFILE) {
            ProfileScreen(
                profile = profile,
                onProfileChange = { viewModel.updateProfileDraft(it) },
                onSave = { viewModel.saveProfile(profile) }
            )
        }

        composable(MeshRoute.SETTINGS) {
            SettingsScreen(
                settings = settings,
                userSettings = userSettings,
                isRelayRunning = isRelayRunning,
                onToggleRelay = { enable ->
                    viewModel.updateSettings { it.copy(backgroundRelayEnabled = enable) }
                    if (enable) viewModel.startRelayService() else viewModel.stopRelayService()
                },
                onToggleBiometric = { enabled -> viewModel.setBiometricLockEnabled(enabled) },
                onLockNow = { viewModel.lockApp() },
                transportPolicy = transportPolicy,
                onUpdateTransportPolicy = { viewModel.updateTransportPolicy(it) },
                onUpdateSettings = { fn -> viewModel.updateSettings(fn) }
            )
        }
    }
}
