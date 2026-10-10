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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun QrScannerScreen(
    onCancel: () -> Unit,
    onJoinEvent: (PingRingInvite) -> Unit
) {
    val context = LocalContext.current
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var scanMessage by remember { mutableStateOf(context.getString(R.string.align_pingring_qr)) }
    var scanSessionId by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_pingring_qr)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
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
                        val invite = PingRingCodeParser.parseInvite(rawValue)
                        if (invite != null) {
                            onJoinEvent(invite)
                            true
                        } else {
                            scanMessage = context.getString(R.string.qr_code_invalid)
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
                    Text(stringResource(R.string.cancel))
                }
                if (permissionState.status.isGranted) {
                    Button(
                        onClick = {
                            scanSessionId++
                            scanMessage = context.getString(R.string.scanning_align_qr)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.rescan))
                    }
                } else {
                    Button(
                        onClick = { permissionState.launchPermissionRequest() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.grant_camera))
                    }
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
                stringResource(R.string.camera_permission_rationale),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.allow_camera))
            }
        }
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
    val scanFailedMessage = stringResource(R.string.camera_scan_failed)
    val cameraUnavailableMessage = stringResource(R.string.camera_unavailable)
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var hasResult by remember(scanSessionId) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView, scanSessionId) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraExecutor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        var preview: CameraPreview? = null
        var imageAnalysis: ImageAnalysis? = null
        var disposed = false
        val analyzer = QrImageAnalyzer(
            onScanResult = { raw ->
                if (!hasResult) {
                    hasResult = onRawScanResult(raw)
                }
            },
            onFailure = {
                onCameraError(scanFailedMessage)
            }
        )

        val listener = Runnable {
            if (disposed) return@Runnable
            try {
                cameraProvider = cameraProviderFuture.get()
                preview = CameraPreview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (_: Exception) {
                onCameraError(cameraUnavailableMessage)
            }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            imageAnalysis?.clearAnalyzer()
            preview?.setSurfaceProvider(null)
            analyzer.closeWhenIdle {
                ContextCompat.getMainExecutor(context).execute {
                    val boundUseCases = listOfNotNull(preview, imageAnalysis).toTypedArray()
                    if (boundUseCases.isNotEmpty()) {
                        runCatching { cameraProvider?.unbind(*boundUseCases) }
                    }
                    cameraExecutor.shutdown()
                }
            }
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
    private var closing = false
    private var activeTasks = 0
    private var onClosed: (() -> Unit)? = null

    @AndroidXOptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(imageProxy: ImageProxy) {
        synchronized(this) {
            if (closing) {
                imageProxy.close()
                return
            }
            activeTasks++
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            completeTask()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qrValue = barcodes
                    .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    .orEmpty()
                if (!isClosing()) onScanResult(qrValue)
            }
            .addOnFailureListener {
                if (!isClosing()) onFailure(it)
            }
            .addOnCompleteListener {
                imageProxy.close()
                completeTask()
            }
    }

    fun closeWhenIdle(onClosed: () -> Unit) {
        val closeImmediately = synchronized(this) {
            closing = true
            this.onClosed = onClosed
            activeTasks == 0
        }
        if (closeImmediately) finishClosing()
    }

    @Synchronized
    private fun isClosing(): Boolean = closing

    private fun completeTask() {
        val shouldClose = synchronized(this) {
            activeTasks--
            closing && activeTasks == 0
        }
        if (shouldClose) finishClosing()
    }

    private fun finishClosing() {
        val callback = synchronized(this) {
            scanner.close()
            onClosed.also { onClosed = null }
        }
        callback?.invoke()
    }
}
