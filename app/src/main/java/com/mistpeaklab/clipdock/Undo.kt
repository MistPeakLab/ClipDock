package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 删除后 5 秒内可以撤销：屏幕上方出现一个小条“已删除 2 条 · 撤销”，不抢输入框的焦点。
 * 删除本来就会进回收站，这里只是让“手滑删掉”不用跑去回收站找。
 * 回收站功能关掉时删除是彻底的，这时不显示（也撤不回来）。
 */
object Undo {
    private const val SHOW_MS = 5_000L

    private var ids: List<String> = emptyList()
    private var pill: View? = null
    private var host: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { clear() }

    /** 记下刚删掉的那几条，并显示撤销小条 */
    fun offer(ctx: Context, deleted: Collection<String>) {
        if (deleted.isEmpty() || !Features.isOn(ctx, Feature.TRASH)) return
        ids = deleted.toList()
        show(ctx)
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MS)
    }

    /** 撤销最近一次删除。没有可撤销的返回 false */
    fun undo(ctx: Context): Boolean {
        val list = ids
        if (list.isEmpty()) return false
        clear()
        list.forEach { ClipStore.restore(it) }
        ctx.toast(tr("已恢复 %d 条", "Restored %d", list.size))
        return true
    }

    private fun clear() {
        main.removeCallbacks(hide)
        ids = emptyList()
        val v = pill
        pill = null
        if (v != null) runCatching { host?.removeViewImmediate(v) }
        host = null
    }

    @SuppressLint("SetTextI18n")
    private fun show(ctx: Context) {
        // 小条已经在显示：只更新文字
        (pill?.findViewById<TextView>(R.id.undo_label))?.let { it.text = label(); return }
        val svc = ClipService.instance
        val (wctx, type) = when {
            svc != null -> svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(ctx) -> ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return // 显示不了小条，删除照样进回收站，可以去那里恢复
        }
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()
        val bar = LinearLayout(wctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xF0202124.toInt()); cornerRadius = dp(20).toFloat() }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(4), dp(4), dp(4))
        }
        val l = TextView(wctx).apply {
            id = R.id.undo_label
            text = label()
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(l)
        val undoBtn = TextView(wctx).apply {
            text = tr("撤销", "Undo")
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0xFF4C8DF6.toInt()); cornerRadius = dp(16).toFloat() }
            setOnClickListener { undo(wctx) }
        }
        bar.addView(undoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            // 不能抢焦点：这时用户可能正在输入框里打字
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 放在底部，别挡住顶部的搜索框
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        val wm = wctx.getSystemService(WindowManager::class.java)
        runCatching { wm.addView(bar, lp) }.onSuccess {
            pill = bar
            host = wm
        }.onFailure { pill = null; host = null }
    }

    private fun label() = tr("已删除 %d 条", "Deleted %d", ids.size)
}
