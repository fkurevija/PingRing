package com.fkurevija.pingring.data.chat
import com.fkurevija.pingring.data.session.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationSettingsTest {

    @Test
    fun eventHasChatNotificationsEnabledByDefault() {
        val event = Event(
            id = "test-123",
            name = "Test PingRing",
            description = "Test Event",
            expiryTimestamp = System.currentTimeMillis() + 3600000
        )
        assertTrue(event.chatNotificationsEnabled)
    }

    @Test
    fun eventAllowsDisablingChatNotifications() {
        val event = Event(
            id = "test-123",
            name = "Test PingRing",
            description = "Test Event",
            expiryTimestamp = System.currentTimeMillis() + 3600000,
            chatNotificationsEnabled = false
        )
        assertFalse(event.chatNotificationsEnabled)

        val updatedEvent = event.copy(chatNotificationsEnabled = true)
        assertTrue(updatedEvent.chatNotificationsEnabled)
    }
}
