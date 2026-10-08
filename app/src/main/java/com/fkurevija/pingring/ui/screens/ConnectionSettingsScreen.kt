package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MAX_JOIN_WAIT_TIMEOUT_MILLIS
import com.fkurevija.pingring.data.MIN_JOIN_WAIT_TIMEOUT_MILLIS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    nearbyDigitComparisonEnabled: Boolean = false,
    onNearbyDigitComparisonEnabledChange: (Boolean) -> Unit = {},
    connectionRecoveryPromptEnabled: Boolean = true,
    connectionRecoveryPromptDelayMillis: Long = 120_000L,
    onConnectionRecoveryPromptEnabledChange: (Boolean) -> Unit = {},
    onConnectionRecoveryPromptDelayChange: (Long) -> Unit = {},
    joinWaitTimeoutMillis: Long = DEFAULT_JOIN_WAIT_TIMEOUT_MILLIS,
    onJoinWaitTimeoutChange: (Long) -> Unit = {},
    peerLinkDiagnosticsEnabled: Boolean = false,
    onPeerLinkDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    batterySaverEnabled: Boolean = false,
    onBatterySaverEnabledChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val delayMinutes = (connectionRecoveryPromptDelayMillis / 60_000L).toInt().coerceIn(1, 10)
    var delayDraft by remember { mutableFloatStateOf(delayMinutes.toFloat()) }
    LaunchedEffect(delayMinutes) { delayDraft = delayMinutes.toFloat() }

    val joinWaitSeconds = (joinWaitTimeoutMillis / 1_000L).toFloat()
    var joinWaitDraft by remember { mutableFloatStateOf(joinWaitSeconds) }
    LaunchedEffect(joinWaitSeconds) { joinWaitDraft = joinWaitSeconds }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection), fontWeight = FontWeight.Bold) },
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.peer_link_diagnostics_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.peer_link_diagnostics_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = peerLinkDiagnosticsEnabled,
                            onCheckedChange = onPeerLinkDiagnosticsEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.peer_link_diagnostics_title)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.battery_saver_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.battery_saver_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = batterySaverEnabled,
                            onCheckedChange = onBatterySaverEnabledChange,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = context.getString(R.string.battery_saver_title)
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
        }
    }
}
