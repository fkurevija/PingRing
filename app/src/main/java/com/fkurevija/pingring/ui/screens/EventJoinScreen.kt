package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.ui.theme.PingRingTheme

@Composable
fun EventJoinScreen(
    onJoinEvent: (String) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Join an Event",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = eventCode,
            onValueChange = { eventCode = it },
            label = { Text("Event Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { if (eventCode.isNotBlank()) onJoinEvent(eventCode) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join")
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { /* TODO: Implement QR Scan */ }) {
            Text("Scan QR Code")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EventJoinScreenPreview() {
    PingRingTheme {
        EventJoinScreen(onJoinEvent = {})
    }
}
