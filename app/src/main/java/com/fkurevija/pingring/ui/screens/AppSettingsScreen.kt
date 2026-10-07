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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
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
import com.fkurevija.pingring.data.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MAX_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MIN_JOIN_WAIT_TIMEOUT_MILLIS
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
    nearbyDigitComparisonEnabled: Boolean = false,
    onNearbyDigitComparisonEnabledChange: (Boolean) -> Unit = {},
    onOpenAccessibility: () -> Unit,
    connectionRecoveryPromptEnabled: Boolean = true,
    connectionRecoveryPromptDelayMillis: Long = 120_000L,
    onConnectionRecoveryPromptEnabledChange: (Boolean) -> Unit = {},
    onConnectionRecoveryPromptDelayChange: (Long) -> Unit = {},
    /**
     * How long a guest waits for a join to complete.
     *
     * A setting rather than a constant because the right value is a property of
     * the ring rather than of the app: a host watching the screen answers in
     * seconds, and a host who is busy does not. The range starts where the host's
     * own approval window ends, because a guest who gives up first would report
     * the host's refusal as a failure to reach anybody.
     */
    joinWaitTimeoutMillis: Long = DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS,
    onJoinWaitTimeoutChange: (Long) -> Unit = {},
    showDeveloperOptions: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAbout: () -> Unit,
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

    // Seconds rather than minutes, because the useful range is short and minutes
    // would collapse 60/90/120/150/180 onto four indistinguishable stops. Held as
    // a float draft for the same reason the recovery slider is: writing on every
    // drag frame would put a DataStore edit per pixel of movement.
    val joinWaitSeconds = (joinWaitTimeoutMillis / 1_000L).toFloat()
    var joinWaitDraft by remember { mutableFloatStateOf(joinWaitSeconds) }
    LaunchedEffect(joinWaitSeconds) { joinWaitDraft = joinWaitSeconds }
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
                .verticalScroll(rememberScrollState())
                // Inside the scroll rather than after it, so the gap travels
                // with the list and sits below the last row - About - instead of
                // reserving a band at the bottom of the viewport that rows scroll
                // behind. One spacedBy height (12.dp), so the margin reads as one
                // more gap in the run rather than a new section of its own.
                .padding(bottom = 12.dp),
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
                    shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
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
            // No hint under the dropdown. It explained that System default follows
            // the device, which is what the option is called, and the field already
            // shows the current choice. The App Settings screen is a flat list of
            // settings rather than a form to be filled in, so nothing here needs
            // explaining to a reader who has just tapped a control.
            //
            // The three notification switches, listed here rather than behind a
            // Notifications screen: a group heading plus a submenu to reach three
            // switches is a tap and a back press spent on nothing, and the App
            // Settings screen then held links to settings instead of settings.
            // Notifications tile grouping
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.notifications),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.chat_push_notifications), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.chat_push_notifications_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = chatNotificationsEnabled,
                            onCheckedChange = onChatNotificationsEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.chat_push_notifications)
                            }
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
                            Text(stringResource(R.string.notification_sound), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.notification_sound_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notificationSoundEnabled,
                            onCheckedChange = onNotificationSoundEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.notification_sound)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasVibrator) Modifier else Modifier.clickable {
                                    Toast.makeText(context, context.getString(R.string.not_supported_on_device), Toast.LENGTH_SHORT).show()
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.notification_vibration), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(
                                    if (hasVibrator) R.string.notification_vibration_description else R.string.not_supported_on_device
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationVibrationEnabled && hasVibrator,
                            enabled = hasVibrator,
                            onCheckedChange = onNotificationVibrationEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.notification_vibration)
                            }
                        )
                    }
                }
            }

            // Connection tile grouping
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.connection),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nearby_digit_comparison_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.nearby_digit_comparison_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = nearbyDigitComparisonEnabled,
                            onCheckedChange = onNearbyDigitComparisonEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.nearby_digit_comparison_title)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = recoveryLabel },
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

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        stringResource(R.string.join_wait_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.join_wait_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.join_wait_delay,
                            joinWaitDraft.roundToInt(),
                        ),
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = joinWaitDraft,
                        onValueChange = { joinWaitDraft = it },
                        onValueChangeFinished = {
                            onJoinWaitTimeoutChange(joinWaitDraft.roundToInt() * 1_000L)
                        },
                        valueRange = MIN_JOIN_WAIT_TIMEOUT_MILLIS / 1_000f..
                            MAX_JOIN_WAIT_TIMEOUT_MILLIS / 1_000f,
                        steps = 21,
                    )
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
            // Last in the run of settings, and ungated. Unlike Developer options
            // this is not a debug affordance - it is the one place a user can
            // find out what they are running, and a build number in a bug report
            // is worth more than any diagnostic a triager could infer.
            SettingsRow(
                icon = { Text("i", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                title = stringResource(R.string.about),
                description = stringResource(R.string.about_description),
                onClick = onOpenAbout,
            )
            // Reporting a problem used to live here, as the last item below a
            // divider. It moved to the About screen, which already holds every
            // fact a report carries: a button here had to send someone to the
            // tracker with nothing attached, so the useful action was two taps
            // away at best and the diagnostics sat on a different screen.
            //
            // What is left is a run of settings with no way out of the app at
            // the bottom, which is the correct shape for this screen.
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



private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system_default
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
