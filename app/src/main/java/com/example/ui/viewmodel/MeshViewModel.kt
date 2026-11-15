package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MeshLinkApplication
import com.example.background.MeshRelayService
import com.example.core.permissions.HardwareCapabilities
import com.example.core.permissions.HardwareCapabilityReport
import com.example.core.protocol.RoutingStrategy
import com.example.data.entities.ConnectionEntity
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import com.example.data.entities.UserSettings
import com.example.location.LocalGpsFix
import com.example.location.RelativeVector
import com.example.security.BiometricManager
import com.example.voice.VoiceRecordingItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MeshLinkApplication).container
    private val repository = container.repository
    private val routingEngine = container.routingEngine
    private val crypto = container.crypto
    private val fileManager = container.fileTransferManager
    private val voiceManager = container.voiceAudioManager
    private val locationManager = container.locationSharingManager
    val biometricManager: BiometricManager = container.biometricManager

    val identity: StateFlow<DeviceIdentityEntity?> = repository.identityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val peers: StateFlow<List<PeerEntity>> = repository.peersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connections: StateFlow<List<ConnectionEntity>> = repository.connectionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<MessageEntity>> = repository.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> = repository.groupsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emergencyBroadcasts: StateFlow<List<EmergencyBroadcastEntity>> = repository.emergencyBroadcastsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routes: StateFlow<List<RouteEntity>> = repository.routesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingPackets: StateFlow<List<PendingPacketEntity>> = repository.pendingPacketsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transfers: StateFlow<List<FileTransferEntity>> = repository.transfersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val securityEvents: StateFlow<List<SecurityEventEntity>> = repository.securityEventsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val networkEvents: StateFlow<List<NetworkEventEntity>> = repository.networkEventsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<SettingsEntity> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsEntity())

    val userSettings: StateFlow<UserSettings> = repository.userSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val isRelayRunning: StateFlow<Boolean> = container.isRelayRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isRecordingVoice: StateFlow<Boolean> = voiceManager.isRecording
    val voiceAmplitude: StateFlow<Float> = voiceManager.amplitude
    val playingClipId: StateFlow<String?> = voiceManager.playingClipId
    val voiceClips: StateFlow<List<VoiceRecordingItem>> = voiceManager.clips

    val currentLocation: StateFlow<LocalGpsFix?> = locationManager.currentFix
    val isTrackingLocation: StateFlow<Boolean> = locationManager.isTracking

    private val _capabilities = MutableStateFlow(HardwareCapabilities.inspect(application))
    val capabilities: StateFlow<HardwareCapabilityReport> = _capabilities.asStateFlow()

    private val _loopbackResult = MutableStateFlow<String?>(null)
    val loopbackResult: StateFlow<String?> = _loopbackResult.asStateFlow()

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private val _snackMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackMessages = _snackMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
            val currentSettings = repository.getSettingsOnce()
            val currentUserSettings = repository.getUserSettingsOnce()
            if (currentUserSettings.biometricLockEnabled || currentSettings.biometricLockEnabled) {
                _isAppLocked.value = true
            }
            if (currentSettings.onboardingCompleted && currentSettings.backgroundRelayEnabled) {
                startRelayService()
            }
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateUserSettings { it.copy(biometricLockEnabled = enabled) }
            repository.updateSettings { it.copy(biometricLockEnabled = enabled) }
            _snackMessages.tryEmit(
                if (enabled) "Biometric protection enabled" else "Biometric protection disabled"
            )
        }
    }

    fun lockApp() {
        _isAppLocked.value = true
    }

    fun refreshCapabilities() {
        _capabilities.value = HardwareCapabilities.inspect(getApplication())
    }

    fun unlockApp() {
        _isAppLocked.value = false
    }

    fun completeOnboarding(nodeAlias: String) {
        viewModelScope.launch {
            if (nodeAlias.isNotBlank()) {
                repository.updateLocalAlias(nodeAlias)
            }
            repository.updateSettings { it.copy(onboardingCompleted = true) }
            refreshCapabilities()
            startRelayService()
        }
    }

    fun updateAlias(alias: String) {
        viewModelScope.launch {
            repository.updateLocalAlias(alias)
            _snackMessages.tryEmit("Updated local node alias")
        }
    }

    fun startRelayService() {
        MeshRelayService.start(getApplication())
    }

    fun stopRelayService() {
        MeshRelayService.stop(getApplication())
    }

    fun triggerDiscoveryScan() {
        viewModelScope.launch {
            refreshCapabilities()
            routingEngine.triggerAllDiscovery()
            _snackMessages.tryEmit("Scanning BLE, Wi-Fi Direct, Wi-Fi Aware & Local LAN")
        }
    }

    fun connectPeer(peer: PeerEntity) {
        viewModelScope.launch {
            val ep = routingEngine.getDiscoveredEndpoints().firstOrNull { it.deviceId == peer.deviceId }
            if (ep != null) {
                val ok = routingEngine.sendHandshake(ep)
                _snackMessages.tryEmit(
                    if (ok) "Handshake sent to ${peer.alias}" else "Peer unreachable over active transport"
                )
            } else if (!peer.lastAddress.isNullOrBlank()) {
                _snackMessages.tryEmit("Queued reconnect check for ${peer.alias}")
                routingEngine.triggerAllDiscovery()
            } else {
                _snackMessages.tryEmit("${peer.alias} is offline — messages will use Store & Forward")
            }
        }
    }

    fun disconnectPeer(peerId: String) {
        viewModelScope.launch {
            repository.markPeerDisconnected(peerId)
            _snackMessages.tryEmit("Disconnected peer")
        }
    }

    fun togglePeerVerified(peer: PeerEntity) {
        viewModelScope.launch {
            repository.setPeerVerified(peer.deviceId, !peer.verifiedByQr)
        }
    }

    fun togglePeerBlocked(peer: PeerEntity) {
        viewModelScope.launch {
            repository.setPeerBlocked(peer.deviceId, !peer.blocked)
            _snackMessages.tryEmit(if (!peer.blocked) "Blocked ${peer.alias}" else "Unblocked ${peer.alias}")
        }
    }

    fun buildLocalIdentityUri(): String {
        val keys = crypto.ensureLocalIdentity()
        return crypto.encodeIdentityUri(keys)
    }

    fun importPeerFromUri(raw: String) {
        viewModelScope.launch {
            val payload = crypto.decodeIdentityUri(raw)
            if (payload == null) {
                _snackMessages.tryEmit("Invalid MeshLink QR / URI identity payload")
                return@launch
            }
            val local = repository.ensureInitialized()
            if (payload.deviceId == local.deviceId) {
                _snackMessages.tryEmit("That is your own local node identity")
                return@launch
            }
            repository.importVerifiedPeer(payload)
            _snackMessages.tryEmit("Verified & trusted ${payload.alias}")
        }
    }

    fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        repository.observeConversation(conversationId)

    fun observeGroupMembers(groupId: String): Flow<List<GroupMemberEntity>> =
        repository.observeGroupMembers(groupId)

    fun sendPrivateMessage(
        peerId: String,
        text: String,
        strategyOverride: RoutingStrategy? = null,
        ttlHoursOverride: Int? = null
    ) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ok = routingEngine.sendDirectTextMessage(
                peerId = peerId,
                plaintext = text.trim(),
                strategyOverride = strategyOverride,
                ttlHoursOverride = ttlHoursOverride
            )
            if (!ok) {
                _snackMessages.tryEmit("Encrypted & queued in Store-and-Forward DTN buffer")
            }
        }
    }

    fun sendGroupMessage(groupId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            routingEngine.sendGroupMessage(groupId, text.trim())
        }
    }

    fun toggleReaction(message: MessageEntity, emoji: String) {
        viewModelScope.launch {
            repository.toggleMessageReaction(message, emoji)
        }
    }

    fun createGroup(
        name: String,
        description: String,
        category: String = "COMMUNITY",
        members: List<PeerEntity>
    ) {
        viewModelScope.launch {
            val group = repository.createGroup(name, description, category, members)
            members.forEach { peer ->
                routingEngine.sendGroupInvite(group.groupId, peer.deviceId)
            }
            _snackMessages.tryEmit("Created $category group '${group.name}' & invited ${members.size} peer(s)")
        }
    }

    fun buildGroupInviteUri(group: GroupEntity): String {
        return crypto.encodeGroupInviteUri(
            groupId = group.groupId,
            name = group.name,
            description = group.description,
            category = group.category,
            groupKeyB64 = group.groupSymmetricKeyB64,
            epoch = group.keyEpoch,
            createdBy = group.createdBy
        )
    }

    fun joinGroupFromUri(rawUri: String) {
        viewModelScope.launch {
            val payload = crypto.decodeGroupInviteUri(rawUri)
            if (payload == null) {
                _snackMessages.tryEmit("Invalid MeshLink group invitation URI")
                return@launch
            }
            val joined = repository.joinGroupFromInvite(payload)
            _snackMessages.tryEmit("Joined community group '${joined.name}'")
        }
    }

    fun invitePeerToGroup(groupId: String, peer: PeerEntity) {
        viewModelScope.launch {
            val sent = routingEngine.sendGroupInvite(groupId, peer.deviceId)
            _snackMessages.tryEmit(
                if (sent) "Sent E2EE group invite to ${peer.alias}" else "Added ${peer.alias} & queued invite"
            )
        }
    }

    fun removeGroupMember(groupId: String, peerId: String) {
        viewModelScope.launch {
            repository.removeGroupMember(groupId, peerId)
            _snackMessages.tryEmit("Removed member from group")
        }
    }

    fun toggleGroupMemberRole(groupId: String, member: GroupMemberEntity) {
        viewModelScope.launch {
            val nextRole = if (member.role == "ADMIN") "MEMBER" else "ADMIN"
            repository.updateGroupMemberRole(groupId, member.peerId, nextRole)
        }
    }

    fun rotateGroupKey(groupId: String) {
        viewModelScope.launch {
            repository.rotateGroupKey(groupId)
            _snackMessages.tryEmit("Group AES-256 key rotated")
        }
    }

    fun sendEmergencyBroadcast(message: String, attachLocation: Boolean) {
        if (message.isBlank()) return
        viewModelScope.launch {
            val fix = if (attachLocation) currentLocation.value else null
            val rec = routingEngine.sendEmergencyBroadcast(
                body = message.trim(),
                lat = fix?.latitude,
                lon = fix?.longitude
            )
            repository.updateSettings { it.copy(emergencyModeActive = true) }
            _snackMessages.tryEmit("Emergency alert broadcast across ${rec.relayCount} link(s)")
        }
    }

    fun sendFileToPeer(uri: Uri, peerId: String) {
        viewModelScope.launch {
            val xfer = fileManager.queueOutgoingFile(uri, peerId)
            if (xfer != null) {
                _snackMessages.tryEmit("Queued ${xfer.fileName} (${xfer.totalChunks} chunks, SHA-256 verified)")
            } else {
                _snackMessages.tryEmit("Unable to read selected file")
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        viewModelScope.launch { fileManager.pauseTransfer(transferId) }
    }

    fun resumeTransfer(transferId: String) {
        viewModelScope.launch { fileManager.resumeTransfer(transferId) }
    }

    fun startPushToTalk(): Boolean {
        val started = voiceManager.startPushToTalk()
        if (!started) {
            _snackMessages.tryEmit("Microphone permission required for Push-to-Talk")
        }
        return started
    }

    fun stopPushToTalk(targetPeerId: String?) {
        voiceManager.stopAndTransmit(targetPeerId)
    }

    fun playVoiceClip(item: VoiceRecordingItem) {
        voiceManager.playClip(item)
    }

    fun startLocationTracking() {
        val ok = locationManager.startUpdates()
        if (!ok) {
            _snackMessages.tryEmit("Location permission required for offline GPS coordinates")
        }
    }

    fun stopLocationTracking() {
        locationManager.stopUpdates()
    }

    fun computeVectorToPeer(peer: PeerEntity): RelativeVector? {
        val lat = peer.lastLat ?: return null
        val lon = peer.lastLon ?: return null
        return locationManager.computeRelativeVector(lat, lon)
    }

    fun broadcastLocation(targetPeerId: String?, isEmergency: Boolean) {
        viewModelScope.launch {
            val fix = currentLocation.value
            if (fix == null) {
                _snackMessages.tryEmit("Acquire a GPS fix first before broadcasting coordinates")
                return@launch
            }
            routingEngine.sendLocationBroadcast(
                targetPeerId = targetPeerId,
                lat = fix.latitude,
                lon = fix.longitude,
                accuracyMeters = fix.accuracyMeters,
                isEmergency = isEmergency
            )
            _snackMessages.tryEmit(
                if (isEmergency) "Emergency beacon queued across mesh" else "Location coordinates shared"
            )
        }
    }

    fun triggerEmergencyModeBroadcast() {
        viewModelScope.launch {
            val current = repository.getSettingsOnce()
            val newState = !current.emergencyModeActive
            repository.updateSettings { it.copy(emergencyModeActive = newState) }
            if (newState) {
                val fix = currentLocation.value
                if (fix != null) {
                    routingEngine.sendLocationBroadcast(
                        targetPeerId = null,
                        lat = fix.latitude,
                        lon = fix.longitude,
                        accuracyMeters = fix.accuracyMeters,
                        isEmergency = true
                    )
                }
                val activePeers = repository.getUnblockedPeersOnce()
                activePeers.forEach { peer ->
                    routingEngine.sendDirectTextMessage(
                        peerId = peer.deviceId,
                        plaintext = current.emergencyMessage,
                        strategyOverride = RoutingStrategy.EPIDEMIC,
                        isEmergency = true
                    )
                }
                _snackMessages.tryEmit("Emergency Mode ACTIVE — priority beacons dispatched")
            } else {
                _snackMessages.tryEmit("Emergency Mode deactivated")
            }
        }
    }

    fun updateSettings(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            repository.updateSettings(transform)
        }
    }

    fun flushQueueNow() {
        viewModelScope.launch {
            routingEngine.flushStoreAndForwardQueue()
            _snackMessages.tryEmit("Flushed DTN Store-and-Forward queue")
        }
    }

    fun purgeExpiredNow() {
        viewModelScope.launch {
            val removed = repository.purgeExpiredCache()
            _snackMessages.tryEmit("Purged $removed expired packets/messages")
        }
    }

    fun runLoopbackSelfTest() {
        viewModelScope.launch {
            _loopbackResult.value = routingEngine.runSelfLoopbackDiagnostic()
        }
    }

    fun clearNetworkLogs() {
        viewModelScope.launch { repository.clearNetworkEvents() }
    }
}
