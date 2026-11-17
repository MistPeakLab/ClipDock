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
import com.mistpeaklab.clipdock.ClipStore
import com.mistpeaklab.clipdock.MainActivity
import com.mistpeaklab.clipdock.Prefs
import com.mistpeaklab.clipdock.R
import com.mistpeaklab.clipdock.WidgetActionActivity
import com.mistpeaklab.clipdock.tr

/**
 * 常驻服务（前台服务），两个用途，可以同时有：
 * - 没开增强模式（无障碍）时，承载悬浮球（普通悬浮窗，需要“显示在其他应用上层”权限）；
 * - 常驻通知：通知栏里显示最近 3 条，点按钮直接复制。
 *
 * 前台服务必须挂一条通知。只为悬浮球运行时，渠道重要性最低，不响、不弹出；
 * 开了常驻通知时就把这条通知本身做成有用的：最近的内容 + 复制按钮。
 * 要不要运行、要不要承载悬浮球都由 [FloatBall.sync] 决定。
 */
class FloatService : Service() {

    companion object {
        private const val CHANNEL = "clipdock_float"
        private const val CHANNEL_RESIDENT = "clipdock_resident"
        private const val NOTIF_ID = 7

        @Volatile var instance: FloatService? = null
            private set

        val running get() = instance != null

        fun start(ctx: Context) {
            if (running) return
            runCatching { ctx.startForegroundService(Intent(ctx, FloatService::class.java)) }
        }

        fun stop(ctx: Context) {
            if (running) runCatching { ctx.stopService(Intent(ctx, FloatService::class.java)) }
        }

        /** 记录变了：刷新常驻通知（服务没在运行时什么也不做） */
        fun refresh(ctx: Context) {
            val s = instance ?: return
            runCatching { s.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, s.notification()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ClipStore.init(this)
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
        instance = null
        FloatBall.detach(this)
        super.onDestroy()
    }

    private fun channels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("悬浮球", "Floating bubble"), NotificationManager.IMPORTANCE_MIN).apply {
                description = tr("悬浮球运行时系统要求显示的通知，可以在这里关掉它的显示", "Required while the bubble is running; you can hide it here")
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_RESIDENT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_RESIDENT, tr("常驻通知", "Persistent notification"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("通知栏里显示最近复制的内容，点一下复制", "Shows recently copied text in the shade; tap to copy")
                setShowBadge(false)
            })
        }
    }

    internal fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        channels(nm)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val p = Prefs(this)
        if (!p.notifEnabled) {
            return Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(tr("随贴悬浮球", "ClipDock bubble"))
                .setContentText(tr("点悬浮球打开面板，长按打开控制台", "Tap the bubble for the panel, long-press for the menu"))
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
        // 常驻通知：最近 3 条（隐藏的不放：通知可能出现在锁屏上）
        val recent = ClipStore.history().filter { !it.hidden }.sortedByDescending { it.time }.take(3)
        val oneLine = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
        val b = Notification.Builder(this, CHANNEL_RESIDENT)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(if (p.capturePaused) tr("随贴 · 记录已暂停", "ClipDock · paused") else tr("随贴 · 最近复制", "ClipDock · recent"))
            .setContentText(recent.firstOrNull()?.let { oneLine(it.preview) } ?: tr("还没有记录", "Nothing yet"))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(this, CHANNEL_RESIDENT).setSmallIcon(R.drawable.ic_launcher_mono)
                    .setContentTitle(tr("随贴", "ClipDock")).setContentText(tr("解锁后查看最近复制的内容", "Unlock to see recent items")).build(),
            )
        if (recent.isNotEmpty()) {
            b.setStyle(Notification.InboxStyle().also { st -> recent.forEach { st.addLine(oneLine(it.preview).take(60)) } })
        }
        recent.forEachIndexed { i, item ->
            val label = oneLine(item.preview).let { if (it.length > 8) it.take(8) + "…" else it }
            val pi = PendingIntent.getActivity(
                this, 100 + i,
                Intent(this, WidgetActionActivity::class.java).putExtra(WidgetActionActivity.EXTRA_ID, item.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        return b.build()
    }
}
