package com.combustiblelemonlabs.pingring.data.chat
import com.combustiblelemonlabs.pingring.data.ForegroundPresenceTracker
/**
 * Decides whether an incoming chat notification should be posted to the shade
 * or suppressed because the user is already looking at the app.
 *
 * This is deliberately a plain function of its four inputs so the rule can be
 * unit tested without an Android device; [ForegroundPresenceTracker] is the
 * Android-aware side that supplies those inputs.
 */
object ChatNotificationPolicy {

    /**
     * A notification is suppressed only when *all* of the following hold:
     * PingRing is the active foreground app, the screen is on, the device is
     * unlocked, and the radar/chat window is the one being displayed.
     *
     * Any one of them missing means the user cannot see the message land, so
     * the notification has to be posted:
     * - app backgrounded: the user is somewhere else entirely,
     * - screen off / locked: nobody is looking at the radar,
     * - another destination (home, host setup, settings): the radar/chat
     *   content is not what is on screen, so a chat message is not visible.
     */
    fun shouldSuppressNotification(
        appInForeground: Boolean,
        radarWindowVisible: Boolean,
        screenOn: Boolean,
        deviceUnlocked: Boolean,
    ): Boolean = appInForeground && radarWindowVisible && screenOn && deviceUnlocked
}
