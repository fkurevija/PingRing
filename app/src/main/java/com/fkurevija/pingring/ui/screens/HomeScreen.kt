package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ping-Ring", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Stay connected nearby.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) {
            Text("Host a Ping-Ring")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text("Join a Ping-Ring")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }
    }
}
