package com.terminuke.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.terminuke.app.R
import com.terminuke.app.TerminukeApp

class SshService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            (application as TerminukeApp).container.sessions.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        (application as TerminukeApp).container.sessions.disconnect()
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_active))
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Disconnect",
            PendingIntent.getService(
                this,
                0,
                Intent(this, SshService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private companion object {
        const val CHANNEL_ID = "ssh-session"
        const val NOTIFICATION_ID = 7301
        const val ACTION_DISCONNECT = "com.terminuke.app.action.DISCONNECT"
    }
}
