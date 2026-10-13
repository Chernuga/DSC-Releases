package com.example.deepseekstandalone

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

/**
 * Keeps the bridge process alive while the phone is locked or the app is in the
 * background.
 *
 * Without this, Android freezes the process as soon as the screen locks: the
 * WebView stops executing JavaScript, the local HTTP server stops answering and
 * the tunnel WebSocket dies. Every request then fails even though the toggle is
 * still on.
 *
 * The service deliberately does no networking itself - MainActivity owns the
 * server, the tunnel and the WebView. This exists only to hold the process in a
 * foreground state so that work can continue.
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // Restart if the system kills us while the bridge is meant to be on.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the DeepSeek bridge running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepSeek bridge is on")
            .setContentText("Keeping the tunnel available in the background")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "deepseek_bridge"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
