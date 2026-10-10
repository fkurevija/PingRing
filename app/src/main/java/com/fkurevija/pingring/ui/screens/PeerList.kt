package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy.Advice
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PeerListOrderPolicy
import com.fkurevija.pingring.data.RadarPeerUpdate

/**
 * One local-radio notice, at the top of the member list.
 *
 * Every notice says what is *lost* rather than what is broken, because none of
 * them stops the ring working - PingRing is meant to run with no network at all.
 * See [LocalRadioAdvicePolicy] for why that constraint is load-bearing.
 */
@Composable
private fun LocalRadioAdviceCard(
    advice: Advice,
    isError: Boolean,
    onRecheck: () -> Unit,
) {
    val text = when (advice) {
        Advice.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off_discovery)
        Advice.WIFI_OFF -> stringResource(R.string.wifi_off_bluetooth_only)
        Advice.BLE_IDENTITY_UNAVAILABLE -> stringResource(R.string.ble_identity_unavailable)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (advice) {
                    Advice.WIFI_OFF -> Icons.Default.WifiOff
                    else -> Icons.Default.BluetoothDisabled
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(text, modifier = Modifier.weight(1f))
            IconButton(onClick = onRecheck) {
                Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.recheck))
            }
        }
    }
}

/**
 * The member list beside (or below) the radar.
 *
 * Internal rather than private so a test can compose it directly. The
 * surrounding [RadarScreen] is only reachable with a null
 * [com.fkurevija.pingring.data.DiscoveryRepository], which means no peers - and
 * the list is where a member actually appears, gets marked as tracked, and
 * shows its beacon. Testing it here means handing over a
 * [com.fkurevija.pingring.data.RadarPeerUpdate] directly, rather than standing up
 * a transport and waiting for a real peer to show up.
 */
@Composable
internal fun PeerList(
    peers: List<RadarPeerUpdate>,
    connectivity: ConnectivityStatus,
    repository: DiscoveryRepository?,
    activeBeacons: Map<String, BeaconInfo>,
    focusedPeerId: String?,
    onFocusPeer: (String) -> Unit,
    isHost: Boolean = false,
    onRemovePeer: (String) -> Unit = {},
    modifier: Modifier
) {
    var peerPendingRemoval by remember { mutableStateOf<RadarPeerUpdate?>(null) }
    // Signal-strength ordering does not change the selected tracking target.
    val orderedPeers = remember(peers) { PeerListOrderPolicy.bySignalStrength(peers) }

    // One policy decides which notices appear and in what order, rather than two
    // inline conditions that could drift apart. The precedence it encodes - that
    // a dead Bluetooth radio hides the rest - is not obvious from reading the
    // markup, which is why it lives in a tested object.
    val advice = remember(connectivity) { LocalRadioAdvicePolicy.adviceFor(connectivity) }

    Column(modifier.padding(bottom = 8.dp)) {
        advice.forEach { notice ->
            LocalRadioAdviceCard(
                advice = notice,
                // The BLE-identity note is a capability statement, not a fault, so
                // it does not get the alarming error container.
                isError = notice != Advice.BLE_IDENTITY_UNAVAILABLE,
                onRecheck = { repository?.refreshConnectivityStatus() },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isFull = (peers.size + 1) >= MAX_PARTICIPANTS
            Text(
                if (isFull) stringResource(R.string.member_count_full, peers.size + 1, MAX_PARTICIPANTS)
                else stringResource(R.string.member_count, peers.size + 1, MAX_PARTICIPANTS),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        // The list of members, or an empty-state message if nobody else has
        // joined. The empty state fills the remaining height so the sheet can
        // be "pulled up" even when empty, answering the host's gesture with a
        // real surface instead of a tiny sliver at the bottom.
        if (peers.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(orderedPeers, key = { it.peerId }) { peer ->
                    val peerBeacon = activeBeacons[peer.peerId]
                    val isFocused = peer.peerId == focusedPeerId
                    val removePeerDescription = stringResource(R.string.remove_member_action, peer.name)
                    Card(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = isFocused,
                            role = Role.RadioButton,
                            onClick = { onFocusPeer(peer.peerId) },
                        ),
                        colors = when {
                            peerBeacon != null ->
                                CardDefaults.cardColors(containerColor = peerBeacon.color.copy(alpha = 0.12f))
                            isFocused ->
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ->
                                CardDefaults.cardColors()
                        }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val indicatorColor = peerBeacon?.color ?: MaterialTheme.colorScheme.primary
                            Box(Modifier.size(12.dp).background(indicatorColor, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(peer.name, fontWeight = FontWeight.SemiBold)
                                    Text(peer.proximity, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                                    if (isFocused) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.tracking_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (peerBeacon != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "🚨 ${peerBeacon.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = peerBeacon.color,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            peer.distance?.let { distance ->
                                Text(
                                    stringResource(R.string.distance_meters, distance),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (isHost) {
                                IconButton(
                                    onClick = { peerPendingRemoval = peer },
                                    modifier = Modifier.semantics {
                                        contentDescription = removePeerDescription
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.PersonRemove,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_members_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        peerPendingRemoval?.let { peer ->
            AlertDialog(
                onDismissRequest = { peerPendingRemoval = null },
                title = { Text(stringResource(R.string.remove_member_title)) },
                text = {
                    Text(stringResource(R.string.remove_member_confirmation, peer.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRemovePeer(peer.peerId)
                            peerPendingRemoval = null
                        }
                    ) {
                        Text(stringResource(R.string.remove_member_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { peerPendingRemoval = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
