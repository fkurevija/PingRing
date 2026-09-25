package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.layout.onSizeChanged
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.BeaconPreset
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.data.SessionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    eventId: String,
    isHost: Boolean,
    trackHost: Boolean,
    coordinationError: String?,
    discoveryRepository: DiscoveryRepository?,
    sessionState: SessionState,
    onEventSettings: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit,
) {
    var showEndConfirmation by remember { mutableStateOf(false) }
    var showBeaconSheet by remember { mutableStateOf(false) }
    var showQrCode by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    LaunchedEffect(discoveryRepository) {
        discoveryRepository?.startDiscovery()
    }
    // Deliberately no onDispose-triggered stopDiscovery() here: HostSetupScreen
    // and RadarScreen share the same DiscoveryRepository instance for the
    // lifetime of an event (e.g. navigating to the event settings screen and
    // back must not drop the connection). Discovery is only stopped
    // explicitly when the user actually leaves/ends the event, via
    // onLeaveEvent/onEndEvent in MainActivity.

    val peers by discoveryRepository?.peerUpdates?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<RadarPeerUpdate>()) }
    val connectivity by discoveryRepository?.connectivityStatus?.collectAsState()
        ?: remember { mutableStateOf(ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true)) }
    val activeBeacons by discoveryRepository?.activeBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyMap()) }
    val localBeacon by discoveryRepository?.localBeacon?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val haptic = LocalHapticFeedback.current
    val nearest = peers.minByOrNull { it.distance ?: Float.MAX_VALUE }

    LaunchedEffect(nearest?.peerId, nearest?.distance) {
        val distance = nearest?.distance ?: return@LaunchedEffect
        if (distance < 2f) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Collect incoming nudges — double haptic pulse + snackbar
    LaunchedEffect(discoveryRepository) {
        discoveryRepository?.receivedNudge?.collect { nudge ->
            if (nudge.senderPeerId != discoveryRepository.localPeerId) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(120L)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                snackbarHostState.showSnackbar("👋 ${nudge.senderName} nudged you!")
            }
        }
    }

    val senderName = sessionState.userName.ifBlank { if (isHost) "Host" else "Peer" }
    val focusedPeer = if (isHost || !trackHost) nearest else peers.firstOrNull()
    val focusedBeacon = focusedPeer?.let { activeBeacons[it.peerId] } ?: localBeacon

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PingRing", fontWeight = FontWeight.Bold)
                        Text(
                            "Code: $eventId · ${peers.size + 1}/$MAX_PARTICIPANTS Members",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isHost) showEndConfirmation = true else onLeaveEvent()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isHost) "End PingRing" else "Leave PingRing"
                        )
                    }
                },
                actions = {
                    val acceptsNewMembers = sessionState.currentEvent?.acceptsNewMembers ?: true
                    if (acceptsNewMembers) {
                        IconButton(onClick = { showQrCode = true }) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = "Show QR code to join this PingRing"
                            )
                        }
                    }
                    if (isHost) {
                        TextButton(onClick = onEventSettings) { Text("Settings") }
                    }
                }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = {
                        if (isHost) showEndConfirmation = true else onLeaveEvent()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (isHost) "End PingRing" else "Leave PingRing") }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            coordinationError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Local Active Beacon Banner
            localBeacon?.let { beacon ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(beacon.colorArgb).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.Campaign,
                                contentDescription = null,
                                tint = Color(beacon.colorArgb),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Your Beacon is Active: ${beacon.title}",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(beacon.colorArgb)
                                )
                                Text(
                                    "Calling nearby peers to your location",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Button(
                            onClick = { discoveryRepository?.cancelLocalBeacon() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }

            // Remote Active Beacons Alerts
            activeBeacons.filter { (id, _) -> id != localBeacon?.senderPeerId }.values.forEach { beacon ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(beacon.colorArgb).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Campaign,
                            contentDescription = null,
                            tint = Color(beacon.colorArgb),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${beacon.senderName}: ${beacon.title}",
                                fontWeight = FontWeight.Bold,
                                color = Color(beacon.colorArgb)
                            )
                            Text(
                                "Active Beacon · ${BeaconVibrationPattern.fromName(beacon.vibrationPattern).displayName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    val paneWidth = (maxWidth - 20.dp) / 2
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RadarContent(
                            focusedPeer,
                            peers.isNotEmpty(),
                            focusedBeacon,
                            Modifier.width(paneWidth)
                        )
                        PeerList(peers, connectivity, discoveryRepository, activeBeacons, senderName, Modifier.width(paneWidth))
                    }
                } else {
                    var chatText by remember { mutableStateOf("") }
                    @OptIn(ExperimentalLayoutApi::class)
                    val isImeVisible = WindowInsets.isImeVisible

                    val renderChatCard: @Composable (Modifier) -> Unit = { cardModifier ->
                        Card(
                            modifier = cardModifier,
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isImeVisible) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (isImeVisible) 8.dp else 0.dp)
                        ) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text(
                                    "Chat",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(6.dp))
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (sessionState.chatMessages.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier.fillParentMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "No messages yet. Say hello!",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    } else {
                                        items(sessionState.chatMessages) { msg ->
                                            val isMe = msg.senderName == senderName
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                            ) {
                                                Card(
                                                    shape = RoundedCornerShape(
                                                        topStart = 14.dp,
                                                        topEnd = 14.dp,
                                                        bottomStart = if (isMe) 14.dp else 4.dp,
                                                        bottomEnd = if (isMe) 4.dp else 14.dp
                                                    ),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isMe) {
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.surface
                                                        }
                                                    )
                                                ) {
                                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                                        Text(
                                                            msg.senderName,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isMe) {
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.primary
                                                            }
                                                        )
                                                        Text(
                                                            msg.messageText,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = chatText,
                                        onValueChange = { chatText = it },
                                        placeholder = { Text("Type a message…", style = MaterialTheme.typography.bodyMedium) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(24.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (chatText.isNotBlank()) {
                                                discoveryRepository?.broadcastOutgoingChatMessage(chatText.trim(), senderName)
                                                chatText = ""
                                                focusManager.clearFocus()
                                            }
                                        },
                                        shape = CircleShape,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        modifier = Modifier.size(46.dp),
                                        enabled = chatText.isNotBlank()
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val density = LocalDensity.current
                    var topContentHeightPx by remember { mutableIntStateOf(0) }
                    val availableHeight = maxHeight

                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { topContentHeightPx = it.height }
                        ) {
                            RadarContent(
                                focusedPeer,
                                peers.isNotEmpty(),
                                focusedBeacon,
                                Modifier.fillMaxWidth().height(260.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { discoveryRepository?.sendNudge(null, senderName) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Nudge All")
                                }
                                Button(
                                    onClick = { showBeaconSheet = true },
                                    modifier = Modifier.weight(1f),
                                    colors = if (localBeacon != null) ButtonDefaults.buttonColors(containerColor = Color(localBeacon!!.colorArgb)) else ButtonDefaults.buttonColors()
                                ) {
                                    Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (localBeacon != null) "My Beacon" else "Beacon")
                                }
                            }
                        }

                        val topContentHeightDp = with(density) { topContentHeightPx.toDp() }
                        val chatCardOffsetY = if (isImeVisible) 0.dp else topContentHeightDp
                        val chatCardHeight = if (isImeVisible) {
                            availableHeight
                        } else {
                            (availableHeight - topContentHeightDp).coerceAtLeast(0.dp)
                        }

                        // Single stable call site keeps the chat text field's composition
                        // identity intact across docked/floating states, preserving focus
                        // and the IME connection when the keyboard opens.
                        renderChatCard(
                            Modifier
                                .fillMaxWidth()
                                .offset(y = chatCardOffsetY)
                                .height(chatCardHeight)
                                .padding(vertical = 6.dp)
                                .then(if (isImeVisible) Modifier.imePadding() else Modifier)
                        )
                    }
                }

                if (showEndConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showEndConfirmation = false },
                        title = { Text("End this PingRing?") },
                        text = {
                            Text("This removes every member from the PingRing and returns them to the home page.")
                        },
                        confirmButton = {
                            Button(onClick = {
                                showEndConfirmation = false
                                onEndEvent()
                            }) { Text("End PingRing") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEndConfirmation = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showBeaconSheet) {
                    BeaconCustomizationDialog(
                        localBeacon = localBeacon,
                        onDismiss = { showBeaconSheet = false },
                        onActivate = { title, colorArgb, patternName ->
                            discoveryRepository?.activateBeacon(title, colorArgb, patternName, senderName)
                        },
                        onStop = {
                            discoveryRepository?.cancelLocalBeacon()
                        },
                        onTestVibrate = { pattern ->
                            discoveryRepository?.playVibrationPattern(pattern)
                        }
                    )
                }

                if (showQrCode) {
                    AlertDialog(
                        onDismissRequest = { showQrCode = false },
                        title = { Text("Scan to Join") },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = generateQrCode(eventId).asImageBitmap(),
                                        contentDescription = "QR code for PingRing $eventId",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    eventId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showQrCode = false }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarContent(
    peer: RadarPeerUpdate?,
    hasPeers: Boolean,
    beacon: BeaconInfo?,
    modifier: Modifier
) {
    val hotness = ((peer?.rssi ?: -100) + 100).coerceIn(0, 100) / 100f
    val defaultRingColor = Color.lerp(Color(0xFF3F51B5), Color(0xFF00C853), hotness)
    val ringColor = beacon?.let { Color(it.colorArgb) } ?: defaultRingColor

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (beacon != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ringColor.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = ringColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        beacon.title,
                        color = ringColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (hasPeers && (peer != null)) {
            Text(
                peer.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
        }
        ProximityRing(
            progress = hotness,
            color = ringColor,
            arrowDegrees = peer?.azimuth ?: 0f,
            beacon = beacon,
            modifier = Modifier.size(260.dp)
        )
        Spacer(Modifier.height(16.dp))
        if (peer != null) {
            Text(
                peer.distance?.let { "%.1f m away".format(it) } ?: "${peer.proximity} signal",
                style = MaterialTheme.typography.headlineSmall,
                color = ringColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (peer.azimuth != null) "Direction locked · ${peer.azimuth.toInt()}°" else "BLE proximity mode",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("Keep PingRing open while others join", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProximityRing(
    progress: Float,
    color: Color,
    arrowDegrees: Float,
    beacon: BeaconInfo?,
    modifier: Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "beaconPulse")
    val beaconPulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseRadius"
    )
    val beaconPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseAlpha"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * .08f

            // Pulsing beacon wave if active
            if (beacon != null) {
                drawCircle(
                    color = color.copy(alpha = beaconPulseAlpha),
                    radius = (size.minDimension / 2) * beaconPulseRadius,
                    style = Stroke(stroke * 0.7f)
                )
            }

            drawCircle(color.copy(alpha = if (beacon != null) 0.25f else .12f), style = Stroke(stroke))
            drawArc(
                brush = Brush.sweepGradient(listOf(color.copy(alpha = .35f), color)),
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceAtLeast(.08f),
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Box(
            Modifier.size(104.dp).rotate(arrowDegrees),
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                drawLine(color, center, Offset(center.x, 8f), 8f, StrokeCap.Round)
                drawCircle(color, 9f, Offset(center.x, 8f))
            }
        }
        Box(Modifier.size(74.dp).background(color.copy(alpha = if (beacon != null) .30f else .14f), CircleShape))
    }
}

@Composable
private fun PeerList(
    peers: List<RadarPeerUpdate>,
    connectivity: ConnectivityStatus,
    repository: DiscoveryRepository?,
    activeBeacons: Map<String, BeaconInfo>,
    senderName: String,
    modifier: Modifier
) {
    Column(modifier.padding(bottom = 8.dp)) {
        if (!connectivity.isBluetoothEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bluetooth is off. Enable it to discover nearby peers.")
                    IconButton(onClick = { repository?.refreshConnectivityStatus() }) {
                        Icon(Icons.Default.Visibility, contentDescription = "Recheck")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isFull = (peers.size + 1) >= MAX_PARTICIPANTS
            Text(
                if (isFull) "Members (${peers.size + 1}/${MAX_PARTICIPANTS} - Full)" else "Members (${peers.size + 1}/${MAX_PARTICIPANTS})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (peers.isNotEmpty()) {
                TextButton(onClick = { repository?.sendNudge(null, senderName) }) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nudge All")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (peers.isEmpty()) {
            Text(
                "Your ephemeral ID is private to this event. When someone appears, their signal and direction will show here.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.peerId }) { peer ->
                    val peerBeacon = activeBeacons[peer.peerId]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (peerBeacon != null) {
                            CardDefaults.cardColors(containerColor = Color(peerBeacon.colorArgb).copy(alpha = 0.12f))
                        } else {
                            CardDefaults.cardColors()
                        }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val indicatorColor = if (peerBeacon != null) {
                                Color(peerBeacon.colorArgb)
                            } else if (peer.proximity == "Hot") {
                                Color(0xFF00C853)
                            } else {
                                Color(0xFF3F51B5)
                            }
                            Box(Modifier.size(12.dp).background(indicatorColor, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(peer.name, fontWeight = FontWeight.SemiBold)
                                    if (peerBeacon != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "🚨 ${peerBeacon.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(peerBeacon.colorArgb),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text("${peer.proximity} · ${peer.rssi ?: 0} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(peer.distance?.let { "%.1f m".format(it) } ?: "—", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { repository?.sendNudge(peer.peerId, senderName) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Text("👋", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconCustomizationDialog(
    localBeacon: BeaconInfo?,
    onDismiss: () -> Unit,
    onActivate: (title: String, colorArgb: Long, patternName: String) -> Unit,
    onStop: () -> Unit,
    onTestVibrate: (LongArray) -> Unit
) {
    var isVibrationMenuExpanded by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(BeaconPreset.SOS) }
    var title by remember { mutableStateOf(localBeacon?.title ?: selectedPreset.defaultTitle) }
    var selectedColor by remember { mutableLongStateOf(localBeacon?.colorArgb ?: selectedPreset.defaultColorArgb) }
    var selectedPattern by remember {
        mutableStateOf(
            localBeacon?.let { BeaconVibrationPattern.fromName(it.vibrationPattern) } ?: selectedPreset.defaultPattern
        )
    }

    val colorPalette = listOf(
        0xFFFF1744, // Red
        0xFFFF9100, // Amber
        0xFF00E676, // Green
        0xFF00E5FF, // Cyan
        0xFFD500F9  // Purple
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = Color(selectedColor),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (localBeacon != null) "Update Active Beacon" else "Create Beacon")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select Preset", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BeaconPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                title = preset.defaultTitle
                                selectedColor = preset.defaultColorArgb
                                selectedPattern = preset.defaultPattern
                                isVibrationMenuExpanded = false // Close menu on selection
                            },
                            label = { Text("${preset.iconEmoji} ${preset.label}") }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Beacon Message / Intent") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )

                Text("Beacon Color", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorPalette.forEach { colorArgb ->
                        val isSelected = selectedColor == colorArgb
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(colorArgb), CircleShape)
                                .clickable { selectedColor = colorArgb }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Text("Vibration Rhythm & Intensity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                ExposedDropdownMenuBox(
                    expanded = isVibrationMenuExpanded,
                    onExpandedChange = { isVibrationMenuExpanded = !isVibrationMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedPattern.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVibrationMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = isVibrationMenuExpanded,
                        onDismissRequest = { isVibrationMenuExpanded = false }
                    ) {
                        BeaconVibrationPattern.entries.forEach { pattern ->
                            DropdownMenuItem(
                                text = { Text(pattern.displayName) },
                                onClick = {
                                    selectedPattern = pattern
                                    isVibrationMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onTestVibrate(selectedPattern.pattern) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test Vibration")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onActivate(title.ifBlank { "Active Beacon" }, selectedColor, selectedPattern.name)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(selectedColor))
            ) {
                Text(if (localBeacon != null) "Update Beacon" else "Activate Beacon", color = Color.White)
            }
        },
        dismissButton = {
            Row {
                if (localBeacon != null) {
                    TextButton(
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop Beacon")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun Color.Companion.lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
