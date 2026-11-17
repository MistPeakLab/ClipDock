package com.mistpeaklab.clipdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mistpeaklab.clipdock.floatball.FloatBall

/**
 * 开机、应用更新后：把悬浮球和常驻通知按设置恢复。
 * 开着无障碍时由无障碍服务自己恢复（服务连上时会调 FloatBall.sync）；没开时要靠这里启动常驻服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val p = Prefs(ctx)
                if (p.floatEnabled || p.notifEnabled) runCatching { FloatBall.sync(ctx) }
                // 读完记录后会自己刷新一次小部件（见 ClipStore.commit → Surfaces）
                runCatching { ClipStore.init(ctx) }
            }
        }
    }
}
