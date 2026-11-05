package com.combustiblelemonlabs.pingring.data.chat
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
import com.combustiblelemonlabs.pingring.data.chat.ChatEntryType
import com.combustiblelemonlabs.pingring.data.chat.ChatReceiptPayloadDto
import com.combustiblelemonlabs.pingring.data.chat.ChatRecipientReceipt
import com.combustiblelemonlabs.pingring.data.chat.ChatReceiptPolicy
import com.combustiblelemonlabs.pingring.data.chat.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.Event

class ChatReceiptPolicyTest {
    private val message = ChatMessage(
        id = "message-1",
        senderPeerId = "sender",
        senderName = "Sender",
        messageText = "Hello",
        timestamp = 1L,
        recipientReceipts = listOf(
            ChatRecipientReceipt("peer-a", "Alice"),
            ChatRecipientReceipt("peer-b", "Bob"),
        ),
    )

    @Test
    fun onlyVisibleUnreadIncomingMessagesAddressedToMeAreEligibleForReadAcknowledgment() {
        val incoming = message.copy(senderPeerId = "peer-b")
        val outgoing = message.copy(id = "outgoing", senderPeerId = "peer-a")
        val alreadyRead = incoming.copy(
            id = "already-read",
            recipientReceipts = incoming.recipientReceipts.map { recipient ->
                if (recipient.peerId == "peer-a") recipient.copy(status = MessageStatus.READ) else recipient
            },
        )
        val systemEntry = incoming.copy(id = "system", entryType = ChatEntryType.MEMBER_JOINED)
        val messages = listOf(incoming, outgoing, alreadyRead, systemEntry)

        assertEquals(
            listOf(incoming),
            ChatReceiptPolicy.visibleIncomingMessagesToAcknowledge(
                messages = messages,
                visibleMessageIds = listOf("message-1", "outgoing", "system"),
                localPeerId = "peer-a",
                readReceiptsEnabled = true,
            ),
        )
        assertEquals(
            emptyList<ChatMessage>(),
            ChatReceiptPolicy.visibleIncomingMessagesToAcknowledge(
                messages = messages,
                visibleMessageIds = listOf("message-1"),
                localPeerId = "peer-a",
                readReceiptsEnabled = false,
            ),
        )
    }

    @Test
    fun appliesMonotonicReceiptsOnlyForTheMatchingSenderEventMessageAndRecipient() {
        val delivered = ChatReceiptPolicy.apply(
            message,
            ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.DELIVERED),
            "sender",
            "event",
        )

        assertEquals(MessageStatus.DELIVERED, delivered?.recipientReceipts?.first()?.status)
        assertEquals(MessageStatus.SENT, delivered?.recipientReceipts?.last()?.status)
        assertEquals(MessageStatus.SENT, delivered?.status)
        assertNull(
            ChatReceiptPolicy.apply(
                delivered!!,
                ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.SENT),
                "sender",
                "event",
            )
        )
        assertNull(
            ChatReceiptPolicy.apply(
                message,
                ChatReceiptPayloadDto("other-event", "message-1", "peer-a", MessageStatus.READ),
                "sender",
                "event",
            )
        )
        assertNull(
            ChatReceiptPolicy.apply(
                message,
                ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.READ),
                "different-local-peer",
                "event",
            )
        )
        assertNull(
            ChatReceiptPolicy.apply(
                message,
                ChatReceiptPayloadDto("event", "message-1", "unknown", MessageStatus.READ),
                "sender",
                "event",
            )
        )
    }

    @Test
    fun calculatesAggregateStatusAndKeepsItWhenOlderHistoryArrives() {
        val peerADelivered = ChatReceiptPolicy.apply(
            message,
            ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.DELIVERED),
            "sender",
            "event",
        )!!
        val peerBRead = ChatReceiptPolicy.apply(
            peerADelivered,
            ChatReceiptPayloadDto("event", "message-1", "peer-b", MessageStatus.READ),
            "sender",
            "event",
        )!!
        assertEquals(MessageStatus.DELIVERED, peerBRead.status)

        val peerARead = ChatReceiptPolicy.apply(
            peerBRead,
            ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.READ),
            "sender",
            "event",
        )!!
        val allRead = peerARead

        assertEquals(MessageStatus.READ, allRead.status)
        assertEquals(
            MessageStatus.READ,
            ChatReceiptPolicy.merge(allRead, message).recipientReceipts.first().status,
        )
        assertEquals(allRead, ChatReceiptPolicy.merge(allRead, message.copy(recipientReceipts = emptyList())))
    }
}
