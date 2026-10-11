package com.fkurevija.pingring.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.session.SessionManager

/**
 * The confirmation shown before a live PingRing is ended or left, whichever
 * way the user asked to leave it: the screen's own button, its back arrow, or
 * the system back button/gesture (both of which end up here through a
 * `BackHandler` on the screens that run a PingRing).
 *
 * It is role-aware on purpose. A guest cannot end a PingRing - only the host
 * can - and sending one down the host path would call
 * [com.fkurevija.pingring.data.session.SessionManager.endHostedEvent], which asserts
 * the session is hosted and throws. So a guest is asked about *leaving*, and
 * confirming calls [onConfirm] only for the action that role is allowed to
 * take.
 */
@Composable
fun EndPingRingDialog(
    isHost: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isHost) R.string.end_pingring_question else R.string.leave_pingring_question)) },
        text = {
            Text(
                if (isHost) {
                    stringResource(R.string.end_pingring_description)
                } else {
                    stringResource(R.string.leave_pingring_description)
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(if (isHost) R.string.end_pingring else R.string.leave)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}