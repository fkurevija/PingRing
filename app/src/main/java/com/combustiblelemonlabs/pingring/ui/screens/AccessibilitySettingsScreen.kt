package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySettingsScreen(
    radarScreenReaderSummaryEnabled: Boolean,
    onRadarScreenReaderSummaryChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    reduceMotionEnabled: Boolean = false,
    onReduceMotionChange: (Boolean) -> Unit = {},
    proximityHapticsEnabled: Boolean = true,
    onProximityHapticsChange: (Boolean) -> Unit = {},
    spokenRadarUpdatesEnabled: Boolean = false,
    onSpokenRadarUpdatesChange: (Boolean) -> Unit = {},
    visualFlashAlertsEnabled: Boolean = false,
    onVisualFlashAlertsChange: (Boolean) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accessibility_title), fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.accessibility_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column {
                    AccessibilitySettingRow(
                        stringResource(R.string.reduce_motion),
                        stringResource(R.string.reduce_motion_description),
                        reduceMotionEnabled, onReduceMotionChange,
                    )
                    AccessibilitySettingRow(
                        stringResource(R.string.proximity_haptics),
                        stringResource(R.string.proximity_haptics_description),
                        proximityHapticsEnabled, onProximityHapticsChange,
                    )
                    AccessibilitySettingRow(
                        stringResource(R.string.radar_screen_reader_summary),
                        stringResource(R.string.radar_screen_reader_summary_description),
                        radarScreenReaderSummaryEnabled, onRadarScreenReaderSummaryChange,
                    )
                    AccessibilitySettingRow(
                        stringResource(R.string.spoken_radar_updates),
                        stringResource(R.string.spoken_radar_updates_description),
                        spokenRadarUpdatesEnabled, onSpokenRadarUpdatesChange,
                    )
                    AccessibilitySettingRow(
                        stringResource(R.string.visual_flash_alerts),
                        stringResource(R.string.visual_flash_alerts_description),
                        visualFlashAlertsEnabled, onVisualFlashAlertsChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessibilitySettingRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = title },
        )
    }
}
