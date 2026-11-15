package com.example.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager as FwBiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat

enum class BiometricStatus {
    AVAILABLE,           // Biometrics hardware present and at least one biometric enrolled
    CREDENTIAL_ONLY,     // No biometrics enrolled, but device PIN/pattern/password lock is set
    NONE_ENROLLED,       // Neither biometric nor screen lock is set up on device
    NO_HARDWARE,         // No biometric sensor found on this hardware
    HW_UNAVAILABLE,      // Biometric sensor hardware currently unavailable or busy
    NOT_SUPPORTED        // API version or device unsupported
}

sealed class AuthResult {
    data object Success : AuthResult()
    data class Error(val errorCode: Int, val errorMessage: String) : AuthResult()
    data object Failed : AuthResult()
    data object Cancelled : AuthResult()
}

/**
 * BiometricManager service handles biometric and device-credential enrollment verification
 * and BiometricPrompt execution for secure local MeshLink vault access.
 */
class BiometricManager(private val context: Context) {

    private val keyguardManager: KeyguardManager?
        get() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private val fwBiometricManager: FwBiometricManager?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(Context.BIOMETRIC_SERVICE) as? FwBiometricManager
        } else {
            null
        }

    /**
     * Inspects device biometric sensor and screen lock status.
     */
    fun canAuthenticate(): BiometricStatus {
        val km = keyguardManager
        val isDeviceSecure = km?.isDeviceSecure ?: km?.isKeyguardSecure ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = fwBiometricManager ?: return if (isDeviceSecure) {
                BiometricStatus.CREDENTIAL_ONLY
            } else {
                BiometricStatus.NO_HARDWARE
            }

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bm.canAuthenticate(
                    FwBiometricManager.Authenticators.BIOMETRIC_STRONG or
                        FwBiometricManager.Authenticators.BIOMETRIC_WEAK
                )
            } else {
                @Suppress("DEPRECATION")
                bm.canAuthenticate()
            }

            return when (result) {
                FwBiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                FwBiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    if (isDeviceSecure) BiometricStatus.CREDENTIAL_ONLY else BiometricStatus.NONE_ENROLLED
                }
                FwBiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                    if (isDeviceSecure) BiometricStatus.CREDENTIAL_ONLY else BiometricStatus.NO_HARDWARE
                }
                FwBiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                    if (isDeviceSecure) BiometricStatus.CREDENTIAL_ONLY else BiometricStatus.HW_UNAVAILABLE
                }
                else -> {
                    if (isDeviceSecure) BiometricStatus.CREDENTIAL_ONLY else BiometricStatus.NONE_ENROLLED
                }
            }
        } else {
            return if (isDeviceSecure) BiometricStatus.CREDENTIAL_ONLY else BiometricStatus.NOT_SUPPORTED
        }
    }

    /**
     * Returns true if either biometrics or screen PIN/pattern/password lock is available to authenticate.
     */
    fun isAuthenticationAvailable(): Boolean {
        val status = canAuthenticate()
        return status == BiometricStatus.AVAILABLE || status == BiometricStatus.CREDENTIAL_ONLY
    }

    /**
     * Executes standard Android BiometricPrompt authentication.
     */
    fun authenticate(
        activity: Activity,
        title: String = "Unlock MeshLink Vault",
        subtitle: String = "Authenticate to access local encrypted mesh network",
        description: String = "Scan fingerprint or confirm device screen lock",
        onResult: (AuthResult) -> Unit
    ): CancellationSignal? {
        val status = canAuthenticate()
        if (status == BiometricStatus.NONE_ENROLLED) {
            onResult(AuthResult.Error(-1, "No biometric or screen lock enrolled on device"))
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cancelSignal = CancellationSignal()
            val executor = ContextCompat.getMainExecutor(activity)

            val builder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setDescription(description)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: Allow Biometrics (Strong/Weak) OR Device Credential (PIN/pattern/password)
                builder.setAllowedAuthenticators(
                    FwBiometricManager.Authenticators.BIOMETRIC_STRONG or
                        FwBiometricManager.Authenticators.BIOMETRIC_WEAK or
                        FwBiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                builder.setNegativeButton("Use PIN / Cancel", executor) { _, _ ->
                    onResult(AuthResult.Cancelled)
                }
            }

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    onResult(AuthResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    val message = errString?.toString() ?: "Authentication error ($errorCode)"
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        onResult(AuthResult.Cancelled)
                    } else {
                        onResult(AuthResult.Error(errorCode, message))
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onResult(AuthResult.Failed)
                }
            }

            try {
                val prompt = builder.build()
                prompt.authenticate(cancelSignal, executor, callback)
                return cancelSignal
            } catch (e: Exception) {
                Log.e("BiometricManager", "Failed to start BiometricPrompt: ${e.message}", e)
                onResult(AuthResult.Error(-2, e.message ?: "Authentication initialization error"))
                return null
            }
        } else {
            onResult(AuthResult.Error(-3, "BiometricPrompt requires Android 9.0+ (API 28)"))
            return null
        }
    }

    /**
     * Fallback intent for confirming device PIN, pattern, or password.
     */
    fun createConfirmDeviceCredentialIntent(
        title: String = "Unlock MeshLink Vault",
        description: String = "Confirm your device PIN or password"
    ): Intent? {
        val km = keyguardManager
        @Suppress("DEPRECATION")
        return km?.createConfirmDeviceCredentialIntent(title, description)
    }

    fun getStatusDescription(status: BiometricStatus): String {
        return when (status) {
            BiometricStatus.AVAILABLE -> "Biometric hardware ready (Fingerprint / Face)"
            BiometricStatus.CREDENTIAL_ONLY -> "Device screen lock ready (PIN / Pattern / Password)"
            BiometricStatus.NONE_ENROLLED -> "No screen lock or biometric enrolled on this device"
            BiometricStatus.NO_HARDWARE -> "No biometric hardware sensor found on this device"
            BiometricStatus.HW_UNAVAILABLE -> "Biometric hardware sensor is currently busy or unavailable"
            BiometricStatus.NOT_SUPPORTED -> "Biometric authentication not supported on this OS version"
        }
    }
}
