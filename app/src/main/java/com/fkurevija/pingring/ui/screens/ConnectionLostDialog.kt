package com.fkurevija.pingring.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.fkurevija.pingring.R

@Composable
fun ConnectionLostDialog(
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onSilence: () -> Unit,
    connectionRecoveryPromptDelayMillis: Long,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection_lost_title)) },
        text = {
            val delayMinutes = ((connectionRecoveryPromptDelayMillis + 59_999L) / 60_000L).toInt()
            Text(stringResource(R.string.connection_lost_message, delayMinutes))
        },
        confirmButton = { TextButton(onClick = onLeave) { Text(stringResource(R.string.leave_pingring)) } },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.connection_lost_snooze)) }
                TextButton(onClick = onSilence) { Text(stringResource(R.string.connection_lost_silence)) }
            }
        }
    )
}
