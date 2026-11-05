package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.chat.BeaconPreset
import com.combustiblelemonlabs.pingring.data.chat.BeaconVibrationPattern
import com.combustiblelemonlabs.pingring.data.TextInputPolicy
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconCustomizationDialog(
    localBeacon: BeaconInfo?,
    onDismiss: () -> Unit,
    onActivate: (title: String, colorArgb: Long, patternName: String) -> Unit,
    onStop: () -> Unit,
    onTestVibrate: (LongArray) -> Unit
) {
    var isVibrationMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(BeaconPreset.entries.first()) }
    val initialPresetTitle = stringResource(selectedPreset.defaultTitleRes)
    var title by remember { mutableStateOf(localBeacon?.title ?: initialPresetTitle) }
    var selectedColor by remember { mutableLongStateOf(localBeacon?.colorArgb ?: selectedPreset.defaultColorArgb) }
    var selectedPattern by remember {
        mutableStateOf(
            localBeacon?.let { BeaconVibrationPattern.fromName(it.vibrationPattern) }
                ?: selectedPreset.defaultPattern
        )
    }

    val colorPalette = listOf(
        0xFFFF1744, // Red
        0xFFFF9100, // Amber
        0xFF00E676, // Green
        0xFF00E5FF, // Cyan
        0xFFD500F9  // Purple
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (localBeacon != null) R.string.update_active_beacon else R.string.create_beacon),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Presets & Intent
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.select_preset),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BeaconPreset.entries.forEach { preset ->
                                    FilterChip(
                                        selected = selectedPreset == preset,
                                        onClick = {
                                            selectedPreset = preset
                                            title = context.getString(preset.defaultTitleRes)
                                            selectedColor = preset.defaultColorArgb
                                            selectedPattern = preset.defaultPattern
                                            isVibrationMenuExpanded = false
                                        },
                                        label = { Text(stringResource(preset.labelRes, preset.iconEmoji)) }
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(20.dp)
                                    .height(36.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    )
                            )
                        }

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = TextInputPolicy.sanitizeBeaconTitle(it) },
                            label = { Text(stringResource(R.string.beacon_message_intent)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            )
                        )
                    }
                }

                // Section 2: Color Palette
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.beacon_color),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colorPalette.forEach { colorArgb ->
                                val isSelected = selectedColor == colorArgb
                                val description = stringResource(
                                    R.string.set_beacon_color_to,
                                    String.format(Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .semantics { contentDescription = description }
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.RadioButton,
                                            onClick = { selectedColor = colorArgb },
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .background(Color(colorArgb), CircleShape)
                                            .border(
                                                width = if (isSelected) 3.dp else 0.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                shape = CircleShape,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 3: Vibration & Testing
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.vibration_rhythm_intensity),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ExposedDropdownMenuBox(
                            expanded = isVibrationMenuExpanded,
                            onExpandedChange = { isVibrationMenuExpanded = !isVibrationMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = stringResource(selectedPattern.labelRes),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVibrationMenuExpanded) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = isVibrationMenuExpanded,
                                onDismissRequest = { isVibrationMenuExpanded = false }
                            ) {
                                BeaconVibrationPattern.entries.forEach { pattern ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(pattern.labelRes)) },
                                        onClick = {
                                            selectedPattern = pattern
                                            isVibrationMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { onTestVibrate(selectedPattern.pattern) },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.test_vibration))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onActivate(
                        title.ifBlank { context.getString(R.string.active_beacon_title) },
                        selectedColor,
                        selectedPattern.name,
                    )
                    onDismiss()
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(if (localBeacon != null) R.string.update_beacon else R.string.activate_beacon), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (localBeacon != null) {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.stop_beacon), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

internal val BeaconPreset.labelRes: Int
    get() = when (this) {
        BeaconPreset.GONDOR_CALLS_FOR_AID -> R.string.preset_gondor_calls_for_aid
        BeaconPreset.FOUND -> R.string.preset_found
        BeaconPreset.REGROUP -> R.string.preset_regroup
        BeaconPreset.CAUTION -> R.string.preset_caution
        BeaconPreset.SOS -> R.string.preset_emergency
        BeaconPreset.CUSTOM -> R.string.preset_custom
    }

internal val BeaconPreset.defaultTitleRes: Int
    get() = when (this) {
        BeaconPreset.GONDOR_CALLS_FOR_AID -> R.string.preset_gondor_calls_for_aid_title
        BeaconPreset.FOUND -> R.string.preset_found_title
        BeaconPreset.REGROUP -> R.string.preset_regroup_title
        BeaconPreset.CAUTION -> R.string.preset_caution_title
        BeaconPreset.SOS -> R.string.preset_emergency_title
        BeaconPreset.CUSTOM -> R.string.preset_custom_title
    }
