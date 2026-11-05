package com.combustiblelemonlabs.pingring.data.chat
/**
 * Represents the delivery state of a chat message.
 */
@kotlinx.serialization.Serializable
enum class MessageStatus {
    /** Message has been sent but not yet acknowledged. */
    SENT,
    /** Message has been delivered to the recipient. */
    DELIVERED,
    /** Message has been read by the recipient. */
    READ
}

@kotlinx.serialization.Serializable
data class ChatRecipientReceipt(
    val peerId: String,
    val name: String,
    val status: MessageStatus = MessageStatus.SENT,
)
