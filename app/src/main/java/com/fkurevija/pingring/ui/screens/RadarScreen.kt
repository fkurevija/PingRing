package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.RadarPeerUpdate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    eventId: String,
    isVisible: Boolean,
    isHost: Boolean,
    trackHost: Boolean,
    discoveryRepository: DiscoveryRepository?,
    onVisibilityToggle: (Boolean) -> Unit,
    onEventSettings: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit
) {
    var showEndConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible, discoveryRepository) {
        if (isVisible) discoveryRepository?.startDiscovery() else discoveryRepository?.stopDiscovery()
    }
    DisposableEffect(Unit) {
        onDispose { discoveryRepository?.stopDiscovery() }
    }

    val peers by discoveryRepository?.peerUpdates?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<RadarPeerUpdate>()) }
    val connectivity by discoveryRepository?.connectivityStatus?.collectAsState()
        ?: remember { mutableStateOf(ConnectivityStatus(true, true)) }
    val haptic = LocalHapticFeedback.current
    val nearest = peers.minByOrNull { it.distance ?: Float.MAX_VALUE }

    LaunchedEffect(nearest?.peerId, nearest?.distance) {
        val distance = nearest?.distance ?: return@LaunchedEffect
        if (isVisible && distance < 2f) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PingRing", fontWeight = FontWeight.Bold)
                        Text("Ping-Ring $eventId", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isHost) showEndConfirmation = true else onLeaveEvent()
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = if (isHost) "End Ping-Ring" else "Leave Ping-Ring"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onEventSettings) { Text("Settings") }
                    IconButton(onClick = { onVisibilityToggle(!isVisible) }) {
                        Icon(
                            if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (isVisible) "Go invisible" else "Go visible"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = {
                        if (isHost) showEndConfirmation = true else onLeaveEvent()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (isHost) "End Ping-Ring" else "Leave Ping-Ring") }
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            val wide = maxWidth >= 600.dp
            if (!isVisible) {
                InvisibleState(Modifier.fillMaxSize())
            } else if (wide) {
                val paneWidth = (maxWidth - 20.dp) / 2
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    RadarContent(
                        if (isHost || !trackHost) nearest else peers.firstOrNull(),
                        peers.isNotEmpty(),
                        Modifier.width(paneWidth)
                    )
                    PeerList(peers, connectivity, discoveryRepository, Modifier.width(paneWidth))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    RadarContent(
                        if (isHost || !trackHost) nearest else peers.firstOrNull(),
                        peers.isNotEmpty(),
                        Modifier.fillMaxWidth().height(320.dp)
                    )
                    PeerList(peers, connectivity, discoveryRepository, Modifier.fillMaxWidth())
                }
            }

            if (showEndConfirmation) {
                AlertDialog(
                    onDismissRequest = { showEndConfirmation = false },
                    title = { Text("End this Ping-Ring?") },
                    text = {
                        Text("This removes every member from the Ping-Ring and returns them to the home page.")
                    },
                    confirmButton = {
                        Button(onClick = {
                            showEndConfirmation = false
                            onEndEvent()
                        }) { Text("End Ping-Ring") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEndConfirmation = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
private fun RadarContent(peer: RadarPeerUpdate?, hasPeers: Boolean, modifier: Modifier) {
    val hotness = ((peer?.rssi ?: -100) + 100).coerceIn(0, 100) / 100f
    val ringColor = Color.Lerp(Color(0xFF3F51B5), Color(0xFF00C853), hotness)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            if (hasPeers) peer?.name ?: "Nearby peer" else "Looking for your people…",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        ProximityRing(
            progress = hotness,
            color = ringColor,
            arrowDegrees = peer?.azimuth ?: 0f,
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
private fun ProximityRing(progress: Float, color: Color, arrowDegrees: Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * .08f
            drawCircle(color.copy(alpha = .12f), style = Stroke(stroke))
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
        Box(Modifier.size(74.dp).background(color.copy(alpha = .14f), CircleShape))
    }
}

@Composable
private fun PeerList(
    peers: List<RadarPeerUpdate>,
    connectivity: ConnectivityStatus,
    repository: DiscoveryRepository?,
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
        Text("Nearby (${peers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(if (peer.proximity == "Hot") Color(0xFF00C853) else Color(0xFF3F51B5), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.fillMaxWidth()) {
                                Text(peer.name, fontWeight = FontWeight.SemiBold)
                                Text("${peer.proximity} · ${peer.rssi ?: 0} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(peer.distance?.let { "%.1f m".format(it) } ?: "—", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvisibleState(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("You are invisible", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Turn visibility on when you are ready to be found.", textAlign = TextAlign.Center)
    }
}

private fun Color.Companion.Lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
