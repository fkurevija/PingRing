package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.session.BeaconInfo
import com.fkurevija.pingring.data.chat.BeaconVibrationPattern

/**
 * The colour this beacon is sent in.
 *
 * Every surface that shows a beacon - the button that stops it, the
 * ring, the chat entry, the peer's row - takes its colour from here, so they
 * cannot drift apart and leave one of them showing a colour the sender never
 * chose.
 */
internal val BeaconInfo.color: Color get() = Color(colorArgb)

/**
 * The text colour that reads on this one: black or white, whichever has more
 * contrast against it.
 *
 * Material's own contentColorFor switches at a luminance of 0.5, which is
 * tuned for tonal surfaces and leaves white on a bright beacon green at 1.7:1 -
 * the least readable pairing on a button that is meant to be read at a glance.
 * Comparing the two contrast ratios instead follows the colour wherever the
 * user has put it.
 */
internal fun Color.readableOnIt(): Color {
    val contrastOnBlack = (luminance() + 0.05f) / 0.05f
    val contrastOnWhite = 1.05f / (luminance() + 0.05f)
    return if (contrastOnBlack >= contrastOnWhite) Color.Black else Color.White
}

internal val BeaconVibrationPattern.labelRes: Int
    get() = when (this) {
        BeaconVibrationPattern.SOS_URGENT -> R.string.vibration_sos_urgent
        BeaconVibrationPattern.RAPID_ALERT -> R.string.vibration_rapid_alert
        BeaconVibrationPattern.STEADY_PULSE -> R.string.vibration_steady_pulse
        BeaconVibrationPattern.GENTLE_PULSE -> R.string.vibration_gentle_pulse
        BeaconVibrationPattern.SILENT -> R.string.vibration_none
    }

/**
 * A live beacon, rendered inside the chat transcript.
 *
 * Built to be unmistakably not a chat bubble: it always spans the full width
 * rather than sitting left or right, it carries a full-strength band of the
 * beacon's own colour rather than a 15% tint, and it has a square silhouette
 * where messages have a 14 dp bubble. Someone glancing at the chat should see
 * a beacon before they read a single word of it.
 *
 * Internal rather than private so a test can compose it directly, for the same
 * reason [PeerList] is: reaching this through [RadarScreen] needs a
 * repository, and the thing worth testing here is that silencing changes the
 * alarm control WITHOUT removing the beacon from the transcript.
 */
@Composable
internal fun ActiveBeaconEntry(
    beacon: BeaconInfo,
    silenced: Boolean,
    onToggleSilenced: (Boolean) -> Unit,
) {
    val beaconColor = beacon.color
    val timeStr = chatHistoryTimestamp(beacon.timestamp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                beaconColor.copy(alpha = 0.18f),
                RoundedCornerShape(8.dp)
            )
            .border(1.dp, beaconColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Campaign,
            contentDescription = stringResource(R.string.active_beacon),
            tint = beaconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    beacon.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = beaconColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                stringResource(
                    R.string.beacon_sender_pattern,
                    beacon.senderName,
                    stringResource(BeaconVibrationPattern.fromName(beacon.vibrationPattern).labelRes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Stated in words as well as shown by the icon, because the icon is
            // the only signal on this row that changes and a member who has just
            // silenced something needs to be able to confirm it took.
            if (beacon.isActive && silenced) {
                Text(
                    stringResource(R.string.beacon_silenced_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (beacon.isActive) {
            IconButton(
                onClick = { onToggleSilenced(!silenced) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (silenced) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    // Names both the action and what it means, so the state is not
                    // carried by the icon shape alone.
                    contentDescription = stringResource(
                        if (silenced) R.string.unsilence_beacon else R.string.silence_beacon,
                        beacon.title,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun BeaconActionButton(
    localBeacon: BeaconInfo?,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeBeacon = localBeacon?.takeIf { it.isActive }
    Button(
        onClick = if (activeBeacon != null) onStop else onOpen,
        modifier = modifier,
        colors = activeBeacon?.let { beacon ->
            ButtonDefaults.buttonColors(
                containerColor = beacon.color,
                contentColor = beacon.color.readableOnIt(),
            )
        } ?: ButtonDefaults.buttonColors(),
    ) {
        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (activeBeacon != null) R.string.stop else R.string.beacons),
            textAlign = TextAlign.Center,
        )
    }
}
