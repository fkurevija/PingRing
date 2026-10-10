package com.fkurevija.pingring.data

/**
 * Represents the delivery state of a chat message.
 */
enum class MessageStatus {
    /** Message has been sent but not yet acknowledged. */
    SENT,
    /** Message has been delivered to the recipient. */
    DELIVERED,
    /** Message has been read by the recipient. */
    READ
}
