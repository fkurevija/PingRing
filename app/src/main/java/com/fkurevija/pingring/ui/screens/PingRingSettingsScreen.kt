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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingRingSettingsScreen(
    event: Event,
    localPeerId: String,
    chatBubbleColorArgb: Long?,
    onChatBubbleColorChange: (Long?) -> Unit,
    uwbHardwareSupported: Boolean,
    isHost: Boolean,
    onBack: () -> Unit,
    onSave: (Event) -> Unit,
    onEndEvent: () -> Unit,
    onLeaveEvent: () -> Unit,
) {
    val context = LocalContext.current
    var acceptsNewMembers by remember(event) { mutableStateOf(event.acceptsNewMembers) }
    var isDiscoverable by remember(event) { mutableStateOf(event.isDiscoverable) }
    var requiresApproval by remember(event) { mutableStateOf(event.requiresApproval) }
    var trackHost by remember(event) { mutableStateOf(event.trackHost) }
    val uwbAvailability = rememberUwbAvailability(uwbHardwareSupported)
    var useUwb by remember(event, uwbAvailability.isUsable) { mutableStateOf(event.useUwb && uwbAvailability.isUsable) }
    var useWifiDirect by remember(event) { mutableStateOf(event.useWifiDirect) }
    var chatNotificationsEnabled by remember(event) { mutableStateOf(event.chatNotificationsEnabled) }
    var selectedChatColor by remember(event.id) { mutableStateOf(chatBubbleColorArgb) }
    val chatColorPresets = remember(event.id, localPeerId) {
        ChatBubblePalette.forMember(event.id, localPeerId)
    }
    var previousChatColor by remember(event.id) { mutableStateOf<Long?>(null) }
    var canUndoChatColor by remember(event.id) { mutableStateOf(false) }
    var showCustomChatColorPicker by remember { mutableStateOf(false) }
    var showEndConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(event.id, chatBubbleColorArgb) {
        selectedChatColor = chatBubbleColorArgb
    }
    fun chooseChatColor(color: Long?) {
        if (color == selectedChatColor) return
        previousChatColor = selectedChatColor
        selectedChatColor = color
        canUndoChatColor = true
        onChatBubbleColorChange(color)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pingring_settings), fontWeight = FontWeight.Bold) },
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
                            isDiscoverable = isDiscoverable,
                            requiresApproval = requiresApproval,
                            trackHost = if (isHost) trackHost else event.trackHost,
                            description = context.getString(if (acceptsNewMembers) R.string.pingring_event else R.string.closed),
                            useUwb = useUwb,
                            useWifiDirect = useWifiDirect,
                            chatNotificationsEnabled = chatNotificationsEnabled
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
                            Text(stringResource(R.string.accept_new_members), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.allow_nearby_discover_join), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = acceptsNewMembers, onCheckedChange = { acceptsNewMembers = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.discoverable), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.discoverable_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isDiscoverable, onCheckedChange = { isDiscoverable = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.approve_each_join), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.approve_each_join_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = requiresApproval, onCheckedChange = { requiresApproval = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.track_host_only), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.track_host_only_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = trackHost,
                            enabled = isHost,
                            onCheckedChange = { trackHost = it },
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.track_host_only)
                            }
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
                    val uwbRefusal = selection.unranged

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tapping asks for the permission only when that is
                            // what is missing. Tapping a device with no UWB radio
                            // used to promise a prompt that could not appear.
                            .clickable(enabled = uwbRefusal == Unavailable.NO_PERMISSION) {
                                uwbAvailability.onRequestAccess()
                            },
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
                            onCheckedChange = { useUwb = it }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    val wifiDirectSupported = remember(context) {
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ||
                                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
                    }

                    // Not a switch, and deliberately so. Nearby Connections
                    // carries its payload channel over Wi-Fi Direct and exposes
                    // no way to turn that off - AdvertisingOptions has no such
                    // setting - so this was a control that could only ever be on.
                    // Offering it invited somebody to disable high-bandwidth
                    // transport and watch nothing change, which is worse than
                    // not mentioning it.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.high_bandwidth_wifi), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (wifiDirectSupported) {
                                    stringResource(R.string.wifi_transport_always_on)
                                } else {
                                    stringResource(R.string.not_supported_on_device)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.always_on),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.chat_notifications), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.event_chat_notifications_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = chatNotificationsEnabled, onCheckedChange = { chatNotificationsEnabled = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.max_capacity), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.max_capacity_description, MAX_PARTICIPANTS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.chat_bubble_colour), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.chat_bubble_colour_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canUndoChatColor) {
                            TextButton(
                                onClick = {
                                    chooseChatColor(previousChatColor)
                                    canUndoChatColor = false
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.undo))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        chatColorPresets.forEach { colorArgb ->
                            val selected = selectedChatColor == colorArgb
                            val presetDescription = stringResource(
                                R.string.set_chat_bubble_colour_to,
                                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(colorArgb), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .semantics { contentDescription = presetDescription }
                                    .clickable { chooseChatColor(colorArgb) },
                            )
                        }
                        TextButton(onClick = { showCustomChatColorPicker = true }) {
                            Text(stringResource(R.string.custom))
                        }
                    }
                    TextButton(onClick = { chooseChatColor(null) }, enabled = selectedChatColor != null) {
                        Text(stringResource(R.string.use_theme_colour))
                    }
                }
            }

            // End/Leave live here rather than on the radar, next to the other
            // settings for a live PingRing. It is destructive and awkward to
            // undo, so it reads better grouped with settings than sitting in a
            // permanent bar above the chat where a stray tap could reach it.
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
                    Text(
                        stringResource(R.string.danger_zone),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isHost) {
                            stringResource(R.string.end_pingring_description)
                        } else {
                            stringResource(R.string.leave_pingring_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
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
                }
            }
        }
    }

    if (showCustomChatColorPicker) {
        ChatBubbleColorPickerDialog(
            onDismiss = { showCustomChatColorPicker = false },
            onColorSelected = ::chooseChatColor,
        )
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

@Composable
private fun ChatBubbleColorPickerDialog(
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    val selectedColor = Color.hsv(hue, 0.72f, 0.72f)
    val wheelColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    val hueWheelDescription = stringResource(R.string.chat_colour_hue_wheel)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(stringResource(R.string.custom_chat_colour)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .semantics { contentDescription = hueWheelDescription }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val fromCentre = tap - centre
                                if (kotlin.math.sqrt(fromCentre.x * fromCentre.x + fromCentre.y * fromCentre.y) <= minOf(size.width, size.height) / 2f) {
                                    hue = ((Math.toDegrees(kotlin.math.atan2(fromCentre.y.toDouble(), fromCentre.x.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                                    onColorSelected(Color.hsv(hue, 0.72f, 0.72f).toArgb().toLong() and 0xFFFF_FFFFL)
                                }
                            }
                        },
                ) {
                    drawCircle(brush = Brush.sweepGradient(wheelColors))
                    drawCircle(color = selectedColor, radius = size.minDimension * 0.12f)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.choose_hue), style = MaterialTheme.typography.bodyMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private val Unavailable.descriptionRes: Int
    get() = when (this) {
        Unavailable.NO_HARDWARE -> R.string.uwb_no_hardware
        Unavailable.NO_PERMISSION -> R.string.uwb_needs_permission
        Unavailable.UNSUPPORTED_OS_VERSION -> R.string.uwb_os_too_old
    }
