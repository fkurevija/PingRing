package com.fkurevija.pingring.ui.screens

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    appearanceSettings: AppearanceSettingsRepository,
    onBack: () -> Unit,
) {
    val chatNotificationsEnabled by appearanceSettings.chatNotificationsEnabled.collectAsState(initial = true)
    val notificationSoundEnabled by appearanceSettings.notificationSoundEnabled.collectAsState(initial = true)
    val notificationVibrationEnabled by appearanceSettings.notificationVibrationEnabled.collectAsState(initial = true)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title), fontWeight = FontWeight.Bold) },
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
                .padding(16.dp)
        ) {
            NotificationSettingRow(
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                title = stringResource(R.string.chat_push_notifications),
                description = stringResource(R.string.chat_push_notifications_description),
                checked = chatNotificationsEnabled,
                onCheckedChange = appearanceSettings::setChatNotificationsEnabled,
            )
            HorizontalDivider()
            NotificationSettingRow(
                icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                title = stringResource(R.string.notification_sound),
                description = stringResource(R.string.notification_sound_description),
                checked = notificationSoundEnabled,
                onCheckedChange = appearanceSettings::setNotificationSoundEnabled,
            )
            HorizontalDivider()
            NotificationSettingRow(
                icon = { Icon(Icons.Default.Vibration, contentDescription = null) },
                title = stringResource(R.string.notification_vibration),
                description = stringResource(if (hasVibrator) R.string.notification_vibration_description else R.string.not_supported_on_device),
                checked = notificationVibrationEnabled && hasVibrator,
                enabled = hasVibrator,
                onCheckedChange = appearanceSettings::setNotificationVibrationEnabled,
            )
        }
    }
}

@Composable
private fun NotificationSettingRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (!enabled) Modifier.clickable {
                Toast.makeText(context, description, Toast.LENGTH_SHORT).show()
            } else Modifier)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = title })
    }
}