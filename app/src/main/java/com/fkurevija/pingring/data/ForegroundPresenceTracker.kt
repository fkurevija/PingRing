package com.fkurevija.pingring.data

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import com.fkurevija.pingring.data.chat.ChatNotificationPolicy

/**
 * Process-wide record of what the user is currently looking at, so that
 * [DiscoveryRepository] can decide between posting a chat notification and
 * staying quiet (see [ChatNotificationPolicy]).
 *
 * Two independent pieces of state are tracked, because either one alone is
 * not enough to conclude the user is watching the radar:
 *
 * - [onActivityForegroundChanged] from the activity lifecycle, so a
 *   backgrounded PingRing (the normal case, the mesh runs as a foreground
 *   service) still notifies.
 * - [onRadarWindowVisibilityChanged] from the top entry of the navigation
 *   back stack, so being in the app is not mistaken for being on the
 *   radar/chat window.
 *
 * Screen on/unlocked state is queried live rather than cached, because it
 * changes without any lifecycle or navigation callback firing.
 */
object ForegroundPresenceTracker {

    private val activityInForeground = AtomicBoolean(false)
    private val radarWindowVisible = AtomicBoolean(false)

    fun onActivityForegroundChanged(inForeground: Boolean) {
        activityInForeground.set(inForeground)
    }

    fun onRadarWindowVisibilityChanged(visible: Boolean) {
        radarWindowVisible.set(visible)
    }

    /** True when the radar/chat window is the top destination of the app. */
    fun isRadarWindowVisible(): Boolean = radarWindowVisible.get()

    /** True while the PingRing activity is resumed (i.e. the app is in front). */
    fun isAppInForeground(): Boolean = activityInForeground.get()

    fun isScreenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun isDeviceUnlocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    /**
     * Convenience wrapper for the one caller that posts chat notifications.
     * The screen/lock checks are only worth paying for once the cheaper
     * lifecycle and navigation state says a suppression is even possible.
     */
    fun shouldSuppressChatNotification(context: Context): Boolean {
        if (!activityInForeground.get() || !radarWindowVisible.get()) return false
        return ChatNotificationPolicy.shouldSuppressNotification(
            appInForeground = true,
            radarWindowVisible = true,
            screenOn = isScreenOn(context),
            deviceUnlocked = isDeviceUnlocked(context),
        )
    }
}
