package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatMessage
import com.fkurevija.pingring.data.ChatReceiptPayloadDto
import com.fkurevija.pingring.data.ChatRecipientReceipt
import com.fkurevija.pingring.data.ChatReceiptPolicy
import com.fkurevija.pingring.data.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        val peerARead = ChatReceiptPolicy.apply(
            message,
            ChatReceiptPayloadDto("event", "message-1", "peer-a", MessageStatus.READ),
            "sender",
            "event",
        )!!
        val allRead = ChatReceiptPolicy.apply(
            peerARead,
            ChatReceiptPayloadDto("event", "message-1", "peer-b", MessageStatus.READ),
            "sender",
            "event",
        )!!

        assertEquals(MessageStatus.READ, allRead.status)
        assertEquals(
            MessageStatus.READ,
            ChatReceiptPolicy.merge(allRead, message).recipientReceipts.first().status,
        )
        assertEquals(allRead, ChatReceiptPolicy.merge(allRead, message.copy(recipientReceipts = emptyList())))
    }

}
