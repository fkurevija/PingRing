package com.fkurevija.pingring.ui.screens

import android.Manifest
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.ui.theme.PingRingTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun EventJoinScreen(
    onJoinEvent: (String) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerScreen(
            onCancel = { showScanner = false },
            onJoinEvent = {
                eventCode = it
                showScanner = false
                inputError = null
                onJoinEvent(it)
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Join an Event",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = eventCode,
            onValueChange = {
                eventCode = it
                inputError = null
            },
            label = { Text("Event Code") },
            supportingText = {
                inputError?.let { Text(it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val parsedEventCode = EventCodeParser.parse(eventCode)
                if (parsedEventCode != null) {
                    onJoinEvent(parsedEventCode)
                } else {
                    inputError = "Enter a valid event code."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join")
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { showScanner = true }) {
            Text("Scan QR Code")
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QrScannerScreen(
    onCancel: () -> Unit,
    onJoinEvent: (String) -> Unit
) {
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var scanMessage by remember { mutableStateOf("Align the event QR code within the frame.") }
    var scanSessionId by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scan Event QR", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(scanMessage, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (permissionState.status.isGranted) {
            QrCameraPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                scanSessionId = scanSessionId,
                onRawScanResult = { rawValue ->
                    val parsedEventCode = EventCodeParser.parse(rawValue)
                    if (parsedEventCode != null) {
                        onJoinEvent(parsedEventCode)
                        true
                    } else {
                        scanMessage = "Invalid or empty code. Try another QR code."
                        false
                    }
                },
                onCameraError = { error ->
                    scanMessage = error
                }
            )
        } else {
            PermissionDeniedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onRetry = { permissionState.launchPermissionRequest() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            if (permissionState.status.isGranted) {
                Button(
                    onClick = {
                        scanSessionId++
                        scanMessage = "Scanning… align a QR code in view."
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Rescan")
                }
            } else {
                Button(
                    onClick = { permissionState.launchPermissionRequest() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Grant Camera")
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedCard(
    modifier: Modifier,
    onRetry: () -> Unit
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Camera permission is needed to scan QR codes. You can still join by typing the event code.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text("Allow Camera")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EventJoinScreenPreview() {
    PingRingTheme {
        EventJoinScreen(onJoinEvent = {})
    }
}

@Composable
private fun QrCameraPreview(
    modifier: Modifier,
    scanSessionId: Int,
    onRawScanResult: (String) -> Boolean,
    onCameraError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var hasResult by remember(scanSessionId) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView, scanSessionId) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val analyzer = QrImageAnalyzer(
            onScanResult = { raw ->
                if (!hasResult) {
                    hasResult = onRawScanResult(raw)
                }
            },
            onFailure = {
                onCameraError("Camera scan failed. Try again.")
            }
        )

        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = CameraPreview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (_: Exception) {
                onCameraError("Camera unavailable on this device.")
            }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            analyzer.close()
            cameraExecutor.shutdown()
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private class QrImageAnalyzer(
    private val onScanResult: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    @AndroidXOptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qrValue = barcodes
                    .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    .orEmpty()
                onScanResult(qrValue)
            }
            .addOnFailureListener(onFailure)
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() {
        scanner.close()
    }
}
