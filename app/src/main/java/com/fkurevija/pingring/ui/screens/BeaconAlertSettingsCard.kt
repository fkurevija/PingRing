package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BEACON_REPEAT_DURATION_UNTIL_STOPPED
import com.fkurevija.pingring.data.BeaconAlertRepeat
import com.fkurevija.pingring.data.BeaconAlertSettings
import kotlin.math.roundToInt

@Composable
internal fun BeaconAlertSettingsCard(
    beaconAlertSettings: BeaconAlertSettings,
    onBeaconAlertSettingsChange: (BeaconAlertSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        var repeatEnabled by remember(beaconAlertSettings) {
            mutableStateOf(beaconAlertSettings.repeat == BeaconAlertRepeat.REPEAT)
        }

        val initialDurationSec = if (beaconAlertSettings.repeatDurationMillis == BEACON_REPEAT_DURATION_UNTIL_STOPPED) {
            300f
        } else {
            (beaconAlertSettings.repeatDurationMillis / 1000f).coerceIn(30f, 300f)
        }
        var durationDraft by remember(beaconAlertSettings) { mutableFloatStateOf(initialDurationSec) }

        val initialGapSec = (((beaconAlertSettings.repeatGapMillis / 1000f) / 5f).roundToInt() * 5f).coerceIn(5f, 30f)
        var frequencyDraft by remember(beaconAlertSettings) { mutableFloatStateOf(initialGapSec) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.beacon_configuration),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.beacon_alert_repetition_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.beacon_alert_repetition_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = repeatEnabled,
                    onCheckedChange = { checked ->
                        repeatEnabled = checked
                        val newRepeat = if (checked) BeaconAlertRepeat.REPEAT else BeaconAlertRepeat.ONCE
                        val durSec = durationDraft.roundToInt()
                        val newDuration = if (durSec >= 300) {
                            BEACON_REPEAT_DURATION_UNTIL_STOPPED
                        } else {
                            durSec * 1000L
                        }
                        val newGap = (((frequencyDraft / 5f).roundToInt() * 5).coerceIn(5, 30) * 1000L)
                        onBeaconAlertSettingsChange(
                            BeaconAlertSettings(
                                repeat = newRepeat,
                                repeatGapMillis = newGap,
                                repeatDurationMillis = newDuration,
                            )
                        )
                    },
                    modifier = Modifier.testTag("beaconRepetitionSwitch"),
                )
            }

            if (repeatEnabled) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                val currentDurationSec = durationDraft.roundToInt()
                val isUntilStopped = currentDurationSec >= 300
                Text(
                    text = if (isUntilStopped) {
                        "Duration: Until stopped"
                    } else {
                        "Duration: ${currentDurationSec}s"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = durationDraft,
                    onValueChange = { durationDraft = it },
                    onValueChangeFinished = {
                        val sec = durationDraft.roundToInt()
                        val dur = if (sec >= 300) BEACON_REPEAT_DURATION_UNTIL_STOPPED else (sec * 1000L)
                        onBeaconAlertSettingsChange(beaconAlertSettings.copy(repeatDurationMillis = dur))
                    },
                    valueRange = 30f..300f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                val currentFreqSec = ((frequencyDraft / 5f).roundToInt() * 5).coerceIn(5, 30)
                Text(
                    text = "Frequency: Every ${currentFreqSec}s",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = frequencyDraft,
                    onValueChange = { frequencyDraft = it },
                    onValueChangeFinished = {
                        val sec = ((frequencyDraft / 5f).roundToInt() * 5).coerceIn(5, 30)
                        val gap = sec * 1000L
                        onBeaconAlertSettingsChange(beaconAlertSettings.copy(repeatGapMillis = gap))
                    },
                    valueRange = 5f..30f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
