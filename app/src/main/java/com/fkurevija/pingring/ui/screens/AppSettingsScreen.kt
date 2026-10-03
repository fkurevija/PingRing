package com.fkurevija.pingring.ui.screens

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    selectedLanguageTag: String,
    onLanguageSelected: (String) -> Unit,
    /**
     * Theme Mode and the three notification switches are passed as plain values
     * and callbacks rather than reached through a submenu.
     *
     * Appearance held exactly one setting and Notifications exactly three, so
     * each was a door onto a screen with less on it than the screen you opened it
     * from. Listing them here removes a tap and a back press from the two things
     * a user changes most, and means the App Settings screen finally contains all
     * the settings rather than links to some of them.
     */
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    chatNotificationsEnabled: Boolean,
    onChatNotificationsEnabledChange: (Boolean) -> Unit,
    notificationSoundEnabled: Boolean,
    onNotificationSoundEnabledChange: (Boolean) -> Unit,
    notificationVibrationEnabled: Boolean,
    onNotificationVibrationEnabledChange: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    /**
     * Opens the project's issue tracker in the browser.
     *
     * An ACTION_VIEW hand-off rather than anything in-app: PingRing sends
     * nothing anywhere, and a report that had to be written into a form inside
     * the app would either need a backend or would quietly assemble a half-filled
     * draft nobody can send. Handing off to the browser keeps the app offline and
     * still gets the user to somewhere they can actually file something.
     */
    onReportAProblem: () -> Unit,
    connectionRecoveryPromptEnabled: Boolean = true,
    connectionRecoveryPromptDelayMillis: Long = 120_000L,
    onConnectionRecoveryPromptEnabledChange: (Boolean) -> Unit = {},
    onConnectionRecoveryPromptDelayChange: (Long) -> Unit = {},
    showDeveloperOptions: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onBack: () -> Unit,
) {
    val languages = listOf(
        "" to R.string.language_system_default,
        "en" to R.string.language_english,
        "hr" to R.string.language_croatian,
        "es" to R.string.language_spanish,
        "fr" to R.string.language_french,
        "fr-CA" to R.string.language_french_canada,
        "de" to R.string.language_german,
        "ru" to R.string.language_russian,
    )
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var themeMenuExpanded by remember { mutableStateOf(false) }
    // Read here rather than passed in, because whether a phone can vibrate is a
    // property of the device rather than of anyone's stored preference. Kept as a
    // remember so it is asked once per context rather than on every recomposition
    // of a screen the user is scrolling through.
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
    val delayMinutes = (connectionRecoveryPromptDelayMillis / 60_000L).toInt().coerceIn(1, 10)
    var delayDraft by remember { mutableFloatStateOf(delayMinutes.toFloat()) }
    LaunchedEffect(delayMinutes) { delayDraft = delayMinutes.toFloat() }
    val selectedLanguage = languages.firstOrNull { it.first == selectedLanguageTag } ?: languages.first()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // One flat list, most-changed first. The old layout interleaved full cards for
            // submenus with a bare dropdown for language, so nothing lined up and
            // the eye had to re-read the screen to work out how many things there
            // were. Every entry here is the same shape and the same width.
            //
            // Theme and language lead because they are the two a user changes
            // once and immediately want to see the effect of. Notification
            // settings are next because they are the third most common. The two
            // grouped blocks - accessibility and developer - sit at the bottom
            // because they are the ones a user arrives at already knowing what
            // they want.
            // Theme leads as a dropdown beside Language, not as a row that opened an
            // Appearance screen containing this one control and nothing else. The
            // label sits on the field itself, so it is announced together with the
            // value instead of needing a heading above it.
            ExposedDropdownMenuBox(
                expanded = themeMenuExpanded,
                onExpandedChange = { themeMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = stringResource(themeMode.labelRes),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.theme_mode)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = themeMenuExpanded,
                    onDismissRequest = { themeMenuExpanded = false },
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(mode.labelRes)) },
                            onClick = {
                                themeMenuExpanded = false
                                onThemeModeChange(mode)
                            },
                        )
                    }
                }
            }
            ExposedDropdownMenuBox(
                expanded = languageMenuExpanded,
                onExpandedChange = { languageMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = stringResource(selectedLanguage.second),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    languages.forEach { (tag, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                languageMenuExpanded = false
                                onLanguageSelected(tag)
                            },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.language_description),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The three notification switches, listed here rather than behind a
            // Notifications screen: a group heading plus a submenu to reach three
            // switches is a tap and a back press spent on nothing, and the App
            // Settings screen then held links to settings instead of settings.
            SettingsSwitchRow(
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                title = stringResource(R.string.chat_push_notifications),
                description = stringResource(R.string.chat_push_notifications_description),
                checked = chatNotificationsEnabled,
                onCheckedChange = onChatNotificationsEnabledChange,
            )
            SettingsSwitchRow(
                icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                title = stringResource(R.string.notification_sound),
                description = stringResource(R.string.notification_sound_description),
                checked = notificationSoundEnabled,
                onCheckedChange = onNotificationSoundEnabledChange,
            )
            SettingsSwitchRow(
                icon = { Icon(Icons.Default.Vibration, contentDescription = null) },
                title = stringResource(R.string.notification_vibration),
                // Says the device cannot do it rather than showing a switch that
                // silently does nothing.
                description = stringResource(
                    if (hasVibrator) R.string.notification_vibration_description else R.string.not_supported_on_device
                ),
                checked = notificationVibrationEnabled && hasVibrator,
                enabled = hasVibrator,
                onCheckedChange = onNotificationVibrationEnabledChange,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.connection_recovery_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.connection_recovery_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val recoveryLabel = stringResource(R.string.connection_recovery_title)
                        Switch(
                            checked = connectionRecoveryPromptEnabled,
                            onCheckedChange = onConnectionRecoveryPromptEnabledChange,
                            modifier = Modifier.semantics { contentDescription = recoveryLabel },
                        )
                    }
                    if (connectionRecoveryPromptEnabled) {
                        Text(
                            stringResource(R.string.connection_recovery_delay, delayDraft.roundToInt()),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = delayDraft,
                            onValueChange = { delayDraft = it },
                            onValueChangeFinished = {
                                onConnectionRecoveryPromptDelayChange(delayDraft.roundToInt() * 60_000L)
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                    }
                }
            }
            // Accessibility below recovery, not above it. Both are about a member having a
            // worse time than the app expects, and the old order put the three
            // switches a first-time user should try last at the very top.
            SettingsRow(
                icon = { Text("A", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                title = stringResource(R.string.accessibility_title),
                description = stringResource(R.string.accessibility_card_description),
                onClick = onOpenAccessibility,
            )
            if (showDeveloperOptions) {
                SettingsRow(
                    icon = { Text("</>", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    title = stringResource(R.string.developer_options),
                    description = stringResource(R.string.lan_debug_transport),
                    onClick = onOpenDeveloperOptions,
                )
            }
            // Last, and below a divider: it is not a setting, it is a way out of
            // the app to somewhere the problem can actually be described. Putting
            // it in the run of settings implied it was one more preference.
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(
                onClick = onReportAProblem,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.report_a_problem))
            }
        }
    }
}

/**
 * One entry in the settings list that opens something.
 *
 * Every navigation row on this screen is this, which is the point: the old
 * screen mixed full cards with a bare dropdown and a couple of unlabelled switch
 * rows, so the list read as several unrelated blocks rather than one list. Equal
 * width and equal internal spacing is what makes it scannable.
 */
@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One switch in the settings list, shaped like [SettingsRow] on purpose.
 *
 * A row you flip and a row that opens something are the same kind of thing to
 * the person scrolling past, and the earlier screen mixed three different row
 * shapes so nothing lined up. The switch keeps a 48dp minimum so it is a
 * reachable target and is labelled for a screen reader by its own title, not by
 * a position in the list.
 */
@Composable
private fun SettingsSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Only when disabled, so that an enabled row does not advertise a
            // tap that goes nowhere. A switch the device cannot honour stays
            // visible and explains itself on tap rather than greying out, which
            // reads as a broken app rather than as an honest limit.
            .then(
                if (enabled) Modifier else Modifier.clickable {
                    Toast.makeText(context, description, Toast.LENGTH_SHORT).show()
                }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = title },
            )
        }
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system_default
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
