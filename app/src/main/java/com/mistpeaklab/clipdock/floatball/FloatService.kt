package com.mistpeaklab.clipdock.floatball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.mistpeaklab.clipdock.MainActivity
import com.mistpeaklab.clipdock.R
import com.mistpeaklab.clipdock.tr

/**
 * 没开增强模式（无障碍）时悬浮球的宿主：一个前台服务，用普通悬浮窗（需要“显示在其他应用上层”权限）。
 * 前台服务必须挂一条通知；渠道重要性设为最低，不响、不弹出。
 */
class FloatService : Service() {

    companion object {
        private const val CHANNEL = "clipdock_float"
        private const val NOTIF_ID = 7

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, FloatService::class.java)) }
        }

        fun stop(ctx: Context) {
            if (running) runCatching { ctx.stopService(Intent(ctx, FloatService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        FloatBall.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        FloatBall.detach(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("悬浮球", "Floating bubble"), NotificationManager.IMPORTANCE_MIN).apply {
                description = tr("悬浮球运行时系统要求显示的通知，可以在这里关掉它的显示", "Required while the bubble is running; you can hide it here")
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(tr("随贴悬浮球", "ClipDock bubble"))
            .setContentText(tr("点悬浮球打开面板，长按打开控制台", "Tap the bubble for the panel, long-press for the menu"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
