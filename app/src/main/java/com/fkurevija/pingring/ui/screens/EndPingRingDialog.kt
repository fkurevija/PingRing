package com.fkurevija.pingring.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The confirmation shown before a live PingRing is ended or left, whichever
 * way the user asked to leave it: the screen's own button, its back arrow, or
 * the system back button/gesture (both of which end up here through a
 * `BackHandler` on the screens that run a PingRing).
 *
 * It is role-aware on purpose. A guest cannot end a PingRing - only the host
 * can - and sending one down the host path would call
 * [com.fkurevija.pingring.data.SessionManager.endHostedEvent], which asserts
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
        title = { Text(if (isHost) "End this PingRing?" else "Leave this PingRing?") },
        text = {
            Text(
                if (isHost) {
                    "This removes every member from the PingRing and returns them to the home page."
                } else {
                    "You will stop sharing your position with this PingRing. The host and the other members are not affected."
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(if (isHost) "End PingRing" else "Leave") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}