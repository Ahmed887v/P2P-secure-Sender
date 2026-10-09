package com.p2psecure.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TransferService : Service() {
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("transfer", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "transfer")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(7, n)
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p2p:tunnel").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            AppLog.w("wakelock ${e.message}")
        }
        running = true
        Thread {
            while (running) {
                try {
                    ShareRuntime.ensureStarted(applicationContext)
                    AppLog.i("service link=${ShareRuntime.receiverLink} err=${ShareRuntime.lastError}")
                    if (ShareRuntime.hasInternetLink()) {
                        Thread.sleep(8000)
                    } else {
                        Thread.sleep(4000)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    try {
                        Thread.sleep(6000)
                    } catch (_: Exception) {
                    }
                }
            }
        }.apply {
            name = "p2p-service"
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
        ShareRuntime.stop()
    }
}
