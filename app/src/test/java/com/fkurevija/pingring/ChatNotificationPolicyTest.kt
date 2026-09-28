package com.fkurevija.pingring

import com.fkurevija.pingring.data.ChatNotificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an incoming chat notification is posted or
 * dropped because the user is already watching the radar/chat window.
 */
class ChatNotificationPolicyTest {

    private fun suppress(
        appInForeground: Boolean = true,
        radarWindowVisible: Boolean = true,
        screenOn: Boolean = true,
        deviceUnlocked: Boolean = true,
    ) = ChatNotificationPolicy.shouldSuppressNotification(
        appInForeground = appInForeground,
        radarWindowVisible = radarWindowVisible,
        screenOn = screenOn,
        deviceUnlocked = deviceUnlocked,
    )

    @Test
    fun suppressesWhenWatchingRadarWithScreenOnAndUnlocked() {
        assertTrue(suppress())
    }

    @Test
    fun notifiesWhenAppIsBackgrounded() {
        assertFalse(suppress(appInForeground = false))
    }

    @Test
    fun notifiesWhenScreenIsOff() {
        assertFalse(suppress(screenOn = false))
    }

    @Test
    fun notifiesWhenDeviceIsLocked() {
        assertFalse(suppress(deviceUnlocked = false))
    }

    @Test
    fun notifiesWhenRadarWindowIsNotOnScreen() {
        // Inside the app, but on home / host setup / settings: the chat
        // message is not visible, so it still has to notify.
        assertFalse(suppress(radarWindowVisible = false))
    }

    @Test
    fun notifiesWhenBackgroundedWithScreenOffAndLocked() {
        assertFalse(
            suppress(
                appInForeground = false,
                screenOn = false,
                deviceUnlocked = false,
            )
        )
    }
}
