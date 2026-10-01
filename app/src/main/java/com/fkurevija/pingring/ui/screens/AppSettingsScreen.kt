package com.fkurevija.pingring.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.BuildConfig
import com.fkurevija.pingring.DeviceEnvironment
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.data.ChatBubbleColorPreset
import com.fkurevija.pingring.data.ThemeMode
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    appearanceSettings: AppearanceSettingsRepository,
    onOpenAccessibility: () -> Unit,
    onBack: () -> Unit
) {
    var hapticsEnabled by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    val chatNotificationsEnabled by appearanceSettings.chatNotificationsEnabled.collectAsState(initial = true)
    val notificationSoundEnabled by appearanceSettings.notificationSoundEnabled.collectAsState(initial = true)
    val notificationVibrationEnabled by appearanceSettings.notificationVibrationEnabled.collectAsState(initial = true)
    val chatBubbleColor by appearanceSettings.chatBubbleColor.collectAsState(initial = null)
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var selectedTheme by remember(themeMode) { mutableStateOf(themeMode) }
    var showCustomChatColorPicker by remember { mutableStateOf(false) }
    val lanDebugEnabled by appearanceSettings.lanDebugEnabled.collectAsState(initial = false)
    val debuggingActive by rememberDebuggingActive()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
            // Appearance Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Appearance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    ExposedDropdownMenuBox(
                        expanded = themeMenuExpanded,
                        onExpandedChange = { themeMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedTheme.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Theme Mode") },
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeMenuExpanded)
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = themeMenuExpanded,
                            onDismissRequest = { themeMenuExpanded = false }
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        selectedTheme = mode
                                        onThemeModeChange(mode)
                                        themeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Text("Chat Bubble Colour", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Shown on new messages you send",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatBubbleColorPreset.entries.forEach { preset ->
                            val selected = chatBubbleColor == preset.colorArgb
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(preset.colorArgb), CircleShape)
                                    .semantics { contentDescription = "Set chat bubble colour to ${preset.label}" }
                                    .clickable { appearanceSettings.setChatBubbleColor(preset.colorArgb) }
                                    .then(
                                        if (selected) Modifier.background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                            CircleShape
                                        ) else Modifier
                                    )
                            )
                        }
                        TextButton(onClick = { showCustomChatColorPicker = true }) {
                            Text("Custom")
                        }
                    }
                    if (chatBubbleColor != null) {
                        TextButton(onClick = { appearanceSettings.setChatBubbleColor(null) }) {
                            Text("Use theme colour")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAccessibility),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Accessibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Configure optional screen-reader features",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Alerts & Feedback Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Alerts & Hardware Haptics",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))

                    val context = LocalContext.current
                    val hasVibrator = remember(context) {
                        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        }
                        vibrator?.hasVibrator() == true
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Haptic Proximity Alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasVibrator) "Vibrate when friends enter close range" else "Not supported on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = hapticsEnabled && hasVibrator, enabled = hasVibrator, onCheckedChange = { hapticsEnabled = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("In-app Notification Banners", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Display alerts for active beacons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = notificationsEnabled, onCheckedChange = { notificationsEnabled = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Chat Push Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Receive system notifications for incoming messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = chatNotificationsEnabled,
                            onCheckedChange = appearanceSettings::setChatNotificationsEnabled
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Notification Sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Play a tone for incoming messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationSoundEnabled,
                            onCheckedChange = appearanceSettings::setNotificationSoundEnabled
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Notification Vibration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasVibrator) "Vibrate for incoming messages" else "Not supported on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationVibrationEnabled && hasVibrator,
                            enabled = hasVibrator,
                            onCheckedChange = appearanceSettings::setNotificationVibrationEnabled
                        )
                    }
                }
            }

            // PingRing's developer tools are shown in debug builds on a device
            // that is set up for development, i.e. with USB or wireless
            // debugging active (see isDebuggingActive). The card stays visible
            // while LAN Debug Transport is on, so a relay left enabled can
            // always be turned back off.
            if (BuildConfig.DEBUG && (debuggingActive || lanDebugEnabled)) {
                val lanDebugHost by appearanceSettings.lanDebugHost.collectAsState(initial = "10.0.2.2")
                var hostFieldValue by remember(lanDebugHost) { mutableStateOf(lanDebugHost) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "Developer Options",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Use LAN Debug Transport", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Route messages through a local relay instead of Nearby Connections, for testing between the Emulator and a real device",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = lanDebugEnabled, onCheckedChange = appearanceSettings::setLanDebugEnabled)
                        }

                        if (lanDebugEnabled) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = hostFieldValue,
                                onValueChange = {
                                    hostFieldValue = it
                                    appearanceSettings.setLanDebugHost(it)
                                },
                                label = { Text("Relay host") },
                                supportingText = { Text("10.0.2.2 for Emulator, or 127.0.0.1 after `adb reverse` on a physical device") },
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCustomChatColorPicker) {
        ChatBubbleColorPickerDialog(
            onDismiss = { showCustomChatColorPicker = false },
            onConfirm = {
                appearanceSettings.setChatBubbleColor(it)
                showCustomChatColorPicker = false
            }
        )
    }
}

@Composable
private fun ChatBubbleColorPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    val selectedColor = Color.hsv(hue, 0.72f, 0.72f)
    val wheelColors = listOf(
        Color.Red,
        Color.Yellow,
        Color.Green,
        Color.Cyan,
        Color.Blue,
        Color.Magenta,
        Color.Red,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Chat Colour") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .semantics { contentDescription = "Chat colour hue wheel" }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val fromCentre = tap - centre
                                if (sqrt(fromCentre.x * fromCentre.x + fromCentre.y * fromCentre.y) <= minOf(size.width, size.height) / 2f) {
                                    hue = ((Math.toDegrees(atan2(fromCentre.y.toDouble(), fromCentre.x.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                                }
                            }
                        }
                ) {
                    drawCircle(brush = Brush.sweepGradient(wheelColors))
                    drawCircle(color = selectedColor, radius = size.minDimension * 0.12f)
                }
                Spacer(Modifier.height(12.dp))
                Text("Choose a hue", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(selectedColor.toArgb().toLong() and 0xFFFF_FFFFL)
                }
            ) { Text("Use colour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System Default"
        ThemeMode.LIGHT -> "Light Theme"
        ThemeMode.DARK -> "Dark Theme"
    }

/**
 * Tracks whether development debugging is active on this device, re-checking
 * every time the screen resumes so toggling USB/wireless debugging, plugging in
 * a cable, or changing Developer options outside the app is picked up.
 */
@Composable
private fun rememberDebuggingActive(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val debuggingActive = remember { mutableStateOf(DeviceEnvironment.isDebuggingActive(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                debuggingActive.value = DeviceEnvironment.isDebuggingActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return debuggingActive
}
