package com.example.ui.navigation

object MeshRoute {
    const val ONBOARDING = "onboarding"
    const val LOCK = "lock"
    const val DASHBOARD = "home"
    const val HOME = DASHBOARD
    const val DISCOVERY = "discover"
    const val DISCOVER = DISCOVERY
    const val CONNECTIONS = "connections"
    const val CHATS = "chats"
    const val PRIVATE_CHAT = "private_chat/{peerId}"
    const val GROUPS = "groups"
    const val GROUP_CHAT = "group_chat/{groupId}"
    const val FILES = "files"
    const val NETWORK_MAP = "network_map"
    const val VOICE = "voice"
    const val LOCATION = "location"
    const val TRANSFERS = "transfers"
    const val IDENTITY = "identity"
    const val SECURITY = "security"
    const val ROUTING = "routing"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    const val BRIDGE = "bridge"
    const val EMERGENCY = "emergency"

    fun privateChat(peerId: String) = "private_chat/$peerId"
    fun groupChat(groupId: String) = "group_chat/$groupId"
}
