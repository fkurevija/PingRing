package com.fkurevija.pingring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fkurevija.pingring.data.ChatRecipientReceipt
import com.fkurevija.pingring.data.MessageStatus
import com.fkurevija.pingring.data.SessionState
import org.junit.Test

class RadarChatReceiptsInstrumentedTest : RadarScreenTestSupport() {
    @Test
    fun outgoingMessagesShowDeliveredAndReadCountsWithPerMemberDetails() {
        val message = chatMessage("me", "group hello", colorArgb = null).copy(
            recipientReceipts = listOf(
                ChatRecipientReceipt("alice", "Alice", MessageStatus.READ),
                ChatRecipientReceipt("bob", "Bob", MessageStatus.DELIVERED),
            ),
        )
        showRadar(
            localPeerId = "me",
            sessionState = SessionState(chatMessages = listOf(message)),
        )

        composeRule.onNodeWithText(
            context.getString(com.fkurevija.pingring.R.string.chat_receipt_counts, 2, 2, 1, 2)
        ).assertIsDisplayed().performClick()
        composeRule.settle()

        composeRule.onNodeWithText(context.getString(com.fkurevija.pingring.R.string.chat_receipt_details))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Alice: ${context.getString(com.fkurevija.pingring.R.string.chat_receipt_read)}")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Bob: ${context.getString(com.fkurevija.pingring.R.string.chat_receipt_delivered)}")
            .assertIsDisplayed()
    }

    @Test
    fun outgoingLegacyMessagesKeepTheirSentLabel() {
        showRadar(
            localPeerId = "me",
            sessionState = SessionState(
                chatMessages = listOf(chatMessage("me", "legacy sent", colorArgb = null)),
            ),
        )

        composeRule.onNodeWithText(context.getString(com.fkurevija.pingring.R.string.chat_receipt_sent))
            .assertIsDisplayed()
    }
}
