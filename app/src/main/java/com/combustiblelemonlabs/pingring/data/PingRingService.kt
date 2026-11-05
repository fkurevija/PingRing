package com.combustiblelemonlabs.pingring.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.combustiblelemonlabs.pingring.MainActivity
import com.combustiblelemonlabs.pingring.R

/**
 * Process-scoped owner for the active discovery session. The Activity configures this before
 * starting [PingRingService]; the service then keeps the same repository running after the
 * Activity is stopped or its task is removed.
 */
object PingRingRuntime {
    @Volatile
    private var discoveryRepository: DiscoveryRepository? = null

    fun configure(repository: DiscoveryRepository) {
        discoveryRepository = repository
    }

    fun startDiscovery() {
        discoveryRepository?.startDiscovery()
    }

    fun stopDiscovery() {
        discoveryRepository?.stopDiscovery()
    }
}

class PingRingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            PingRingRuntime.stopDiscovery()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID) ?: getString(R.string.active)
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            setAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_body, eventId))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        startForeground(NOTIFICATION_ID, notification, serviceType)
        PingRingRuntime.startDiscovery()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_notification_channel_description)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "pingring_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVICE = "com.combustiblelemonlabs.pingring.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.combustiblelemonlabs.pingring.STOP_SERVICE"
        const val EXTRA_EVENT_ID = "extra_event_id"

        fun start(context: Context, eventId: String) {
            val intent = Intent(context, PingRingService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PingRingService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
            PingRingRuntime.stopDiscovery()
        }
    }
}
