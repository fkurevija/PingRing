package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.data.Event

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EventSettingsScreen(
    event: Event,
    onBack: () -> Unit,
    onSave: (Event) -> Unit
) {
    var name by remember(event) { mutableStateOf(event.name) }
    var visible by remember { mutableStateOf(true) }
    var useUwb by remember(event) { mutableStateOf(event.useUwb) }
    var useWifiDirect by remember(event) { mutableStateOf(event.useWifiDirect) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ping-Ring settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
      Column(Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Ping-Ring name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Accept new members", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = visible, onCheckedChange = { visible = it })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ultra-wideband (UWB)", style = MaterialTheme.typography.titleMedium)
                Text("High-precision ranging.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = useUwb, onCheckedChange = { useUwb = it })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("High Bandwidth (Wi-Fi)", style = MaterialTheme.typography.titleMedium)
                Text("Faster coordination.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = useWifiDirect, onCheckedChange = { useWifiDirect = it })
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    onSave(event.copy(
                        name = name.trim(), 
                        description = if (visible) "PingRing Event" else "Closed",
                        useUwb = useUwb,
                        useWifiDirect = useWifiDirect
                    ))
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
      }
    }
}
