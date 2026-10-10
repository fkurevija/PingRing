package com.fkurevija.pingring.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.fkurevija.pingring.MainActivity
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * What a PingRing does when a chat message arrives: a shade notification, a
 * vibration, a sound, or some combination of the three.
 *
 * This is a separate concern from receiving the message (the transport) and from
 * keeping the conversation (the session), and it is the only part of chat that
 * touches the notification system - which has rules of its own that the rest of
 * the app has no business knowing, such as a channel's sound and vibration being
 * fixed when it is created and impossible to change afterwards.
 */
class ChatNotifier(
    private val context: Context,
    private val appearanceSettings: AppearanceSettingsRepository,
    /** Supplies the name of the event a message belongs to, if one is running. */
    private val currentEventName: () -> String?,
) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Announces an incoming chat message.
     *
     * When the user is already watching the radar/chat window the shade
     * notification is redundant noise, so it is dropped - but the device is still
     * allowed to vibrate and/or make a sound, because a message can still be
     * worth a haptic while the user's eyes are on the radar. Those two channels
     * are controlled independently from app settings.
     */
    suspend fun onChatMessageReceived(chatMessage: ChatMessage) {
        val soundEnabled = appearanceSettings.notificationSoundEnabled.first()
        val vibrationEnabled = appearanceSettings.notificationVibrationEnabled.first()

        if (ForegroundPresenceTracker.shouldSuppressChatNotification(context)) {
            if (vibrationEnabled) playVibration()
            if (soundEnabled) playSound()
            return
        }

        postNotification(chatMessage, soundEnabled, vibrationEnabled)
    }

    private fun playVibration() {
        val deviceVibrator = vibrator ?: return
        try {
            val effect = VibrationEffect.createWaveform(CHAT_VIBRATION_PATTERN, -1)
            deviceVibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Chat vibration playback failed", e)
        }
    }

    /**
     * Plays the same tone the notification would have used, straight through the
     * media stack. Respects the device's notification volume and mute state
     * because it is the notification stream that is used.
     */
    private fun playSound() {
        val uri: Uri = Settings.System.DEFAULT_NOTIFICATION_URI
        try {
            RingtoneManager.getRingtone(context, uri)?.apply {
                if (!isPlaying) play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chat notification sound playback failed", e)
        }
    }

    private fun postNotification(
        chatMessage: ChatMessage,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        createChannel(soundEnabled, vibrationEnabled)

        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Keyed by the message so two messages from the same person do not collapse
        // into one notification, and a message that is read and dismissed does not
        // get reused by the next one.
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatMessage.id.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val eventName = currentEventName() ?: context.getString(R.string.app_name)

        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setContentTitle("$eventName • ${chatMessage.senderName}")
            .setContentText(chatMessage.messageText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(CHAT_NOTIFICATION_GROUP)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(chatMessage.id.hashCode(), notification)
    }

    /**
     * Creates (or re-creates) the notification channel to match the user's
     * sound/vibration preferences.
     *
     * A [NotificationChannel]'s sound and vibration are fixed at creation time
     * and cannot be changed afterwards, so honouring a settings toggle means
     * comparing the desired configuration against the live channel and deleting
     * it when they disagree. Deleting also drops the user's own system-level
     * customisation of that channel, which is unavoidable here and is preferable
     * to silently ignoring the in-app setting.
     */
    private fun createChannel(soundEnabled: Boolean, vibrationEnabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val desiredSound: Uri? = if (soundEnabled) Settings.System.DEFAULT_NOTIFICATION_URI else null
        val desiredVibration: LongArray? = ChatChannelPolicy.vibrationPatternFor(vibrationEnabled, CHAT_VIBRATION_PATTERN)

        // LongArray is compared by content, not identity: vibrationPattern
        // hands back a fresh array on every read, so == would never match and
        // the channel would be needlessly recreated on every message. The rule
        // itself lives in ChatChannelPolicy so it can be tested without a live
        // NotificationManager.
        manager.getNotificationChannel(CHAT_CHANNEL_ID)?.let { existing ->
            val live = ChatChannelPolicy.LiveConfiguration(
                sound = existing.sound?.toString(),
                vibrationPattern = existing.vibrationPattern,
            )
            val desired = ChatChannelPolicy.DesiredConfiguration(
                sound = desiredSound?.toString(),
                vibrationPattern = desiredVibration,
            )
            if (ChatChannelPolicy.needsRecreation(live, desired)) {
                manager.deleteNotificationChannel(CHAT_CHANNEL_ID)
            }
        }

        val channel = NotificationChannel(
            CHAT_CHANNEL_ID,
            context.getString(R.string.chat_notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.chat_notification_channel_description)
            enableVibration(vibrationEnabled)
            if (desiredVibration != null) {
                vibrationPattern = desiredVibration
            }
            setSound(
                desiredSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "ChatNotifier"

        /**
         * The channel id and group are part of the app's on-device state: a device
         * that has already created `pingring_chat_channel` keeps using that
         * channel's user-customised name, importance and sound, so these values
         * must not change without a migration.
         */
        const val CHAT_CHANNEL_ID = "pingring_chat_channel"
        const val CHAT_NOTIFICATION_GROUP = "com.fkurevija.pingring.CHAT_NOTIFICATION"

        /** Two short pulses: distinguishable from a beacon, not a nuisance. */
        val CHAT_VIBRATION_PATTERN = longArrayOf(0, 120, 90, 120)
    }
}
