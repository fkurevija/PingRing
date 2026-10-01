package com.fkurevija.pingring.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.data.DiscoveredSession
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.R
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
fun PingRingJoinScreen(
    savedUsername: String = "",
    existingMembers: List<String> = emptyList(),
    isConnecting: Boolean = false,
    discoveredSessions: List<DiscoveredSession> = emptyList(),
    onBack: () -> Unit,
    onJoinEvent: (eventCode: String, username: String, inviteSecret: String?) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Nothing is live yet on this screen, so back simply means "never mind" -
    // but say so explicitly instead of leaning on the navigation default, which
    // pops whatever is underneath and would take the user out of the app
    // entirely if this screen were ever the only entry on the stack.
    BackHandler { onBack() }

    fun attemptJoin(codeToUse: String, inviteSecret: String? = null) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isBlank()) {
            usernameError = context.getString(R.string.join_username_required)
            return
        }
        if (existingMembers.any { it.equals(trimmedUsername, ignoreCase = true) }) {
            usernameError = context.getString(R.string.join_username_taken, trimmedUsername)
            return
        }
        if (existingMembers.size + 1 >= MAX_PARTICIPANTS) {
            inputError = context.getString(R.string.join_pingring_full, MAX_PARTICIPANTS)
            return
        }
        val parsedEventCode = PingRingCodeParser.parse(codeToUse)
        if (parsedEventCode == null) {
            inputError = context.getString(R.string.join_code_invalid)
            return
        }
        onJoinEvent(parsedEventCode, trimmedUsername, inviteSecret)
    }

    if (showScanner) {
        QrScannerScreen(
            onCancel = { showScanner = false },
            onJoinEvent = { invite ->
                eventCode = invite.eventCode
                showScanner = false
                inputError = null
                attemptJoin(invite.eventCode, invite.secret)
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_pingring), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                        stringResource(R.string.enter_pingring_code),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.ask_host_for_code),
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
                        label = { Text(stringResource(R.string.username)) },
                        placeholder = { Text(stringResource(R.string.username_example)) },
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

                    // PingRing Code Field
                    OutlinedTextField(
                        value = eventCode,
                        onValueChange = {
                            eventCode = it.uppercase()
                            inputError = null
                        },
                        label = { Text(stringResource(R.string.pingring_code)) },
                        placeholder = { Text(stringResource(R.string.pingring_code_example)) },
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
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { attemptJoin(eventCode) },
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connecting), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.or),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(20.dp))

                    // Sessions nearby that are offering themselves. Tapping one
                    // takes the same path as typing its code rather than
                    // filling the field: the user has asked to join, and making
                    // them confirm what they tapped adds a step to no end.
                    if (discoveredSessions.isNotEmpty()) {
                        Text(
                            stringResource(R.string.nearby_pingrings),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        discoveredSessions.forEach { session ->
                            val canJoin = session.joinable && !isConnecting
                            OutlinedButton(
                                onClick = { attemptJoin(session.eventCode) },
                                enabled = canJoin,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        session.name,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        if (session.joinable) {
                                            stringResource(R.string.tap_to_join)
                                        } else {
                                            stringResource(R.string.full_or_not_accepting)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = stringResource(R.string.join_session, session.name)
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    val hasCamera = remember(context) {
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    }

                    OutlinedButton(
                        onClick = { if (hasCamera) showScanner = true },
                        enabled = hasCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (hasCamera) R.string.scan_host_qr else R.string.camera_not_available),
                            fontWeight = FontWeight.SemiBold
                        )
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

@Preview(showBackground = true)
@Composable
fun PingRingJoinScreenPreview() {
    PingRingTheme {
        PingRingJoinScreen(onBack = {}, onJoinEvent = { _, _, _ -> })
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
