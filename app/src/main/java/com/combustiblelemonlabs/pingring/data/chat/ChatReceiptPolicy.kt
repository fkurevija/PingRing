package com.combustiblelemonlabs.pingring.data.chat
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
/** Applies authenticated per-recipient receipt updates without allowing state regression. */
object ChatReceiptPolicy {
    fun visibleIncomingMessagesToAcknowledge(
        messages: List<ChatMessage>,
        visibleMessageIds: Collection<String>,
        localPeerId: String,
        readReceiptsEnabled: Boolean,
    ): List<ChatMessage> {
        if (!readReceiptsEnabled || visibleMessageIds.isEmpty()) return emptyList()
        val visibleIds = visibleMessageIds.toSet()
        return messages.filter { message ->
            message.id in visibleIds &&
                message.entryType == ChatEntryType.MESSAGE &&
                message.senderPeerId != localPeerId &&
                message.recipientReceipts.any {
                    it.peerId == localPeerId && it.status != MessageStatus.READ
                }
        }
    }

    fun merge(existing: ChatMessage, incoming: ChatMessage): ChatMessage {
        if (existing.id != incoming.id) return incoming
        if (incoming.recipientReceipts.isEmpty()) {
            return if (existing.recipientReceipts.isEmpty()) incoming
            else incoming.copy(
                recipientReceipts = existing.recipientReceipts,
                status = existing.status,
            )
        }
        val existingByPeer = existing.recipientReceipts.associateBy { it.peerId }
        val recipients = incoming.recipientReceipts.map { recipient ->
            val previous = existingByPeer[recipient.peerId] ?: return@map recipient
            if (rank(previous.status) > rank(recipient.status)) {
                recipient.copy(status = previous.status)
            } else {
                recipient
            }
        }
        return incoming.copy(
            recipientReceipts = recipients,
            status = aggregateStatus(recipients),
        )
    }

    fun apply(
        message: ChatMessage,
        receipt: ChatReceiptPayloadDto,
        localPeerId: String,
        currentEventId: String,
    ): ChatMessage? {
        if (message.senderPeerId != localPeerId ||
            receipt.eventCode != currentEventId ||
            receipt.messageId != message.id ||
            receipt.status == MessageStatus.SENT
        ) return null

        val index = message.recipientReceipts.indexOfFirst { it.peerId == receipt.recipientPeerId }
        if (index < 0) return null
        val current = message.recipientReceipts[index]
        if (rank(receipt.status) <= rank(current.status)) return null

        val updatedRecipients = message.recipientReceipts.toMutableList().also {
            it[index] = current.copy(status = receipt.status)
        }
        return message.copy(
            recipientReceipts = updatedRecipients,
            status = aggregateStatus(updatedRecipients),
        )
    }

    private fun aggregateStatus(recipients: List<ChatRecipientReceipt>): MessageStatus = when {
        recipients.isNotEmpty() && recipients.all { it.status == MessageStatus.READ } -> MessageStatus.READ
        recipients.isNotEmpty() && recipients.all { it.status != MessageStatus.SENT } -> MessageStatus.DELIVERED
        else -> MessageStatus.SENT
    }

    private fun rank(status: MessageStatus): Int = when (status) {
        MessageStatus.SENT -> 0
        MessageStatus.DELIVERED -> 1
        MessageStatus.READ -> 2
    }
}
