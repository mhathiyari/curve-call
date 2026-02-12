package com.curvecall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.curvecall.MainActivity

/**
 * Foreground service that keeps GPS tracking and TTS narration active
 * when the app is in the background.
 *
 * Required for reliable location updates and audio playback while the user
 * is interacting with other apps (e.g., a navigation app alongside CurveCall).
 *
 * The service displays a persistent notification while active, allowing the
 * user to return to the session screen with a single tap.
 *
 * PRD: "Handle background audio (foreground service for GPS + TTS)"
 */
class SessionForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "curvecall_session"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.curvecall.action.START_SESSION"
        const val ACTION_STOP = "com.curvecall.action.STOP_SESSION"
        const val ACTION_PAUSE = "com.curvecall.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.curvecall.action.RESUME_SESSION"

        /**
         * Start the foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = createNotification("CurveCall session active")
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_PAUSE -> {
                updateNotification("Session paused")
            }
            ACTION_RESUME -> {
                updateNotification("CurveCall session active")
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when CurveCall is actively narrating curves"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CurveCall")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
