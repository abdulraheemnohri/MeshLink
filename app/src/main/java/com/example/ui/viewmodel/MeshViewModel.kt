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
import com.example.data.profile.LocalUserProfile
import com.example.data.profile.LocalUserProfileStore
import com.example.location.LocalGpsFix
import com.example.location.RelativeVector
import com.example.security.BiometricManager
import com.example.voice.VoiceRecordingItem
import com.example.transport.TransportPolicy
import com.example.transport.DiscoveredEndpoint
import com.example.transport.TransportHealthSnapshot
import com.example.transport.TransportSelectionMode
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
    private val profileStore = LocalUserProfileStore(application)
    private val _profile = MutableStateFlow(profileStore.load())
    val profile: StateFlow<LocalUserProfile> = _profile.asStateFlow()
    private val routingEngine = container.routingEngine
    private val crypto = container.crypto
    private val fileManager = container.fileTransferManager
    private val voiceManager = container.voiceAudioManager
    private val locationManager = container.locationSharingManager
    private val transportPolicyManager = container.transportPolicyManager
    private val _transportPolicy = MutableStateFlow(transportPolicyManager.load())
    val transportPolicy: StateFlow<TransportPolicy> = _transportPolicy.asStateFlow()
    /** Live transport-specific paths observed by the app-scoped discovery coordinator. */
    val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = container.discoveryCoordinator.peers
    val transportHealth: StateFlow<List<TransportHealthSnapshot>> = container.discoveryCoordinator.transportHealth
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

    fun updateProfileDraft(profile: LocalUserProfile) {
        _profile.value = profile
    }

    fun saveProfile(profile: LocalUserProfile) {
        viewModelScope.launch {
            runCatching { profileStore.save(profile) }
                .onSuccess {
                    _profile.value = it
                    _snackMessages.tryEmit("Local profile saved")
                }
                .onFailure { _snackMessages.tryEmit("Could not save profile: ${it.message ?: "storage error"}") }
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
            val results = container.discoveryCoordinator.discoverAll()
            val active = results.keys.joinToString(", ") { it.name }
            _snackMessages.tryEmit(if (active.isBlank()) "No enabled transport adapters are running" else "Scanning active transports: " + active)
        }
    }

    fun connectEndpoint(endpoint: DiscoveredEndpoint) {
        viewModelScope.launch {
            val result = container.discoveryCoordinator.connect(endpoint)
            val connected = result.getOrDefault(false)
            if (connected) {
                repository.logNetworkEvent(
                    transportType = endpoint.transportType.name,
                    eventType = "CONNECT_AUTHENTICATED",
                    peerId = endpoint.deviceId,
                    summary = "Authenticated RFCOMM session established with " + endpoint.alias
                )
            }
            val message = if (connected) "Authenticated connection established with " + endpoint.alias
                else if (result.isFailure) "Connection failed: " + (result.exceptionOrNull()?.message ?: "adapter error")
                else "Could not authenticate " + endpoint.alias + "; check that both devices run MeshLink"
            _snackMessages.tryEmit(message)
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

    fun updateTransportPolicy(policy: TransportPolicy) {
        transportPolicyManager.save(policy)
        _transportPolicy.value = policy
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
            runCatching { repository.importVerifiedPeer(payload) }
                .onSuccess { peer ->
                    _snackMessages.tryEmit(
                        if (peer.verifiedByQr) "Identity matched the previously trusted fingerprint for ${peer.alias}"
                        else "Imported ${peer.alias}. Compare its fingerprint out-of-band before trusting it."
                    )
                }
                .onFailure { error ->
                    _snackMessages.tryEmit(
                        if (error is com.example.data.repository.PeerIdentityKeyChangedException) {
                            "SECURITY ALERT: identity key changed for ${payload.alias}. Existing trust was preserved; verify the new fingerprint separately."
                        } else {
                            "Could not import peer identity: ${error.message ?: "storage error"}"
                        }
                    )
                }
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
                _snackMessages.tryEmit("Message not sent. Connect to a QR-verified peer to establish a secure session, then retry.")
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
            var queued = 0
            members.forEach { peer ->
                if (routingEngine.sendGroupInvite(group.groupId, peer.deviceId)) queued++
            }
            _snackMessages.tryEmit(
                "Created $category group '${group.name}'. Secure invitations queued/sent to $queued of ${members.size} selected peers; unverified peers must be fingerprint-verified first."
            )
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
        _snackMessages.tryEmit(
            "Legacy QR group invites are disabled because they expose the group encryption key. Ask an admin to invite this device through an authenticated peer connection."
        )
    }

    fun invitePeerToGroup(groupId: String, peer: PeerEntity) {
        viewModelScope.launch {
            val sent = routingEngine.sendGroupInvite(groupId, peer.deviceId)
            _snackMessages.tryEmit(
                if (sent) "Encrypted group invitation queued/sent to ${peer.alias}"
                else "Invitation not sent. Verify the peer fingerprint, check group admin rights and confirm a valid peer key."
            )
        }
    }

    fun removeGroupMember(groupId: String, peerId: String) {
        viewModelScope.launch {
            runCatching { routingEngine.removeGroupMemberAndRotate(groupId, peerId) }
                .onSuccess { (queued, total) ->
                    _snackMessages.tryEmit(
                        "Member removed locally and epoch rotated. Key update queued/sent to $queued of $total remaining members; delivery ACKs are asynchronous."
                    )
                }
                .onFailure { error ->
                    _snackMessages.tryEmit("Could not remove member or rotate key: ${error.message ?: "operation failed"}")
                }
        }
    }

    fun toggleGroupMemberRole(groupId: String, member: GroupMemberEntity) {
        viewModelScope.launch {
            val nextRole = if (member.role == "ADMIN") "MEMBER" else "ADMIN"
            runCatching { routingEngine.updateGroupMemberRoleAndDistribute(groupId, member.peerId, nextRole) }
                .onSuccess { (sent, total) ->
                    _snackMessages.tryEmit("Role update distributed to " + sent + " of " + total + " members; offline members receive it after reconnecting.")
                }
                .onFailure { error ->
                    _snackMessages.tryEmit("Could not update group role: " + (error.message ?: "operation failed"))
                }
        }
    }

    fun rotateGroupKey(groupId: String) {
        viewModelScope.launch {
            runCatching { routingEngine.rotateGroupKeyAndDistribute(groupId) }
                .onSuccess { (queued, total) ->
                    _snackMessages.tryEmit(
                        "New group key epoch created. Update queued/sent to $queued of $total members; delivery ACKs are asynchronous."
                    )
                }
                .onFailure { error ->
                    _snackMessages.tryEmit("Could not rotate group key: ${error.message ?: "operation failed"}")
                }
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
            val previous = repository.getSettingsOnce()
            repository.updateSettings(transform)
            val updated = repository.getSettingsOnce()
            val radioSettingsChanged =
                previous.localLanEnabled != updated.localLanEnabled ||
                previous.bleEnabled != updated.bleEnabled ||
                previous.bluetoothClassicEnabled != updated.bluetoothClassicEnabled ||
                previous.wifiDirectEnabled != updated.wifiDirectEnabled ||
                previous.wifiAwareEnabled != updated.wifiAwareEnabled

            // Apply radio toggles immediately when the foreground relay is already active.
            if (radioSettingsChanged && container.isRelayRunning.value) {
                val enabled = buildSet {
                    if (updated.localLanEnabled) add(com.example.core.protocol.TransportType.LOCAL_LAN)
                    if (updated.bleEnabled) add(com.example.core.protocol.TransportType.BLE)
                    if (updated.bluetoothClassicEnabled) add(com.example.core.protocol.TransportType.BLUETOOTH_CLASSIC)
                    if (updated.wifiDirectEnabled) add(com.example.core.protocol.TransportType.WIFI_DIRECT)
                    if (updated.wifiAwareEnabled) add(com.example.core.protocol.TransportType.WIFI_AWARE)
                }
                val registered = setOf(
                    com.example.core.protocol.TransportType.LOCAL_LAN,
                    com.example.core.protocol.TransportType.BLE,
                    com.example.core.protocol.TransportType.BLUETOOTH_CLASSIC,
                    com.example.core.protocol.TransportType.WIFI_DIRECT,
                    com.example.core.protocol.TransportType.WIFI_AWARE
                )
                container.discoveryCoordinator.stop(registered - enabled)
                container.discoveryCoordinator.start(enabled)
                container.discoveryCoordinator.discoverAll()
            }
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
