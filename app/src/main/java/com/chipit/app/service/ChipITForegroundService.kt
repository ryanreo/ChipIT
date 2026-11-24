package com.chipit.app.service

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
import com.chipit.app.ChipITApplication
import com.chipit.app.MainActivity

class ChipITForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AGENT) {
            val app = application as? ChipITApplication
            app?.container?.let { c ->
                c.agentOrchestrator.kill(c.activeEngine())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Local LLM & Agent active"
        startForeground(NOTIFICATION_ID, buildNotification(statusText))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "ChipIT Active Runtime",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ChipITForegroundService::class.java).apply { action = ACTION_STOP_AGENT },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ChipIT Runtime")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Agent (Kill Switch)", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chipit_runtime"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP_AGENT = "com.chipit.app.ACTION_STOP_AGENT"
        const val EXTRA_STATUS = "status"

        fun start(context: Context, status: String) {
            val intent = Intent(context, ChipITForegroundService::class.java).apply {
                putExtra(EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChipITForegroundService::class.java))
        }
    }
}
