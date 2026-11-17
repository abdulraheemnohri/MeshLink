package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline QR scanner. Camera is bound only while this dialog is visible and permission is granted.
 * Decoded content is returned to the caller; callers must validate and authenticate the payload.
 */
@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan MeshLink QR code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasPermission) {
                    QrCameraPreview(
                        onResult = onResult,
                        modifier = Modifier.fillMaxWidth().height(320.dp)
                    )
                    Text(
                        "Point the camera at a MeshLink peer identity QR code. The scan works offline.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("Camera permission is required only while scanning. No image is saved.")
                }
            }
        },
        confirmButton = {
            if (!hasPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (!hasPermission) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun QrCameraPreview(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val delivered = remember { AtomicBoolean(false) }
    val active = remember { AtomicBoolean(true) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }
    val executor = remember(retryToken) { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, previewView, retryToken) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        active.set(true)
        cameraError = null
        val listener = Runnable {
            if (!active.get()) return@Runnable
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val reader = MultiFormatReader().apply {
                    setHints(
                        mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true
                        )
                    )
                }
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (!delivered.get()) {
                            val plane = image.planes.firstOrNull()
                            if (plane != null) {
                                val buffer = plane.buffer.duplicate()
                                val base = buffer.position()
                                val bytes = ByteArray(image.width * image.height)
                                var validPlane = true
                                for (row in 0 until image.height) {
                                    for (column in 0 until image.width) {
                                        val offset = base + row * plane.rowStride + column * plane.pixelStride
                                        if (offset >= buffer.limit()) {
                                            validPlane = false
                                            break
                                        }
                                        bytes[row * image.width + column] = buffer.get(offset)
                                    }
                                    if (!validPlane) break
                                }
                                if (!validPlane) return@setAnalyzer
                                val source = PlanarYUVLuminanceSource(
                                    bytes,
                                    image.width,
                                    image.height,
                                    0,
                                    0,
                                    image.width,
                                    image.height,
                                    false
                                )
                                val decoded = runCatching {
                                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                                }.getOrNull()
                                if (active.get() && !decoded.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                    mainExecutor.execute { if (active.get()) onResult(decoded) }
                                }
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Exception) {
                val reason = error.cause?.message ?: error.message ?: "Camera could not start"
                mainExecutor.execute {
                    if (active.get()) cameraError = "Camera unavailable: $reason"
                }
            }
        }
        providerFuture.addListener(listener, mainExecutor)
        onDispose {
            active.set(false)
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(320.dp))
        Text(
            "Align QR code inside camera view",
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
    if (cameraError != null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(cameraError!!, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                delivered.set(false)
                retryToken++
            }) { Text("Retry camera") }
        }
    }
}
