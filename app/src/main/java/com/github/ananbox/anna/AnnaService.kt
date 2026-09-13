package com.github.ananbox.anna

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
import com.github.ananbox.MainActivity
import com.github.ananbox.R
import java.io.File

/**
 * Foreground service that keeps the Anna gateway alive while the container is
 * running, including when the activity is in the background.
 */
class AnnaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AnnaCore.stopGateway()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!AnnaCore.isInitialized) {
            AnnaCore.init(applicationContext, File(filesDir, "rootfs"))
        }
        AnnaCore.startGateway()
        return START_STICKY
    }

    override fun onDestroy() {
        AnnaCore.stopGateway()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.anna_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.anna_channel_description) }
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.anna_notification_title))
            .setContentText(getString(R.string.anna_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "anna_gateway"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.github.ananbox.anna.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AnnaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnnaService::class.java))
        }
    }
}
