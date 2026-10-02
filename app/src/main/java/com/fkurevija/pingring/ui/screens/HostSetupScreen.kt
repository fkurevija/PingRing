package com.fkurevija.pingring.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    eventCode: String,
    savedUsername: String,
    discoveryRepository: DiscoveryRepository,
    onEndEvent: () -> Unit,
    onSaveUsername: (String) -> Unit,
    onView: () -> Unit,
    /**
     * Opens PingRing Settings, which owns every event switch.
     *
     * This screen used to carry five of them - Accept New Members, Track Host
     * Only, UWB, Wi-Fi and Chat Notifications - and reached Discoverable and
     * Approve Each Join only through a link. Two copies of one control is how
     * they drift: the pair already disagreed, because the switches added later
     * existed on only one screen and a host who configured here could not see
     * them at all. So the duplicates are gone and this screen is what it is
     * actually for - the code to share, a name, and the way in.
     */
    onOpenSettings: () -> Unit
) {
    val defaultHostName = stringResource(R.string.host_default_name)
    var username by remember(savedUsername, defaultHostName) { mutableStateOf(savedUsername.ifBlank { defaultHostName }) }
    var showEndConfirmation by remember { mutableStateOf(false) }

    // The PingRing is already live on this screen - the host is advertising as
    // soon as it opens - so system back must not slip past the question. The
    // radar is reached from here, and the default pop would otherwise either
    // drop the user out of the app or quietly return to Home with the event
    // still running and nobody told.
    BackHandler { showEndConfirmation = true }

    LaunchedEffect(discoveryRepository) {
        discoveryRepository.startDiscovery()
    }
    // Deliberately no onDispose-triggered stopDiscovery() here: HostSetupScreen
    // and RadarScreen share the same DiscoveryRepository instance for the
    // lifetime of an event, and navigating from this screen to RadarScreen
    // must NOT tear down the connection RadarScreen just took over. Discovery
    // is only stopped explicitly when the user actually ends the event, via
    // onEndEvent (MainActivity) once the confirmation above is accepted.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.host_pingring), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    // Same confirmation as the system back, so the arrow and the
                    // gesture can never disagree about what back means.
                    IconButton(onClick = { showEndConfirmation = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.end_pingring))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.all_settings)) }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    onClick = {
                        val trimmed = username.trim()
                        if (trimmed.isNotBlank()) {
                            onSaveUsername(trimmed)
                            onView()
                        }
                    },
                    enabled = username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.launch_radar), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // QR Code Showcase Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.scan_to_join),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    val qrCodeBitmap = remember(eventCode) {
                        generateQrCode(eventCode).asImageBitmap()
                    }
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrCodeBitmap,
                            contentDescription = stringResource(R.string.qr_code_for_pingring, eventCode),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(14.dp))
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
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Username Field
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username)) },
                placeholder = { Text(stringResource(R.string.username_example)) },
                isError = username.isBlank(),
                supportingText = {
                    if (username.isBlank()) {
                        Text(stringResource(R.string.username_required), color = MaterialTheme.colorScheme.error)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // One link, not a second copy of the switches. Every event switch
            // lives on PingRing Settings, which is one tap away and the only
            // place any of them change.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.event_settings),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.event_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.event_settings), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        if (showEndConfirmation) {
            // Only ever reached by the host: MainActivity sends anyone else back
            // to Home before this screen is shown.
            EndPingRingDialog(
                isHost = true,
                onConfirm = {
                    showEndConfirmation = false
                    onEndEvent()
                },
                onDismiss = { showEndConfirmation = false }
            )
        }
    }
}
