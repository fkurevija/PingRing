package com.fkurevija.pingring.ui.screens

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.session.Event

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarTopAppBar(
    eventId: String,
    peerCount: Int,
    maxPeers: Int,
    isHost: Boolean,
    acceptsNewMembers: Boolean,
    onShowEndConfirmation: () -> Unit,
    onShowMemberSheet: () -> Unit,
    onShowQrCode: () -> Unit,
    onEventSettings: () -> Unit,
    hideMemberCount: Boolean = false,
) {
    TopAppBar(
        title = {
            // Title shows the event code and member count status
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "Code: $eventId",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!hideMemberCount) {
                    val isFull = peerCount >= maxPeers
                    Text(
                        text = stringResource(
                            if (isFull) R.string.member_count_full else R.string.member_count,
                            peerCount,
                            maxPeers
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onShowEndConfirmation) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(if (isHost) R.string.end_pingring else R.string.leave_pingring)
                )
            }
        },
        actions = {
            // Member list button (always shown for consistency)
            IconButton(onClick = onShowMemberSheet) {
                Icon(
                    Icons.Default.People,
                    contentDescription = stringResource(R.string.show_members)
                )
            }
            if (acceptsNewMembers) {
                IconButton(onClick = onShowQrCode) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = stringResource(R.string.show_join_qr)
                    )
                }
            }
            if (isHost) {
                TextButton(onClick = onEventSettings) {
                    Text(stringResource(R.string.settings))
                }
            }
        }
    )
}
