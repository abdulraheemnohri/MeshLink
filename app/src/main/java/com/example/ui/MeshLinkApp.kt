package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.navigation.MeshRoute
import com.example.ui.navigation.NavigationGraph
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshLinkTheme
import com.example.ui.viewmodel.MeshViewModel

private data class TopNavItem(
    val route: String,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshLinkApp(viewModel: MeshViewModel) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    MeshLinkTheme(themeMode = settings.themeMode) {
        if (!settings.onboardingCompleted) {
            OnboardingScreen(
                defaultAlias = identity?.alias ?: "MeshNode",
                capabilities = capabilities,
                onRefreshPermissions = { viewModel.refreshCapabilities() },
                onComplete = { alias -> viewModel.completeOnboarding(alias) }
            )
            return@MeshLinkTheme
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: MeshRoute.HOME

        val topTabs = listOf(
            TopNavItem(MeshRoute.HOME, stringResource(R.string.nav_home), Icons.Filled.Home, Icons.Outlined.Home),
            TopNavItem(MeshRoute.DISCOVER, stringResource(R.string.nav_mesh), Icons.Filled.Hub, Icons.Outlined.Hub),
            TopNavItem(MeshRoute.CHATS, stringResource(R.string.nav_chats), Icons.Filled.Chat, Icons.Outlined.Chat),
            TopNavItem(MeshRoute.FILES, stringResource(R.string.nav_share), Icons.Filled.FolderZip, Icons.Outlined.FolderZip),
            TopNavItem(MeshRoute.IDENTITY, stringResource(R.string.nav_node), Icons.Filled.Settings, Icons.Outlined.Settings)
        )

        val meshSubRoutes = listOf(
            MeshRoute.DISCOVER to "Discover",
            MeshRoute.NETWORK_CONTROL to "Network Control",
            MeshRoute.CONNECTIONS to "Connections",
            MeshRoute.NETWORK_MAP to "Topology",
            MeshRoute.BRIDGE to "Bridge"
        )
        val commsSubRoutes = listOf(
            MeshRoute.FILES to "Files",
            MeshRoute.TRANSFERS to "Queue",
            MeshRoute.VOICE to "Voice PTT",
            MeshRoute.LOCATION to "Location"
        )
        val nodeSubRoutes = listOf(
            MeshRoute.IDENTITY to "Identity",
            MeshRoute.SECURITY to "Security",
            MeshRoute.ROUTING to "Routing",
            MeshRoute.DIAGNOSTICS to "Diagnostics",
            MeshRoute.PROFILE to "Profile",
            MeshRoute.SETTINGS to "Settings"
        )

        val isDetailScreen = currentRoute.startsWith("private_chat/") ||
            currentRoute.startsWith("group_chat/") ||
            currentRoute == MeshRoute.EMERGENCY ||
            currentRoute == MeshRoute.LOCK
        val activeHubIndex = when {
            currentRoute == MeshRoute.HOME -> 0
            meshSubRoutes.any { it.first == currentRoute } -> 1
            currentRoute == MeshRoute.CHATS || currentRoute == MeshRoute.GROUPS ||
                currentRoute.startsWith("private_chat/") || currentRoute.startsWith("group_chat/") -> 2
            commsSubRoutes.any { it.first == currentRoute } -> 3
            nodeSubRoutes.any { it.first == currentRoute } -> 4
            else -> 0
        }

        val screenTitle = when {
            currentRoute.startsWith("private_chat/") -> {
                val peerId = backStackEntry?.arguments?.getString("peerId")
                peers.firstOrNull { it.deviceId == peerId }?.alias ?: "Encrypted Chat"
            }
            currentRoute.startsWith("group_chat/") -> {
                val groupId = backStackEntry?.arguments?.getString("groupId")
                groups.firstOrNull { it.groupId == groupId }?.name ?: "Group Channel"
            }
            currentRoute == MeshRoute.EMERGENCY -> "Emergency Mode"
            currentRoute == MeshRoute.LOCK -> "MeshLink Vault Locked"
            currentRoute == MeshRoute.HOME -> "MeshLink"
            else -> (meshSubRoutes + commsSubRoutes + nodeSubRoutes)
                .firstOrNull { it.first == currentRoute }?.second ?: "MeshLink"
        }

        val navigateTopLevel: (String) -> Unit = { target ->
            navController.navigate(target) {
                popUpTo(MeshRoute.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail && currentRoute != MeshRoute.LOCK) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Spacer(Modifier.height(16.dp))
                        topTabs.forEachIndexed { idx, item ->
                            val selected = activeHubIndex == idx
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    val iconScale = animateFloatAsState(
                                        targetValue = if (selected) 1.12f else 1f,
                                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                                        label = "navigation_icon_scale"
                                    )
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale.value
                                            scaleY = iconScale.value
                                        },
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("rail_${item.route}")
                            )
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (currentRoute != MeshRoute.LOCK) {
                            Column {
                                TopAppBar(
                                    title = {
                                        Text(
                                            text = screenTitle,
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                    },
                                    navigationIcon = {
                                        if (isDetailScreen && currentRoute != MeshRoute.LOCK) {
                                            IconButton(onClick = { navController.popBackStack() }) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Navigate back"
                                                )
                                            }
                                        }
                                    },
                                    actions = {
                                        if (currentRoute != MeshRoute.EMERGENCY && currentRoute != MeshRoute.LOCK) {
                                            IconButton(
                                                onClick = { navController.navigate(MeshRoute.EMERGENCY) },
                                                modifier = Modifier.testTag("topbar_emergency_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.MedicalServices,
                                                    contentDescription = "Open Emergency Mode",
                                                    tint = if (settings.emergencyModeActive) MeshCrimson else MeshCrimson.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )

                            val subRoutes = when (activeHubIndex) {
                                1 -> meshSubRoutes
                                3 -> commsSubRoutes
                                4 -> nodeSubRoutes
                                else -> null
                            }
                            if (subRoutes != null) {
                                val selectedIdx = subRoutes.indexOfFirst { it.first == currentRoute }.coerceAtLeast(0)
                                ScrollableTabRow(
                                    selectedTabIndex = selectedIdx,
                                    edgePadding = 12.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ) {
                                    subRoutes.forEachIndexed { i, (route, label) ->
                                        Tab(
                                            selected = i == selectedIdx,
                                            onClick = { navigateTopLevel(route) },
                                            text = { Text(label) },
                                            modifier = Modifier.testTag("subtab_$route")
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                    bottomBar = {
                        if (!useRail && !isDetailScreen) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                tonalElevation = 8.dp
                            ) {
                                topTabs.forEachIndexed { idx, item ->
                                    val selected = activeHubIndex == idx
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTopLevel(item.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.label
                                            )
                                        },
                                        label = { Text(item.label) },
                                        modifier = Modifier.testTag("bottom_nav_${item.route}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavigationGraph(
                        navController = navController,
                        viewModel = viewModel,
                        onNavigateTopLevel = navigateTopLevel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}
