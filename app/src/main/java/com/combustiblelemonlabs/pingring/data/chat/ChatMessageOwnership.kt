package com.combustiblelemonlabs.pingring.data.chat
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
/**
 * Decides which side of the chat window a message belongs on.
 *
 * This used to compare display names, which is wrong in a few ways that all
 * show up as messages rendering on the sender's *other* side:
 *
 *  - The local fallback name is "Host" or "Peer" when no username is set, so a
 *    message from a genuinely anonymous peer compared equal to your own.
 *  - Two members can end up with the same display name, so someone else's
 *    message matched and appeared as yours.
 *  - The name a message was sent with is a snapshot; renaming yourself
 *    afterwards left your own older messages on the wrong side.
 *
 * The peer id is the stable identity for the whole session and is already
 * carried on every [ChatMessage], so that is what this compares.
 */
object ChatMessageOwnership {

    /**
     * True when [senderPeerId] is this device.
     *
     * A blank sender id is treated as *not* ours: a message with no known
     * origin must never be rendered as though the local user sent it.
     */
    fun isOwnMessage(senderPeerId: String?, localPeerId: String?): Boolean {
        if (senderPeerId.isNullOrBlank() || localPeerId.isNullOrBlank()) return false
        return senderPeerId == localPeerId
    }
}
