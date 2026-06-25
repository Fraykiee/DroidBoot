package com.fraykiee.droidboot.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Держит процесс живым, пока телефон работает как загрузочная флешка.
 * Сам гаджет уже поднят менеджером; сервис нужен для приоритета процесса
 * и видимого пользователю статуса.
 */
class GadgetService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "образ"
        startForeground(NOTIF_ID, buildNotification(name))
        return START_STICKY
    }

    private fun buildNotification(name: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "USB-гаджет", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("DroidBoot активен")
            .setContentText("Телефон эмулирует загрузочную флешку: $name")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "droidboot_gadget"
        private const val NOTIF_ID = 1001
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, imageName: String) {
            val i = Intent(ctx, GadgetService::class.java).putExtra(EXTRA_NAME, imageName)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GadgetService::class.java))
        }
    }
}
