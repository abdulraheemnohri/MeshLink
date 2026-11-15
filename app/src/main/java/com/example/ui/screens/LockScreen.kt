package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.security.AuthResult
import com.example.security.BiometricManager
import com.example.security.BiometricStatus
import com.example.ui.components.GlassPanel
import com.example.ui.components.MeshPillBadge
import com.example.ui.components.PulseStatusDot
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshCrimson
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan

@Composable
fun LockScreen(
    biometricManager: BiometricManager,
    promptTitle: String = "Unlock MeshLink Vault",
    promptSubtitle: String = "Authenticate to access offline mesh storage",
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Prevent bypassing the lock screen via system back navigation
    BackHandler(enabled = true) {
        activity?.moveTaskToBack(true)
    }

    var biometricStatus by remember { mutableStateOf(biometricManager.canAuthenticate()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasAutoTriggered by remember { mutableStateOf(false) }

    val credentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            errorMessage = null
            onUnlocked()
        } else {
            errorMessage = "Device credential verification cancelled or failed."
        }
    }

    val triggerAuthentication = {
        errorMessage = null
        biometricStatus = biometricManager.canAuthenticate()

        if (activity != null && biometricStatus == BiometricStatus.AVAILABLE) {
            val signal = biometricManager.authenticate(
                activity = activity,
                title = promptTitle,
                subtitle = promptSubtitle,
                description = "Confirm biometric or device credential to unlock local vault"
            ) { result ->
                when (result) {
                    is AuthResult.Success -> {
                        errorMessage = null
                        onUnlocked()
                    }
                    is AuthResult.Cancelled -> {
                        errorMessage = "Authentication cancelled"
                    }
                    is AuthResult.Failed -> {
                        errorMessage = "Fingerprint not recognized. Try again."
                    }
                    is AuthResult.Error -> {
                        // Fallback to device PIN/pattern/password if biometric error or lockout
                        val credentialIntent = biometricManager.createConfirmDeviceCredentialIntent(
                            title = promptTitle,
                            description = promptSubtitle
                        )
                        if (credentialIntent != null) {
                            credentialLauncher.launch(credentialIntent)
                        } else {
                            errorMessage = result.errorMessage
                        }
                    }
                }
            }
            if (signal == null && biometricStatus == BiometricStatus.CREDENTIAL_ONLY) {
                val intent = biometricManager.createConfirmDeviceCredentialIntent(promptTitle, promptSubtitle)
                if (intent != null) credentialLauncher.launch(intent)
            }
        } else {
            // Fallback for CREDENTIAL_ONLY or devices requiring PIN/Pattern
            val intent = biometricManager.createConfirmDeviceCredentialIntent(promptTitle, promptSubtitle)
            if (intent != null) {
                credentialLauncher.launch(intent)
            } else if (biometricStatus == BiometricStatus.NONE_ENROLLED) {
                errorMessage = "No screen lock (PIN/Password) or biometric is set up on this device."
            } else {
                errorMessage = biometricManager.getStatusDescription(biometricStatus)
            }
        }
    }

    // Auto-prompt on initial display if biometrics or PIN are available
    LaunchedEffect(Unit) {
        if (!hasAutoTriggered) {
            hasAutoTriggered = true
            if (biometricManager.isAuthenticationAvailable()) {
                triggerAuthentication()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassPanel(accentColor = NeonCyan) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = NeonCyan.copy(alpha = 0.2f),
                                modifier = Modifier.size(80.dp)
                            )
                            Icon(
                                imageVector = if (biometricStatus == BiometricStatus.AVAILABLE) {
                                    Icons.Filled.Fingerprint
                                } else {
                                    Icons.Filled.Lock
                                },
                                contentDescription = "Locked Vault",
                                tint = NeonCyan,
                                modifier = Modifier.size(46.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "MeshLink Vault Locked",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Decentralized offline keys, routes, and chat history are secured with hardware encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            PulseStatusDot(
                                active = biometricStatus == BiometricStatus.AVAILABLE || biometricStatus == BiometricStatus.CREDENTIAL_ONLY,
                                activeColor = if (biometricStatus == BiometricStatus.AVAILABLE) MeshEmerald else NeonCyan
                            )
                            Spacer(Modifier.width(8.dp))
                            MeshPillBadge(
                                text = biometricManager.getStatusDescription(biometricStatus),
                                color = when (biometricStatus) {
                                    BiometricStatus.AVAILABLE -> MeshEmerald
                                    BiometricStatus.CREDENTIAL_ONLY -> NeonCyan
                                    BiometricStatus.NONE_ENROLLED -> MeshAmber
                                    else -> MeshAmber
                                },
                                icon = if (biometricStatus == BiometricStatus.AVAILABLE) Icons.Filled.Fingerprint else Icons.Filled.Security
                            )
                        }

                        if (errorMessage != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshCrimson
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = triggerAuthentication,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("unlock_vault_button")
                        ) {
                            Icon(
                                imageVector = if (biometricStatus == BiometricStatus.AVAILABLE) {
                                    Icons.Filled.Fingerprint
                                } else {
                                    Icons.Filled.Password
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (biometricStatus == BiometricStatus.AVAILABLE) {
                                    "Unlock with Biometrics"
                                } else {
                                    "Unlock with Device Screen Lock"
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val intent = biometricManager.createConfirmDeviceCredentialIntent(
                                        promptTitle,
                                        promptSubtitle
                                    )
                                    if (intent != null) {
                                        credentialLauncher.launch(intent)
                                    } else {
                                        errorMessage = "Device PIN confirmation unavailable."
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("unlock_pin_button")
                            ) {
                                Icon(Icons.Filled.Password, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Use Device PIN")
                            }

                            OutlinedButton(
                                onClick = {
                                    biometricStatus = biometricManager.canAuthenticate()
                                    errorMessage = null
                                    triggerAuthentication()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("refresh_auth_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry Auth")
                            }
                        }

                        // Fallback for test devices/emulators with no system lock enrolled
                        if (biometricStatus == BiometricStatus.NONE_ENROLLED || biometricStatus == BiometricStatus.NO_HARDWARE) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                    }
                                ) {
                                    Text("Open Security Settings", color = NeonCyan)
                                }
                                TextButton(
                                    onClick = onUnlocked,
                                    modifier = Modifier.testTag("bypass_lock_button")
                                ) {
                                    Text("Unlock Test Vault", color = MeshAmber)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
