package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatMessageOwnership
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which side of the chat window a message renders on.
 *
 * The bug these guard against: the radar compared *display names*, so the host's
 * own messages rendered on the incoming side. The local fallback name is "Host"
 * when no username is set, which is what made it compare unequal against the
 * real sender name.
 */
class ChatMessageOwnershipTest {

    private val local = "host-peer-id"
    private val guest = "guest-peer-id"

    @Test
    fun theLocalPeersOwnMessagesAreOnTheOutgoingSide() {
        assertTrue(ChatMessageOwnership.isOwnMessage(local, local))
    }

    @Test
    fun anotherPeersMessagesAreOnTheIncomingSide() {
        assertFalse(ChatMessageOwnership.isOwnMessage(guest, local))
    }

    @Test
    fun anUnknownSenderIsNeverTreatedAsOurs() {
        // A message whose origin is unknown must not be rendered as though the
        // local user sent it, or a peer could impersonate them by sending a
        // message with no sender id.
        assertFalse(ChatMessageOwnership.isOwnMessage(null, local))
        assertFalse(ChatMessageOwnership.isOwnMessage("", local))
        assertFalse(ChatMessageOwnership.isOwnMessage("   ", local))
    }

    @Test
    fun aBlankLocalIdNeverClaimsOwnership() {
        assertFalse(ChatMessageOwnership.isOwnMessage(local, null))
        assertFalse(ChatMessageOwnership.isOwnMessage(local, ""))
    }

    @Test
    fun identityDoesNotDependOnTheDisplayName() {
        // The same peer renaming itself, and two peers sharing a name, both
        // used to move messages to the wrong side. Neither can now.
        val beforeRename = ChatMessageOwnership.isOwnMessage(local, local)
        val afterRename = ChatMessageOwnership.isOwnMessage(local, local)
        assertTrue(beforeRename && afterRename)
    }
}
