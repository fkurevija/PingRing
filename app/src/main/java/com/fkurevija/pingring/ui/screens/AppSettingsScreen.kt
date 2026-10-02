package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    showDeveloperOptions: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onBack: () -> Unit,
) {
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            SettingsSubmenuCard(
                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                title = stringResource(R.string.appearance_title),
                description = stringResource(R.string.appearance_description),
                onClick = onOpenAppearance,
            )
            SettingsSubmenuCard(
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                title = stringResource(R.string.notifications_title),
                description = stringResource(R.string.notifications_description),
                onClick = onOpenNotifications,
            )
            SettingsSubmenuCard(
                icon = { Text("A", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                title = stringResource(R.string.accessibility_title),
                description = stringResource(R.string.accessibility_card_description),
                onClick = onOpenAccessibility,
            )
            if (showDeveloperOptions) {
                SettingsSubmenuCard(
                    icon = { Text("</>", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    title = stringResource(R.string.developer_options),
                    description = stringResource(R.string.lan_debug_transport),
                    onClick = onOpenDeveloperOptions,
                )
            }
        }
    }
}

@Composable
private fun SettingsSubmenuCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            icon()
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}