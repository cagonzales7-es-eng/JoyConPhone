package com.joydroid.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class JoyConService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val side = runCatching { Side.valueOf(intent?.getStringExtra("side") ?: "RIGHT") }
            .getOrDefault(Side.RIGHT)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jc", "Joy-Con activo", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "jc")
            .setContentTitle("Joy-Con Phone activo")
            .setContentText(if (side == Side.LEFT) "Modo Joy-Con izquierdo" else "Modo Joy-Con derecho")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val mode = runCatching { Mode.valueOf(intent?.getStringExtra("mode") ?: "BLUETOOTH") }
            .getOrDefault(Mode.BLUETOOTH)
        val ip = intent?.getStringExtra("ip") ?: ""
        JoyConEngine.start(applicationContext, side, mode, ip)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        JoyConEngine.stop()
        super.onDestroy()
    }
}
