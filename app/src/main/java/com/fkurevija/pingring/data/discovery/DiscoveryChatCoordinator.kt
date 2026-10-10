package com.fkurevija.pingring.data.discovery

import android.content.Context
import android.util.Log
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.ChatEntryType
import com.fkurevija.pingring.data.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.ChatMessage
import com.fkurevija.pingring.data.ChatPayloadDto
import com.fkurevija.pingring.data.ChatReceiptPayloadDto
import com.fkurevija.pingring.data.ChatNotifier
import com.fkurevija.pingring.data.ChatRecipientReceipt
import com.fkurevija.pingring.data.ChatReceiptPolicy
import com.fkurevija.pingring.data.MessageStatus
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.TextInputPolicy
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Owns chat payload creation, validation, session history, and chat notifications
 * for one active discovery repository.
 */
internal class DiscoveryChatCoordinator(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val appearanceSettings: AppearanceSettingsRepository,
    private val updatePeerName: (String, String) -> Unit,
) {
    private val readAcknowledgedMessageIds = mutableSetOf<String>()
    private var readAcknowledgedEventId: String? = null

    private val notifier = ChatNotifier(
        context = context,
        appearanceSettings = appearanceSettings,
        currentEventName = { sessionManager.sessionState.value.currentEvent?.name },
    )

    fun broadcastOutgoingMessage(text: String, senderName: String, colorArgb: Long?) {
        val sanitizedText = TextInputPolicy.sanitizeMessage(text)
        val sanitizedName = TextInputPolicy.sanitizeUsername(senderName)
        if (sanitizedText.isBlank() || sanitizedName.isBlank()) return
        val state = sessionManager.sessionState.value
        val senderPeerId = sessionManager.peerId.value
        val recipients = state.friends
            .asSequence()
            .filter { it.peerId.isNotBlank() && it.peerId != senderPeerId }
            .distinctBy { it.peerId }
            .map { ChatRecipientReceipt(peerId = it.peerId, name = it.name) }
            .toList()
        publish(
            ChatPayloadDto(
                id = UUID.randomUUID().toString(),
                senderPeerId = senderPeerId,
                senderName = sanitizedName,
                text = sanitizedText,
                timestamp = System.currentTimeMillis(),
                colorArgb = colorArgb,
                recipientReceipts = recipients,
            )
        )
    }

    fun announceRingCreated() {
        val state = sessionManager.sessionState.value
        check(state.isHost && state.currentEvent != null) {
            "A PingRing-created notice requires an active host session."
        }
        publishSystemEntry(
            entryType = ChatEntryType.PINGRING_CREATED,
            subjectName = state.userName,
            subjectPeerId = state.currentEvent.hostPeerId,
        )
    }

    fun announceMemberJoined(peerId: String) {
        val state = sessionManager.sessionState.value
        if (!state.isHost || peerId.isBlank() || peerId == sessionManager.peerId.value) return
        if (state.chatMessages.any {
                it.entryType == ChatEntryType.MEMBER_JOINED && it.subjectPeerId == peerId
            }
        ) return

        val member = state.friends.firstOrNull { it.peerId == peerId }
        publishSystemEntry(
            entryType = ChatEntryType.MEMBER_JOINED,
            subjectName = member?.name?.takeIf(String::isNotBlank),
            subjectPeerId = peerId,
        )
    }

    suspend fun receiveMessage(dto: ChatPayloadDto) {
        if (dto.entryType != ChatEntryType.MESSAGE && !isValidSystemEntry(dto)) {
            Log.w(TAG, "Ignoring invalid system chat entry ${dto.id}")
            return
        }
        if (dto.entryType == ChatEntryType.MESSAGE) {
            updatePeerName(dto.senderPeerId, dto.senderName)
        }
        val message = dto.toChatMessage()
        sessionManager.receiveLocalChatMessage(message)

        if (dto.entryType == ChatEntryType.MESSAGE &&
            dto.senderPeerId != sessionManager.peerId.value
        ) {
            val localPeerId = sessionManager.peerId.value
            val eventId = sessionManager.sessionState.value.currentEvent?.id
            if (eventId != null && dto.recipientReceipts.any { it.peerId == localPeerId }) {
                transport.sendChatReceiptToPeer(
                    dto.senderPeerId,
                    ChatReceiptPayloadDto(
                        eventCode = eventId,
                        messageId = dto.id,
                        recipientPeerId = localPeerId,
                        status = MessageStatus.DELIVERED,
                    ),
                )
            }
            val appWideEnabled = appearanceSettings.chatNotificationsEnabled.first()
            val eventEnabled = sessionManager.sessionState.value.currentEvent?.chatNotificationsEnabled ?: true
            if (appWideEnabled && eventEnabled) {
                notifier.onChatMessageReceived(message)
            }
        }
    }

    fun receiveReceipt(receipt: ChatReceiptPayloadDto) {
        val eventId = sessionManager.sessionState.value.currentEvent?.id ?: return
        val message = sessionManager.sessionState.value.chatMessages
            .firstOrNull { it.id == receipt.messageId } ?: return
        val updated = ChatReceiptPolicy.apply(
            message = message,
            receipt = receipt,
            localPeerId = sessionManager.peerId.value,
            currentEventId = eventId,
        ) ?: return
        sessionManager.receiveLocalChatMessage(updated)
    }

    suspend fun markVisibleMessagesRead(messageIds: List<String>) {
        if (!appearanceSettings.readReceiptsEnabled.first()) return
        val state = sessionManager.sessionState.value
        val eventId = state.currentEvent?.id ?: return
        if (readAcknowledgedEventId != eventId) {
            readAcknowledgedMessageIds.clear()
            readAcknowledgedEventId = eventId
        }
        val localPeerId = sessionManager.peerId.value
        val messagesById = state.chatMessages.associateBy { it.id }
        messageIds.distinct().forEach { messageId ->
            if (messageId in readAcknowledgedMessageIds) return@forEach
            val message = messagesById[messageId] ?: return@forEach
            if (message.entryType != ChatEntryType.MESSAGE || message.senderPeerId == localPeerId) {
                return@forEach
            }
            val recipient = message.recipientReceipts.firstOrNull { it.peerId == localPeerId }
                ?: return@forEach
            if (recipient.status == MessageStatus.READ) {
                readAcknowledgedMessageIds.add(messageId)
                return@forEach
            }
            readAcknowledgedMessageIds.add(messageId)
            transport.sendChatReceiptToPeer(
                message.senderPeerId,
                ChatReceiptPayloadDto(
                    eventCode = eventId,
                    messageId = message.id,
                    recipientPeerId = localPeerId,
                    status = MessageStatus.READ,
                ),
            )
        }
    }

    fun receiveHistory(history: ChatHistoryPayloadDto) {
        val validMessages = history.messages.mapNotNull { dto ->
            if (dto.entryType != ChatEntryType.MESSAGE && !isValidSystemEntry(dto)) {
                Log.w(TAG, "Ignoring invalid system chat entry ${dto.id} in history")
                return@mapNotNull null
            }
            dto.toChatMessage()
        }
        sessionManager.receiveChatHistory(validMessages)
    }

    fun currentHistory(): List<ChatPayloadDto> =
        sessionManager.sessionState.value.chatMessages.map { message ->
            ChatPayloadDto(
                id = message.id,
                senderPeerId = message.senderPeerId,
                senderName = message.senderName,
                text = message.messageText,
                timestamp = message.timestamp,
                colorArgb = message.colorArgb,
                entryType = message.entryType,
                subjectName = message.subjectName,
                subjectPeerId = message.subjectPeerId,
                recipientReceipts = message.recipientReceipts,
            )
        }

    fun sendHistoryToPeer(peerId: String) {
        transport.sendChatHistoryToPeer(peerId, currentHistory())
    }

    fun broadcastHistory() {
        if (sessionManager.sessionState.value.isHost) {
            transport.broadcastChatHistory(currentHistory())
        }
    }

    fun renameMemberHistory(peerId: String, name: String) {
        val oldHistory = sessionManager.sessionState.value.chatMessages
        sessionManager.renameMemberHistory(peerId, name)
        if (sessionManager.sessionState.value.chatMessages != oldHistory) {
            broadcastHistory()
        }
    }

    private fun publishSystemEntry(
        entryType: ChatEntryType,
        subjectName: String?,
        subjectPeerId: String?,
    ) {
        val state = sessionManager.sessionState.value
        val senderName = TextInputPolicy.sanitizeUsername(state.userName)
            .ifBlank { context.getString(R.string.host_default_name) }
        publish(
            ChatPayloadDto(
                id = UUID.randomUUID().toString(),
                senderPeerId = sessionManager.peerId.value,
                senderName = senderName,
                text = "",
                timestamp = System.currentTimeMillis(),
                entryType = entryType,
                subjectName = subjectName?.let(TextInputPolicy::sanitizeUsername),
                subjectPeerId = subjectPeerId,
            )
        )
    }

    private fun publish(dto: ChatPayloadDto) {
        sessionManager.receiveLocalChatMessage(dto.toChatMessage())
        transport.broadcastChatMessage(dto)
    }

    private fun isValidSystemEntry(dto: ChatPayloadDto): Boolean {
        val hostPeerId = sessionManager.sessionState.value.currentEvent?.hostPeerId
            ?: return false
        if (dto.senderPeerId != hostPeerId) return false
        return when (dto.entryType) {
            ChatEntryType.MESSAGE -> true
            ChatEntryType.PINGRING_CREATED -> dto.subjectPeerId == hostPeerId
            ChatEntryType.MEMBER_JOINED ->
                !dto.subjectPeerId.isNullOrBlank() && dto.subjectPeerId != hostPeerId
        }
    }

    private fun ChatPayloadDto.toChatMessage() = ChatMessage(
        id = id,
        senderPeerId = senderPeerId,
        senderName = senderName,
        messageText = text,
        timestamp = timestamp,
        colorArgb = colorArgb,
        entryType = entryType,
        subjectName = subjectName,
        subjectPeerId = subjectPeerId,
        recipientReceipts = recipientReceipts,
    )

    private companion object {
        const val TAG = "DiscoveryChat"
    }
}
