package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 依次粘贴：选好几条，之后每“粘贴一次”就换到下一条，适合一格一格地填表。
 *
 * 开始以后屏幕上方出现一个小条“依次粘贴 2/5 · 下一条 · ×”，不抢输入框的焦点：
 * 把光标放到下一个输入框，点“下一条”（或者按唤起面板的快捷键）就贴进去。
 * 没开增强模式时贴不进去，只能放进剪贴板，由用户自己长按粘贴。
 */
object PasteQueue {
    private var texts: List<String> = emptyList()
    private var index = 0
    private var pill: View? = null
    private var label: TextView? = null
    private var host: WindowManager? = null

    val active: Boolean get() = texts.isNotEmpty()

    /** @param pasteFirst 是否马上贴出第一条（在悬浮面板里开始时是；在主界面里开始时不是：那时焦点在随贴自己身上） */
    fun start(ctx: Context, list: List<String>, pasteFirst: Boolean) {
        cancel()
        if (list.isEmpty()) return
        texts = list
        index = 0
        if (pasteFirst) next(ctx) else showPill(ctx)
    }

    /** 贴出下一条；贴完最后一条自动结束 */
    fun next(ctx: Context) {
        if (!active) return
        val t = texts[index++]
        PanelHost.deliver(ctx, t, MODE_PASTE)
        if (index >= texts.size) {
            Toast.makeText(ctx, tr("已经全部粘贴（%d 条）", "All %d items pasted", texts.size), Toast.LENGTH_SHORT).show()
            cancel()
        } else showPill(ctx)
    }

    fun cancel() {
        texts = emptyList()
        index = 0
        val v = pill
        pill = null
        label = null
        if (v != null) runCatching { host?.removeViewImmediate(v) }
        host = null
    }

    private fun status() = tr("依次粘贴 %d/%d", "Paste queue %d/%d", index + 1, texts.size)

    @SuppressLint("SetTextI18n")
    private fun showPill(ctx: Context) {
        label?.let { it.text = status(); return }
        val svc = ClipService.instance
        val (wctx, type) = when {
            svc != null -> svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(ctx) -> ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> {
                // 没有悬浮窗权限也没开增强模式：显示不了小条，只能逐条放进剪贴板
                Toast.makeText(ctx, tr("没有悬浮窗权限，无法显示“下一条”按钮", "No overlay permission, so the “Next” button can't be shown"), Toast.LENGTH_LONG).show()
                cancel()
                return
            }
        }
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()
        val bar = LinearLayout(wctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xF0202124.toInt()); cornerRadius = dp(22).toFloat() }
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(4), dp(4), dp(4))
        }
        val l = TextView(wctx).apply { text = status(); setTextColor(Color.WHITE); textSize = 14f }
        bar.addView(l)
        fun btn(text: String, primary: Boolean, onClick: () -> Unit) = TextView(wctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(if (primary) 0xFF4C8DF6.toInt() else Color.TRANSPARENT); cornerRadius = dp(18).toFloat() }
            setOnClickListener { onClick() }
        }
        bar.addView(btn(tr("下一条", "Next"), true) { next(wctx) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        bar.addView(btn("✕", false) { cancel() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            // 不能抢焦点：点“下一条”时，焦点要还留在要粘贴的那个输入框里
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        val wm = wctx.getSystemService(WindowManager::class.java)
        runCatching { wm.addView(bar, lp) }.onSuccess {
            pill = bar
            label = l
            host = wm
        }.onFailure { cancel() }
    }
}
