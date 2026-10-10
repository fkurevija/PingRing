package com.fkurevija.pingring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.TextInputPolicy
import java.util.Locale

/**
 * Everything about chat that the member can change, in one place.
 *
 * Opened from the radar between the beacon buttons and the transcript, so the
 * controls sit next to the chat they change rather than on a settings screen two
 * destinations away.
 *
 * Username and bubble colour, with colours only from [ChatBubblePalette.presets]. There is no
 * custom picker and no "use the theme colour" option: a member picks a colour so
 * their messages can be found in a busy transcript, which stops working the
 * moment two phones render the same message differently.
 */
@Composable
internal fun ChatSettingsDialog(
    selectedColorArgb: Long?,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Who holds which colour, and which of those holders is us.
     *
     * Needed because a colour somebody else holds cannot be picked: two members
     * rendering the same colour is what makes a transcript unreadable, so the
     * swatch is shown as taken rather than silently failing on tap. The holder's
     * name is passed in rather than looked up so this stays a plain composable.
     */
    colourHolders: Map<Long, String> = emptyMap(),
    memberNameFor: (String) -> String = { it },
    localPeerId: String? = null,
    refusedColorArgb: Long? = null,
    username: String = "",
    existingMemberNames: List<String> = emptyList(),
    onUsernameChange: (String) -> Unit = {},
) {
    var editedUsername by remember(username) { mutableStateOf(username) }
    var draftColor by remember(selectedColorArgb) { mutableStateOf(selectedColorArgb ?: ChatBubblePalette.random()) }
    var usernameSubmitted by remember { mutableStateOf(false) }
    var saveCompleted by remember { mutableStateOf(false) }
    val usernameError = TextInputPolicy.usernameError(editedUsername, existingMemberNames)
    val usernameChanged = editedUsername.trim() != username
    // Only consider colour changed if an initial colour was provided and the user picks a different one.
    val colorChanged = selectedColorArgb != null && draftColor != selectedColorArgb

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = editedUsername,
                    onValueChange = {
                        editedUsername = TextInputPolicy.sanitizeUsername(it)
                        usernameSubmitted = false
                    },
                    label = { Text(stringResource(R.string.username)) },
                    placeholder = { Text(stringResource(R.string.username_example)) },
                    singleLine = true,
                    isError = usernameError != null && (usernameChanged || usernameSubmitted),
                    supportingText = {
                        if (usernameChanged || usernameSubmitted) {
                            when (usernameError) {
                                TextInputPolicy.UsernameError.REQUIRED -> Text(stringResource(R.string.join_username_required))
                                TextInputPolicy.UsernameError.TAKEN ->
                                    Text(stringResource(R.string.join_username_taken, editedUsername.trim()))
                                null -> Unit
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.chat_bubble_colour_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refusedColorArgb != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chat_colour_refused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Wraps rather than scrolls sideways: twelve swatches in one
                // row needs more width than a phone has, and a horizontal
                // scroller hides the fact that there are eleven other choices.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatBubblePalette.presets.forEach { colorArgb ->
                        val selected = draftColor == colorArgb
                        // Ours is not taken, and ours is the one thing that must
                        // never grey out or a member cannot see or keep their own.
                        //
                        // A null localPeerId means we do not know who we are, and
                        // that disables nothing rather than everything: treating
                        // every holder as somebody else would grey out the
                        // member's own colour, which is the one case the whole
                        // screen has to get right. Unknown identity is a
                        // screenshot-test situation, not a real one.
                        val holder = colourHolders[colorArgb]
                        val heldByOther = holder != null &&
                            localPeerId != null &&
                            holder != localPeerId
                        val description = when {
                            selected -> stringResource(
                                R.string.set_chat_bubble_colour_to,
                                String.format(Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                            )
                            heldByOther -> stringResource(
                                R.string.chat_colour_taken_by,
                                String.format(Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                                memberNameFor(holder ?: ""),
                            )
                            else -> stringResource(
                                R.string.set_chat_bubble_colour_to,
                                String.format(Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = description
                                    // Announced as well as drawn, and only when it is
                                    // genuinely taken: dimming is a colour change
                                    // alone, which a screen reader cannot convey. Set
                                    // unconditionally it would announce all twelve
                                    // swatches as disabled.
                                    if (heldByOther) disabled()
                                }
                                .selectable(
                                    selected = selected,
                                    enabled = !heldByOther,
                                    role = Role.RadioButton,
                                    onClick = { draftColor = colorArgb },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    // Dimmed rather than hidden: the member can see
                                    // the colour is spoken for and who has it,
                                    // instead of finding eleven options and no
                                    // explanation for the gap.
                                    .background(
                                        Color(colorArgb).copy(
                                            alpha = if (heldByOther) 0.25f else 1f,
                                        ),
                                        CircleShape,
                                    )
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = if (!saveCompleted && (usernameChanged || colorChanged)) {
            {
                TextButton(
                    onClick = {
                        usernameSubmitted = true
                        if (usernameError == null) {
                            if (usernameChanged) {
                                onUsernameChange(editedUsername.trim())
                            }
                            if (colorChanged) {
                                onColorSelected(draftColor)
                            }
                            saveCompleted = true
                            onDismiss()
                        }
                    },
                    enabled = usernameError == null,
                ) {
                    Text(stringResource(R.string.save_changes))
                }
            }
        } else {
            {}
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
