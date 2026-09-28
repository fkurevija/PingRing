package com.fkurevija.pingring.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.BuildConfig
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    appearanceSettings: AppearanceSettingsRepository,
    onBack: () -> Unit
) {
    var hapticsEnabled by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    val chatNotificationsEnabled by appearanceSettings.chatNotificationsEnabled.collectAsState(initial = true)
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var selectedTheme by remember(themeMode) { mutableStateOf(themeMode) }
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
                            Text("Display alerts for nudges and active beacons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val debuggingActive = remember { mutableStateOf(isDebuggingActive(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                debuggingActive.value = isDebuggingActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return debuggingActive
}

// `Settings.Global.ADB_WIFI_ENABLED` is @hide in the SDK, so the wireless
// debugging flag is read through its platform string value instead.
private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"

/**
 * Whether this device is set up for development: ADB over Wi-Fi, ADB over USB,
 * the Developer options master switch, a USB cable to a development machine, or
 * an emulator.
 *
 * Android 15 and later filter `adb_enabled` and `development_settings_enabled`
 * out of `Settings.Global` for apps on some devices - the reads silently return
 * the default value instead of throwing - so those two only report the truth
 * where the platform still exposes them; `adb_wifi_enabled` is readable on the
 * devices checked so far. A debug build on an emulator or with a USB cable
 * attached therefore covers the remaining cases. Every read is defensive: any
 * failure simply means "not detected".
 */
private fun isDebuggingActive(context: Context): Boolean =
    isGlobalFlagEnabled(context, ADB_WIFI_ENABLED_KEY) ||
        isGlobalFlagEnabled(context, Settings.Global.ADB_ENABLED) ||
        isGlobalFlagEnabled(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ||
        isUsbPowered(context) ||
        isEmulator()

private fun isGlobalFlagEnabled(context: Context, key: String): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, key, 0) == 1
}.getOrDefault(false)

/** True while the device is powered over USB, i.e. by a cable to a dev machine. */
private fun isUsbPowered(context: Context): Boolean = runCatching {
    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
}.getOrDefault(false)

/** Emulator images report sdk_gphone hardware and usually run userdebug/eng builds. */
private fun isEmulator(): Boolean =
    Build.TYPE == "userdebug" ||
        Build.TYPE == "eng" ||
        Build.MODEL.startsWith("sdk_gphone") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.FINGERPRINT.startsWith("generic")
