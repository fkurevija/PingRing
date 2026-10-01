package com.fkurevija.pingring.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.ConnectionAuthenticationRequest

@Composable
fun ConnectionAuthenticationDialog(
    request: ConnectionAuthenticationRequest,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.connection_authentication_title)) },
        text = {
            Text(stringResource(
                R.string.connection_authentication_message,
                request.deviceName,
                request.authenticationDigits,
            ))
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.connection_digits_match))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.connection_reject))
            }
        },
    )
}