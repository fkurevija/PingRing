package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.radar.LastKnownPeerDirection
import com.combustiblelemonlabs.pingring.data.radar.RadarPeerUpdate
import kotlin.math.atan2

@Composable
internal fun RadarNoticeCards(
    peers: List<RadarPeerUpdate>,
    focusedPeer: RadarPeerUpdate?,
    lastKnownDirection: LastKnownPeerDirection?,
    lastKnownClockMillis: Long,
) {
    val weakSignalPeers = peers.filter { it.signalWeak }
    val trackedWeakPeer = focusedPeer?.takeIf { it.signalWeak }
    val weakSignalMessage = when {
        trackedWeakPeer != null -> stringResource(R.string.radar_weak_tracked_signal, trackedWeakPeer.name)
        weakSignalPeers.isNotEmpty() -> stringResource(R.string.radar_weak_group_signal, weakSignalPeers.size)
        else -> null
    }
    weakSignalMessage?.let { message ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    lastKnownDirection?.let { location ->
        val bearingDegrees = (
            Math.toDegrees(atan2(location.worldDirectionX.toDouble(), location.worldDirectionY.toDouble()))
                .toInt() % 360 + 360
            ) % 360
        val secondsAgo = (
            (lastKnownClockMillis - location.lastDetectedAtMillis).coerceAtLeast(0L) / 1_000L
            ).toInt()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.radar_last_known_direction, location.name, bearingDegrees, secondsAgo),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
