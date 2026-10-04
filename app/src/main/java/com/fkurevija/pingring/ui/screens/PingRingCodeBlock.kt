package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R

/**
 * The invitation as a guest receives it: a QR code, the code to read out, and how
 * many people reading it can actually come.
 *
 * Shown twice - on Host Setup before the radar starts, and in the radar's own QR
 * dialog once it has. Those were separate implementations and they drifted: the
 * host screen labelled the code and put it in a monospace chip, the dialog showed
 * a bare letter-spaced number with nothing naming it. The same six characters
 * read two different ways depending on which screen you were looking at, which
 * is worse than either treatment alone - a guest told "it's the code under the
 * QR code" would find no such label on the host's radar.
 *
 * So this is one block, used twice, and the only thing that varies is how big
 * the QR code is drawn. Capacity is here for the same reason the code is: it is
 * part of the invitation being read out, not a setting on the screen happening
 * to show it. It is stated nowhere else on the radar, so a host telling someone
 * "come to my ring" had no capacity to quote without leaving the radar.
 */
@Composable
fun PingRingCodeBlock(
    eventCode: String,
    inviteSecret: String?,
    modifier: Modifier = Modifier,
    qrSize: Dp = 150.dp,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Remembered on the encoded value rather than on the bitmap: a host
        // rotating the phone or the theme changing re-composes this, and
        // re-encoding a QR code is the expensive part of the block.
        val qrValue = remember(eventCode, inviteSecret) {
            PingRingInvite(eventCode, inviteSecret).toQrValue()
        }
        val qrCodeBitmap = remember(qrValue) {
            generateQrCode(qrValue).asImageBitmap()
        }
        Box(
            modifier = Modifier
                .size(qrSize)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = qrCodeBitmap,
                // The code is an image, so its content description is the only
                // thing carrying it to a screen reader.
                contentDescription = stringResource(R.string.qr_code_for_pingring, eventCode),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(12.dp))
// Label above, value below, and the two sit 4dp apart because they are
        // one unit - the same pairing the host screen uses throughout.
        Text(
            stringResource(R.string.pingring_code),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = eventCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // Monospace, not letter spacing. The code is grouped in two runs
                // of three by how it is read aloud, and a monospace face makes
                // O/0 and I/1 distinguishable at a glance, which letter spacing
                // does not.
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(12.dp))

        // Capacity, directly under the code it qualifies, and phrased exactly as
        // Host Setup phrases it. It is a property of the ring rather than a
        // setting, so nothing here is interactive.
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GroupAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.max_capacity),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.max_capacity_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
