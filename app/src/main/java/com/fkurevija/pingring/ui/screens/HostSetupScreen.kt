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
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.R
import androidx.compose.ui.res.pluralStringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    eventCode: String,
    inviteSecret: String? = null,
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
    onOpenSettings: () -> Unit,
    pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
    onApproveJoinRequest: (String) -> Unit = {},
    onDenyJoinRequest: (String) -> Unit = {},
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
            )
        }
    ) { padding ->
        // Shaped like the join screen: the same 20dp inset, the same centred
        // column, the same explicit Spacers between blocks, and the primary
        // action inline rather than pinned to a bottom bar. The two screens ask
        // for the same thing from opposite directions, and a host who has just
        // read a code out to somebody should be looking at the same shape they
        // will see a minute later when they scan it.
        //
        // The scroll stays as a guard, not as a habit. Everything here fits a
        // phone with room to spare, so nothing scrolls in the resting state and
        // anEmptyUsernameErrorAndThePrimaryActionBothFitWithoutScrolling says
        // so. What it is for is the one thing here that grows at runtime: the
        // queue of people waiting to be admitted, each a full row with Allow
        // and Deny on it. Clip those and nobody can get into the ring, so the
        // page gives way before the approvals do.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pendingJoinRequests.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            pluralStringResource(R.plurals.join_request_count, pendingJoinRequests.size, pendingJoinRequests.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        pendingJoinRequests.forEach { request ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(request.deviceName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { onDenyJoinRequest(request.endpointId) }) {
                                    Text(stringResource(R.string.deny))
                                }
                                Button(onClick = { onApproveJoinRequest(request.endpointId) }) {
                                    Text(stringResource(R.string.allow))
                                }
                            }
                        }
                    }
                }
            }

            // Spacers, not spacedBy: the join screen sets its rhythm with
            // explicit ones and matching it here is the point of this layout.
            Spacer(Modifier.height(12.dp))

            // One panel, the way the join screen has one. That screen puts its
            // heading and both of its fields inside a single card and leaves only
            // the buttons outside; this one had four separate cards for the same
            // job, which made one short form read as four unrelated blocks.
            //
            // Merging them also buys the vertical room the one-page promise
            // needed: three card paddings and two card gaps disappear into three
            // dividers, which is cheaper than the cards they replace.
            //
            // The queue of waiting devices stays in a card of its own. It is
            // transient, it appears and disappears on its own, and it is an
            // interruption rather than part of the form - folding it in would
            // imply setting up a ring and being asked to join one are the same
            // screen.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.scan_to_join),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    val qrValue = remember(eventCode, inviteSecret) {
                        PingRingInvite(eventCode, inviteSecret).toQrValue()
                    }
                    val qrCodeBitmap = remember(qrValue) {
                        generateQrCode(qrValue).asImageBitmap()
                    }
                    Box(
                        modifier = Modifier
                            .size(150.dp)
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
                    Spacer(Modifier.height(12.dp))
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

                    // Capacity, directly under the code it qualifies.
                    //
                    // It belongs here rather than at the foot of the panel because
                    // it is the same sentence as the code above it: this is the code
                    // to read out, and this is how many people reading it can
                    // actually come. Separated by a username field, the two read as
                    // unrelated facts about the host.
                    //
                    // It is a property of the ring, not something to configure -
                    // there is no control next to it, and never was - so putting it
                    // under a heading on the settings screen made it look like a
                    // setting that could be changed.
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

                    HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // Username Field. Inside the panel, where the join screen keeps its
                    // two fields, rather than loose below it.
                    //
                    // The error is a sibling Text rather than the TextField's
                    // supportingText slot. supportingText is measured into a
                    // reserved strip inside the field, and a message that only
                    // appears once the field is empty has been seen clipped by a
                    // few pixels at the bottom on a real device. Laid out as
                    // ordinary content it takes its height from the text at
                    // whatever font scale the phone is using.
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.username)) },
                            placeholder = { Text(stringResource(R.string.username_example)) },
                            isError = username.isBlank(),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (username.isBlank()) {
                            Text(
                                stringResource(R.string.username_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                            )
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // One link, not a second copy of the switches. Every event switch
                    // lives on PingRing Settings, which is one tap away and the only
                    // place any of them change.
                    //
                    // No heading above the button: it is already labelled "PingRing
                    // Settings", and a title repeating those words two lines above
                    // it said the same thing twice.
                    //
                    // The hint goes below the button, where it captions it: it
                    // answers what tapping will take you to, which is what the
                    // reader is looking at once they have read the label.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pingring_settings), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.event_settings_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // The primary action, inline and last, the way the join screen puts
            // its own. It was pinned to a bottom bar, which cost the page ~84dp
            // it could not use and made this the only one of the two screens
            // whose call to action lived somewhere else.
            //
            // Guarded on a trimmed name as well as enabled on a blank one, so a
            // name of only spaces cannot open a session called " ".
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val trimmed = username.trim()
                    if (trimmed.isNotBlank()) {
                        onSaveUsername(trimmed)
                        onView()
                    }
                },
                enabled = username.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    stringResource(R.string.launch_radar),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
