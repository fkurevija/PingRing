package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.data.AppearanceSettingsRepository
import com.fkurevija.pingring.data.ChatBubbleColorPreset
import com.fkurevija.pingring.data.ThemeMode
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    appearanceSettings: AppearanceSettingsRepository,
    onBack: () -> Unit,
) {
    val chatBubbleColor by appearanceSettings.chatBubbleColor.collectAsState(initial = null)
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var selectedTheme by remember(themeMode) { mutableStateOf(themeMode) }
    var showCustomChatColorPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = themeMenuExpanded,
                onExpandedChange = { themeMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedTheme.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Theme Mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = themeMenuExpanded,
                    onDismissRequest = { themeMenuExpanded = false },
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                selectedTheme = mode
                                onThemeModeChange(mode)
                                themeMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Chat Bubble Colour", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Shown on new messages you send",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatBubbleColorPreset.entries.forEach { preset ->
                    val selected = chatBubbleColor == preset.colorArgb
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(preset.colorArgb), CircleShape)
                            .semantics { contentDescription = "Set chat bubble colour to ${preset.label}" }
                            .clickable { appearanceSettings.setChatBubbleColor(preset.colorArgb) }
                            .then(
                                if (selected) Modifier.background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape
                                ) else Modifier
                            ),
                    )
                }
                TextButton(onClick = { showCustomChatColorPicker = true }) {
                    Text("Custom")
                }
            }
            if (chatBubbleColor != null) {
                TextButton(onClick = { appearanceSettings.setChatBubbleColor(null) }) {
                    Text("Use theme colour")
                }
            }
        }
    }

    if (showCustomChatColorPicker) {
        ChatBubbleColorPickerDialog(
            onDismiss = { showCustomChatColorPicker = false },
            onConfirm = {
                appearanceSettings.setChatBubbleColor(it)
                showCustomChatColorPicker = false
            },
        )
    }
}

@Composable
private fun ChatBubbleColorPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    val selectedColor = Color.hsv(hue, 0.72f, 0.72f)
    val wheelColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Chat Colour") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .semantics { contentDescription = "Chat colour hue wheel" }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val fromCentre = tap - centre
                                if (sqrt(fromCentre.x * fromCentre.x + fromCentre.y * fromCentre.y) <= minOf(size.width, size.height) / 2f) {
                                    hue = ((Math.toDegrees(atan2(fromCentre.y.toDouble(), fromCentre.x.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                                }
                            }
                        },
                ) {
                    drawCircle(brush = Brush.sweepGradient(wheelColors))
                    drawCircle(color = selectedColor, radius = size.minDimension * 0.12f)
                }
                Spacer(Modifier.height(12.dp))
                Text("Choose a hue", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedColor.toArgb().toLong() and 0xFFFF_FFFFL) }) {
                Text("Use colour")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System Default"
        ThemeMode.LIGHT -> "Light Theme"
        ThemeMode.DARK -> "Dark Theme"
    }