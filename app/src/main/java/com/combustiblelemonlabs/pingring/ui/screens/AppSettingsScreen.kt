package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    selectedLanguageTag: String,
    onLanguageSelected: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenAccessibility: () -> Unit,
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
            SettingsRow(
                icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.notifications),
                description = stringResource(R.string.notifications_sub_description),
                onClick = onOpenNotifications,
            )
            SettingsRow(
                icon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.connection),
                description = stringResource(R.string.connection_sub_description),
                onClick = onOpenConnection,
            )
            SettingsRow(
                icon = { Text("A", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.accessibility_title),
                description = stringResource(R.string.accessibility_card_description),
                onClick = onOpenAccessibility,
            )
            if (showDeveloperOptions) {
                SettingsRow(
                    icon = { Text("</>", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.developer_options),
                    description = stringResource(R.string.lan_debug_transport),
                    onClick = onOpenDeveloperOptions,
                )
            }
            SettingsRow(
                icon = { Text("i", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.about),
                description = stringResource(R.string.about_description),
                onClick = onOpenAbout,
            )
        }
    }
}

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
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
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
