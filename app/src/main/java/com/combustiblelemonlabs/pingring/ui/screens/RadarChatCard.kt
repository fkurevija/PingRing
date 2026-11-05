package com.combustiblelemonlabs.pingring.ui.screens

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.chat.ChatBubbleColorPolicy
import com.combustiblelemonlabs.pingring.data.chat.ChatEntryType
import com.combustiblelemonlabs.pingring.data.chat.ChatMessageOwnership
import com.combustiblelemonlabs.pingring.data.chat.ChatRecipientReceipt
import com.combustiblelemonlabs.pingring.data.chat.MessageStatus
import com.combustiblelemonlabs.pingring.data.TextInputPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import com.combustiblelemonlabs.pingring.data.chat.ChatTimelineItem

@Composable
internal fun RadarChatCard(
    modifier: Modifier = Modifier,
    isImeVisible: Boolean,
    chatListState: LazyListState,
    chatEntries: List<ChatTimelineItem>,
    localPeerId: String?,
    chatBubbleColorArgb: Long?,
    readReceiptsVisible: Boolean,
    latestSenderColors: Map<String, Long>,
    silencedBeaconIds: Set<String>,
    onToggleBeaconSilenced: (String, Boolean) -> Unit,
    onVisibleMessagesChanged: (List<String>) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var chatText by remember { mutableStateOf("") }
    var receiptDetails by remember { mutableStateOf<List<ChatRecipientReceipt>?>(null) }
    val visibleMessagesChanged by rememberUpdatedState(onVisibleMessagesChanged)
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(chatListState, readReceiptsVisible) {
        if (readReceiptsVisible) {
            snapshotFlow {
                chatListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    (item.key as? String)
                        ?.takeIf { it.startsWith("m:") }
                        ?.removePrefix("m:")
                }
            }.distinctUntilChanged().collect { visibleMessagesChanged(it) }
        }
    }

    receiptDetails?.let { recipients ->
        AlertDialog(
            onDismissRequest = { receiptDetails = null },
            title = { Text(stringResource(R.string.chat_receipt_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipients.forEach { recipient ->
                        val status = when (recipient.status) {
                            MessageStatus.SENT -> stringResource(R.string.chat_receipt_sent)
                            MessageStatus.DELIVERED -> stringResource(R.string.chat_receipt_delivered)
                            MessageStatus.READ -> stringResource(R.string.chat_receipt_read)
                        }
                        Text("${recipient.name}: $status")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { receiptDetails = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isImeVisible) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isImeVisible) 8.dp else 0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                stringResource(R.string.chat),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                state = chatListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // Laid out from the newest entry backwards, so the
                // newest message rests just above the text box and
                // everything older stacks up behind it, the way a
                // conversation reads. The list does still have to be
                // told to follow the newest entry: a lazy list
                // deliberately holds on to whatever it was showing
                // when items are added to it, which is right for a
                // page being read and wrong for a chat, where a
                // message arriving has to push the transcript up.
                reverseLayout = true
            ) {
                if (chatEntries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_messages_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(chatEntries, key = { it.key }) { entry ->
                        when (entry) {
                            is ChatTimelineItem.Msg -> {
                                val msg = entry.message
                                val isMe = ChatMessageOwnership.isOwnMessage(
                                    msg.senderPeerId,
                                    localPeerId
                                )
                                val senderBubbleColor = ChatBubbleColorPolicy
                                    .colorFor(
                                        message = msg,
                                        localPeerId = localPeerId,
                                        localColorArgb = chatBubbleColorArgb,
                                        latestColorByPeer = latestSenderColors,
                                    )
                                    ?.let(::Color)
                                val timeStr = chatHistoryTimestamp(msg.timestamp)
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth(0.75f)
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = {
                                                    clipboard.setText(AnnotatedString(msg.messageText))
                                                    Toast.makeText(context, context.getString(R.string.chat_message_copied), Toast.LENGTH_SHORT).show()
                                                }
                                            ),
                                        shape = RoundedCornerShape(
                                            topStart = 14.dp,
                                            topEnd = 14.dp,
                                            bottomStart = if (isMe) 14.dp else 4.dp,
                                            bottomEnd = if (isMe) 4.dp else 14.dp
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = senderBubbleColor ?: if (isMe) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            }
                                        )
                                    ) {
                                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    msg.senderName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = senderBubbleColor?.readableOnIt() ?: if (isMe) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    timeStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = (senderBubbleColor?.readableOnIt() ?: if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.7f)
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                msg.messageText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = senderBubbleColor?.readableOnIt()
                                                    ?: if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isMe && msg.recipientReceipts.isEmpty()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    stringResource(R.string.chat_receipt_sent),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = (senderBubbleColor?.readableOnIt() ?: MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.6f),
                                                    modifier = Modifier.align(Alignment.End)
                                                )
                                            }
                                        }
                                    }
                                    if (isMe && msg.recipientReceipts.isNotEmpty()) {
                                        val delivered = msg.recipientReceipts.count { it.status != MessageStatus.SENT }
                                        val read = msg.recipientReceipts.count { it.status == MessageStatus.READ }
                                        TextButton(
                                            onClick = { receiptDetails = msg.recipientReceipts },
                                            contentPadding = PaddingValues(0.dp),
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.chat_receipt_counts,
                                                    delivered,
                                                    msg.recipientReceipts.size,
                                                    read,
                                                    msg.recipientReceipts.size,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = (senderBubbleColor?.readableOnIt() ?: MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.75f),
                                            )
                                        }
                                    }
                                }
                            }

                            is ChatTimelineItem.Beacon -> ActiveBeaconEntry(
                                beacon = entry.beacon,
                                silenced = entry.beacon.id in silencedBeaconIds,
                                onToggleSilenced = { silenced ->
                                    onToggleBeaconSilenced(entry.beacon.id, silenced)
                                },
                            )

                            is ChatTimelineItem.SystemNotice -> {
                                val message = entry.message
                                val noticeText = when (message.entryType) {
                                    ChatEntryType.PINGRING_CREATED -> stringResource(
                                        R.string.system_pingring_created,
                                        message.senderName,
                                    )
                                    ChatEntryType.MEMBER_JOINED -> stringResource(
                                        R.string.system_member_joined,
                                        message.subjectName
                                            ?.takeIf(String::isNotBlank)
                                            ?: stringResource(R.string.peer_default_name),
                                    )
                                    ChatEntryType.MESSAGE -> ""
                                }
                                val timeStr = chatHistoryTimestamp(message.timestamp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = noticeText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = timeStr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = chatText,
                    onValueChange = { chatText = TextInputPolicy.sanitizeMessage(it) },
                    placeholder = { Text(stringResource(R.string.type_message), style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (chatText.isNotBlank()) {
                            onSendMessage(chatText.trim())
                            chatText = ""
                            focusManager.clearFocus()
                        }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(46.dp),
                    enabled = chatText.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
