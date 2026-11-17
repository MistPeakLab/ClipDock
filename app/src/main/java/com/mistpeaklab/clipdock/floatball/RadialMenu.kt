package com.mistpeaklab.clipdock.floatball

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 环形控制台里的一项 */
class RadialItem(val icon: Int, val label: String, val action: () -> Unit)

/**
 * 长按悬浮球出现的环形控制台：以球心为圆心，朝屏幕内侧展开半圈。
 *
 * 触摸不跨窗口：这次按下的后续事件系统会一直发给悬浮球自己的窗口（哪怕手指已经移出去），
 * 所以控制台窗口一开始是“不可触摸”的，只负责画；悬浮球把手指位置转告 [hover] / [release]。
 * 在中心松手（没选任何一项）时菜单停留，这时才把窗口改成可触摸，等用户再点一项。
 */
class RadialMenu(
    private val ctx: Context,
    private val wm: WindowManager,
    private val type: Int,
    /** 球心在屏幕上的位置 */
    private val cx: Float,
    private val cy: Float,
    /** 悬浮球停在左边：菜单朝右展开 */
    private val dockLeft: Boolean,
    private val items: List<RadialItem>,
    private val onClosed: () -> Unit,
) {
    private val dens = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    private val radius = dp(110f)
    private val bubble = dp(26f)
    private val deadZone = dp(38f)
    private val spread = Math.toRadians(if (items.size <= 3) 110.0 else 150.0)
    private val inward = if (dockLeft) 0.0 else Math.PI

    private var hovered = -1
    private var progress = 0f
    private var sticky = false
    private var closed = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        windowAnimations = 0
    }

    private val view = object : View(ctx) {
        override fun onDraw(canvas: Canvas) = paintMenu(canvas)
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!sticky) return false
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val i = hit(e.rawX, e.rawY)
                if (i >= 0) pick(i) else close()
            }
            return true
        }
    }

    // 画笔
    private val dim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1F2328.toInt() }
    private val icons = items.map { runCatching { ctx.getDrawable(it.icon)?.mutate() }.getOrNull() }

    /** 菜单中心：贴近屏幕上下边时往里挪，保证每一项都在屏幕内 */
    private var mx = cx
    private var my = cy

    fun show(): Boolean {
        val b = wm.currentWindowMetrics.bounds
        val margin = bubble + dp(28f)
        my = cy.coerceIn(b.top + radius * 0.75f + margin, b.bottom - radius * 0.75f - margin)
        if (runCatching { wm.addView(view, lp) }.isFailure) return false
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; view.invalidate() }
            start()
        }
        return true
    }

    private fun angleOf(i: Int): Double {
        if (items.size == 1) return inward
        val step = spread / (items.size - 1)
        // 自上而下排列：停左边时从右上到右下，停右边时从左上到左下
        val a = -spread / 2 + step * i
        return if (dockLeft) inward + a else inward - a
    }

    private fun posOf(i: Int, r: Float = radius): Pair<Float, Float> {
        val a = angleOf(i)
        return (mx + r * cos(a)).toFloat() to (my + r * sin(a)).toFloat()
    }

    /** 手指（屏幕坐标）下是哪一项：按方向判断，离中心太近不算 */
    private fun hit(rx: Float, ry: Float): Int {
        val dx = rx - mx; val dy = ry - my
        if (hypot(dx, dy) < deadZone) return -1
        val a = atan2(dy.toDouble(), dx.toDouble())
        val half = if (items.size == 1) Math.PI / 2 else spread / (items.size - 1) / 2 + Math.toRadians(8.0)
        var best = -1
        var bestD = Double.MAX_VALUE
        items.indices.forEach { i ->
            var d = abs(a - angleOf(i)) % (2 * Math.PI)
            if (d > Math.PI) d = 2 * Math.PI - d
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD <= half) best else -1
    }

    fun hover(rx: Float, ry: Float) {
        if (closed) return
        val i = hit(rx, ry)
        if (i != hovered) {
            hovered = i
            if (i >= 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            view.invalidate()
        }
    }

    /** 手指抬起：选中一项就执行；在中心松手则停留等待点选；滑出范围取消 */
    fun release(rx: Float, ry: Float) {
        if (closed) return
        val i = hit(rx, ry)
        when {
            i >= 0 -> pick(i)
            hypot(rx - mx, ry - my) < deadZone -> {
                sticky = true
                hovered = -1
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(view, lp) }
                view.invalidate()
            }
            else -> close()
        }
    }

    private fun pick(i: Int) {
        close()
        items[i].action()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { wm.removeViewImmediate(view) }
        onClosed()
    }

    private fun paintMenu(c: Canvas) {
        val o = IntArray(2)
        view.getLocationOnScreen(o)
        c.save()
        c.translate(-o[0].toFloat(), -o[1].toFloat())
        val p = progress
        dim.color = Color.argb((70 * p).toInt(), 0, 0, 0)
        c.drawPaint(dim)
        // 中心的小圈：提示“在这里松手 = 停留”
        ring.color = Color.argb((150 * p).toInt(), 255, 255, 255)
        c.drawCircle(cx, cy, deadZone * 0.8f * p, ring)
        items.forEachIndexed { i, item ->
            val (x, y) = posOf(i, radius * (0.4f + 0.6f * p))
            val on = i == hovered
            val r = bubble * (if (on) 1.18f else 1f) * (0.6f + 0.4f * p)
            fill.color = if (on) ACCENT else Color.WHITE
            fill.alpha = (255 * p).toInt()
            fill.setShadowLayer(dp(6f), 0f, dp(2f), 0x40000000)
            c.drawCircle(x, y, r, fill)
            fill.clearShadowLayer()
            icons[i]?.let { d ->
                val s = (r * 0.95f).toInt()
                d.setBounds((x - s / 2).toInt(), (y - s / 2).toInt(), (x + s / 2).toInt(), (y + s / 2).toInt())
                d.colorFilter = PorterDuffColorFilter(if (on) Color.WHITE else ACCENT, PorterDuff.Mode.SRC_IN)
                d.alpha = (255 * p).toInt()
                d.draw(c)
            }
            // 文字：放在气泡朝屏幕内侧的一边（停左边时在右侧），不会压到相邻的气泡
            val tw = text.measureText(item.label)
            val gap = r + dp(8f) + tw / 2 + dp(7f)
            val lx = if (dockLeft) x + gap else x - gap
            val ly = y
            val box = RectF(lx - tw / 2 - dp(7f), ly - dp(11f), lx + tw / 2 + dp(7f), ly + dp(9f))
            pill.alpha = (204 * p).toInt()
            c.drawRoundRect(box, dp(10f), dp(10f), pill)
            text.alpha = (255 * p).toInt()
            c.drawText(item.label, lx, ly + dp(4f), text)
        }
        c.restore()
    }

    companion object {
        const val ACCENT = 0xFF4C8DF6.toInt()
    }
}
