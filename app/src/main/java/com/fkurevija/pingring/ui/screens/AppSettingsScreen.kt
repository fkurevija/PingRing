package com.fkurevija.pingring.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.fkurevija.pingring.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    selectedLanguageTag: String,
    onLanguageSelected: (String) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
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
            SettingsRow(
                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                title = stringResource(R.string.appearance_title),
                description = stringResource(R.string.appearance_description),
                onClick = onOpenAppearance,
            )
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
            // Notifications are a plain row rather than a card of their own: the
            // card only ever held a title and a description, which is what every
            // other row here is.
            SettingsRow(
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                title = stringResource(R.string.notifications_title),
                description = stringResource(R.string.notifications_description),
                onClick = onOpenNotifications,
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
 * One entry in the settings list.
 *
 * Every row on this screen is this, which is the point: the old screen mixed
 * full cards with a bare dropdown and a couple of unlabelled switch rows, so the
 * list read as several unrelated blocks rather than one list. Equal width and
 * equal internal spacing is what makes it scannable.
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
