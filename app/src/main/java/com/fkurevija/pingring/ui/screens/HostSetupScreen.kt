package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.fkurevija.pingring.data.DiscoveryRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    eventCode: String,
    discoveryRepository: DiscoveryRepository,
    onBack: () -> Unit,
    onEventChange: (String, Boolean) -> Unit,
    onView: () -> Unit
) {
    var name by remember { mutableStateOf("My Ping-Ring") }
    var trackHost by remember { mutableStateOf(false) }

    LaunchedEffect(discoveryRepository, trackHost) {
        discoveryRepository.startDiscovery()
    }
    DisposableEffect(discoveryRepository) {
        onDispose { discoveryRepository.stopDiscovery() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create a Ping-Ring") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Scan to join", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = generateQrCode(eventCode).asImageBitmap(),
                contentDescription = "QR code for Ping-Ring $eventCode",
                modifier = Modifier.align(Alignment.CenterHorizontally).height(220.dp)
            )
            Text(
                eventCode,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onEventChange(it.trim(), trackHost)
                },
                label = { Text("Ping-Ring name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Track host", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Joined users track only you. Your radar still tracks everyone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = trackHost,
                    onCheckedChange = {
                        trackHost = it
                        onEventChange(name.trim(), it)
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onView,
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("View Ping-Ring") }
        }
    }
}

private fun generateQrCode(value: String): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
