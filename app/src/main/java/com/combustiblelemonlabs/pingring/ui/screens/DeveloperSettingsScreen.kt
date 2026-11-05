package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.TextInputPolicy
import com.combustiblelemonlabs.pingring.data.settings.AppearanceSettingsRepository

@Suppress("FunctionName")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    appearanceSettings: AppearanceSettingsRepository,
    onBack: () -> Unit,
) {
    val lanDebugEnabled by appearanceSettings.lanDebugEnabled.collectAsState(initial = false)
    val lanDebugHost by appearanceSettings.lanDebugHost.collectAsState(initial = "10.0.2.2")
    val realTimePolicyVisualizationEnabled by appearanceSettings.realTimePolicyVisualizationEnabled.collectAsState(initial = false)
    val dataExportApiEnabled by appearanceSettings.dataExportApiEnabled.collectAsState(initial = false)
    var hostFieldValue by remember(lanDebugHost) { mutableStateOf(lanDebugHost) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_options), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.use_lan_debug_transport), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.lan_debug_transport_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = lanDebugEnabled, onCheckedChange = appearanceSettings::setLanDebugEnabled)
            }
            if (lanDebugEnabled) {
                OutlinedTextField(
                    value = hostFieldValue,
                    onValueChange = { raw ->
                        if (raw.isEmpty()) {
                            hostFieldValue = ""
                        } else {
                            TextInputPolicy.sanitizeRelayHost(raw)?.let { host ->
                                hostFieldValue = host
                                appearanceSettings.setLanDebugHost(host)
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.relay_host)) },
                    supportingText = { Text(stringResource(R.string.relay_host_help)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.realtime_policy_visualization_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.realtime_policy_visualization_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = realTimePolicyVisualizationEnabled, onCheckedChange = appearanceSettings::setRealTimePolicyVisualizationEnabled)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.data_export_api_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.data_export_api_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = dataExportApiEnabled, onCheckedChange = appearanceSettings::setDataExportApiEnabled)
            }
        }
    }
}
