package com.fkurevija.pingring.ui.screens

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.data.ConnectionMethodPolicy
import com.fkurevija.pingring.data.ConnectionMethodPolicy.Unavailable
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.R
import com.fkurevija.pingring.ui.permissions.rememberUwbAvailability

/**
 * The settings for a PingRing that is live right now.
 *
 * Deliberately short. This used to carry nine controls, four of which are gone
 * because the behaviour they configured is gone: a session is no longer
 * advertised as findable, joins are no longer held one at a time for a human,
 * the host no longer owns what anybody tracks, and GPS is no longer something
 * a guest has to be told about. What is left is the ring-wide lockout, the one
 * ranging switch that can actually be turned off, and a way out.
 *
 * Chat settings are not here either - bubble colour and chat notifications live
 * in the chat settings window on the radar, next to the chat they change, rather
 * than two screens away from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingRingSettingsScreen(
    event: Event,
    uwbHardwareSupported: Boolean,
    isHost: Boolean,
    onBack: () -> Unit,
    /**
     * Opens the app's own settings.
     *
     * Which is app-wide rather than about this ring: theme, language,
     * accessibility, notifications. Pushed onto the same back stack as this
     * screen rather than launched as a new task, so backing out of it returns
     * to whichever PingRing screen sent the user here.
     */
    onOpenAppSettings: () -> Unit,
    onSave: (Event) -> Unit,
    onEndEvent: () -> Unit,
    onLeaveEvent: () -> Unit,
) {
    val context = LocalContext.current
    var acceptsNewMembers by remember(event) { mutableStateOf(event.acceptsNewMembers) }
    val uwbAvailability = rememberUwbAvailability(uwbHardwareSupported)
    var useUwb by remember(event, uwbAvailability.isUsable) { mutableStateOf(event.useUwb && uwbAvailability.isUsable) }
    var showEndConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // No title. The screen is reached from the radar, which already says
            // which PingRing this is, and a second copy of the name at the top of
            // every settings screen is the thing that made this area feel like
            // it was navigating rather than configuring. The back arrow carries
            // all the orientation this needs.
            TopAppBar(
                // Empty rather than omitted: the parameter has no default, and
                // an empty composable is what "no title" means here.
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSave(event.copy(
                            acceptsNewMembers = acceptsNewMembers,
                            description = context.getString(if (acceptsNewMembers) R.string.pingring_event else R.string.closed),
                            useUwb = useUwb,
                        ))
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.save_changes))
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
            // End/Leave, first thing on the screen.
            //
            // It used to sit under a "Danger Zone" heading at the bottom, which
            // is the shape settings screens use for actions that are rare and
            // destructive - and this is neither: on a ring you will use it at
            // least once, and scrolling to find it made a one-tap exit a hunt.
            // The heading goes with the position: the button is red, it says what
            // it does, and the consequence is written underneath it rather than
            // above.
            //
            // The radar's navigation bar back arrow still offers the same
            // action, so the two routes cannot disagree.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(
                        onClick = { showEndConfirmation = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(if (isHost) R.string.end_pingring else R.string.leave_pingring))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isHost) {
                            stringResource(R.string.end_pingring_description)
                        } else {
                            stringResource(R.string.leave_pingring_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.connectivity_participation),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.prevent_new_users_joining), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.prevent_new_users_joining_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Switched round so the control reads the same way as the
                        // intent: turning it *on* closes the ring. Labelled "accept
                        // new members" it had to be read as a double negative, and
                        // the default - open - looked like the setting that needed
                        // switching off. The stored field is still
                        // [Event.acceptsNewMembers] so nothing on the wire changes;
                        // only the direction the control reads in is inverted.
                        Switch(
                            checked = !acceptsNewMembers,
                            onCheckedChange = { acceptsNewMembers = !it },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.prevent_new_users_joining)
                            },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Driven by ConnectionMethodPolicy rather than by a bare
                    // hardware check, so the row says *why* it cannot be on -
                    // no radio, permission not granted, or an Android version
                    // too old to grant it. The last of those was previously
                    // invisible: the feature flag said yes, the permission did
                    // not exist, and the switch simply refused to stay on.
                    val capabilities = remember(uwbAvailability) {
                        ConnectionMethodPolicy.Capabilities(
                            apiLevel = Build.VERSION.SDK_INT,
                            uwbHardwareSupported = uwbAvailability.hardwareSupported,
                            uwbPermissionGranted = uwbAvailability.permissionGranted,
                        )
                    }
                    val selection = remember(capabilities, useUwb) {
                        ConnectionMethodPolicy.chooseMethod(
                            capabilities = capabilities,
                            preferences = ConnectionMethodPolicy.Preferences(
                                uwbRangingEnabled = useUwb,
                            ),
                        )
                    }
                    val uwbRefusal = ConnectionMethodPolicy.chooseMethod(
                        capabilities = capabilities,
                        preferences = ConnectionMethodPolicy.Preferences(uwbRangingEnabled = true),
                    ).unranged

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!capabilities.canRange) Modifier.clickable {
                                uwbAvailability.onRequestAccess()
                            } else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.uwb), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                uwbRefusal?.let { stringResource(it.descriptionRes) }
                                    ?: stringResource(R.string.sub_metre_uwb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = selection.usesUwb,
                            enabled = capabilities.canRange,
                            onCheckedChange = { useUwb = it },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.uwb)
                            },
                        )
                    }
                }
            }

// A way out to the settings that are about the app rather than
            // about this ring: theme, language, notifications, accessibility.
            // They live two screens below Home, which is exactly why they were
            // hard to find at the moment somebody standing in a crowd with a
            // bright screen actually wanted one. A link, not a copy - the same
            // argument that keeps every event switch on one screen.
            //
            // No heading above the button: it is already labelled "App Settings",
            // and repeating those words two lines up said the same thing twice.
            // The button is the whole card, so the card needs no name of its own.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_settings), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showEndConfirmation) {
        EndPingRingDialog(
            isHost = isHost,
            onConfirm = {
                showEndConfirmation = false
                if (isHost) onEndEvent() else onLeaveEvent()
            },
            onDismiss = { showEndConfirmation = false }
        )
    }
}

private val Unavailable.descriptionRes: Int
    get() = when (this) {
        Unavailable.NO_HARDWARE -> R.string.uwb_no_hardware
        Unavailable.NO_PERMISSION -> R.string.uwb_needs_permission
        Unavailable.UNSUPPORTED_OS_VERSION -> R.string.uwb_os_too_old
    }

