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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EventJoinScreen(
    savedUsername: String = "",
    existingMembers: List<String> = emptyList(),
    onBack: () -> Unit,
    onJoinEvent: (eventCode: String, username: String) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    fun attemptJoin(codeToUse: String) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isBlank()) {
            usernameError = "Please enter a username."
            return
        }
        if (existingMembers.any { it.equals(trimmedUsername, ignoreCase = true) }) {
            usernameError = "Username '$trimmedUsername' is already taken in this Ping-Ring."
            return
        }
        val parsedEventCode = EventCodeParser.parse(codeToUse)
        if (parsedEventCode == null) {
            inputError = "Enter a valid 6-character Ping-Ring code."
            return
        }
        onJoinEvent(parsedEventCode, trimmedUsername)
    }

    if (showScanner) {
        QrScannerScreen(
            onCancel = { showScanner = false },
            onJoinEvent = { scannedCode ->
                eventCode = scannedCode
                showScanner = false
                inputError = null
                attemptJoin(scannedCode)
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join a Ping-Ring", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Enter Ping-Ring Code",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Ask the host for their 6-character Ping-Ring code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))

                    // Username Field
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            usernameError = null
                        },
                        label = { Text("Username") },
                        placeholder = { Text("e.g. Alex") },
                        isError = usernameError != null,
                        supportingText = {
                            usernameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Ping-Ring Code Field
                    OutlinedTextField(
                        value = eventCode,
                        onValueChange = {
                            eventCode = it.uppercase()
                            inputError = null
                        },
                        label = { Text("Ping-Ring Code") },
                        placeholder = { Text("e.g. AB12CD") },
                        isError = inputError != null,
                        supportingText = {
                            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { attemptJoin(eventCode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            "  OR  ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Host QR Code", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerScreen(
    onCancel: () -> Unit,
    onJoinEvent: (String) -> Unit
) {
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var scanMessage by remember { mutableStateOf("Align the Ping-Ring QR code within the frame.") }
    var scanSessionId by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Ping-Ring QR") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                "Camera permission is needed to scan QR codes. You can still join by typing the Ping-Ring code.",
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
        EventJoinScreen(onBack = {}, onJoinEvent = { _, _ -> })
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
                onCameraError("Camera scan failed. Try again.")
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
                onCameraError("Camera unavailable on this device.")
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
