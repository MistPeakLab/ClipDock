package com.mistpeaklab.clipdock.floatball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 第二圈里的一条（最近 / 收藏的某一条内容） */
class SubEntry(val label: String, val action: () -> Unit)

/**
 * 环形控制台的一项。有 [children] 的项（最近、收藏）滑过去时向外再展开一圈具体内容；
 * 没有的直接执行 [action]，执行完卡卡做 [reaction]（null = 不做，由动作本身触发反应）。
 */
class RadialItem(
    val icon: Int,
    val label: String,
    val reaction: Mascot? = null,
    val children: (() -> List<SubEntry>)? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 长按悬浮球出现的环形控制台。卡卡从屏幕边走到控制台中间（压在暗色背景上、完整显示），
 * 选项朝屏幕内侧排成半圈围着她。
 *
 * 触摸不跨窗口：这次按下的后续事件系统会一直发给悬浮球自己的窗口（哪怕手指已经移出去），
 * 所以控制台窗口一开始是“不可触摸”的，只负责画；悬浮球把手指位置转告 [hover] / [release]。
 * 在卡卡身上松手（或松在某个“最近 / 收藏”上）时菜单停留，这时才把窗口改成可触摸，等用户点选。
 *
 * @param ballX 悬浮球窗口左上角（屏幕坐标）
 * @param onClosed 关闭后调用：选中项要卡卡做的反应（取消时为 [Mascot.LAND]），以及选中后要执行的动作
 */
class RadialMenu(
    private val ctx: Context,
    private val wm: WindowManager,
    private val type: Int,
    private val ballX: Float,
    private val ballY: Float,
    private val ballSize: Int,
    private val dockLeft: Boolean,
    private val mascot: Mascot?,
    private val items: List<RadialItem>,
    private val onClosed: (reaction: Mascot?, then: (() -> Unit)?) -> Unit,
) {
    private val dens = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    /** 控制台里的卡卡和悬浮球一样大（圆形图标样式时至少 140dp），明显大过按钮 */
    private val big = maxOf(ballSize.toFloat(), dp(140f)).toInt()
    private val radius = dp(96f) + big * 0.22f
    private val bubble = dp(26f)
    private val spread = Math.toRadians(if (items.size <= 3) 110.0 else 150.0)
    private val inward = if (dockLeft) 0.0 else Math.PI
    private val kakaHit = big * 0.38f

    private var hovered = -1
    private var subOf = -1
    private var subs: List<SubEntry> = emptyList()
    private var subHover = -1
    private var progress = 0f
    private var subProgress = 0f
    private var sticky = false
    private var closed = false

    /** 菜单中心 = 卡卡站的位置（屏幕坐标） */
    private var mx = 0f
    private var my = 0f

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

    private val layer = object : View(ctx) {
        override fun onDraw(canvas: Canvas) = paintMenu(canvas)
    }

    /** 卡卡（圆形图标样式时为 null，中间画一个圆球） */
    private val kaka: SpriteView? = mascot?.let { SpriteView(ctx).apply { mirrored = !dockLeft } }
    private val ball: View? = if (mascot == null) BallIconView(ctx) else null

    private val root = object : FrameLayout(ctx) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!sticky) return false
            if (e.actionMasked == MotionEvent.ACTION_UP) tapAt(e.rawX, e.rawY)
            return true
        }
    }

    // 画笔
    private val dim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); color = 0xFF1F2328.toInt() }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1F2328.toInt() }
    private val icons = items.map { runCatching { ctx.getDrawable(it.icon)?.mutate() }.getOrNull() }

    /** 在屏幕坐标里，悬浮层自己的左上角（电脑截图时为 0） */
    private var ox = 0f
    private var oy = 0f

    fun show(): Boolean {
        val b = wm.currentWindowMetrics.bounds
        // 卡卡站的位置：离屏幕边一点点，完整露出来；上下贴边时往里挪，保证每一项都在屏幕内
        // 卡卡的画布左右有留白，贴边放就行
        mx = if (dockLeft) b.left + dp(6f) + big / 2f else b.right - dp(6f) - big / 2f
        val margin = bubble + dp(24f)
        my = (ballY + ballSize / 2f).coerceIn(b.top + radius * 0.97f + margin, b.bottom - radius * 0.97f - margin)

        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        (kaka ?: ball)?.let { root.addView(it, FrameLayout.LayoutParams(big, big)) }
        if (runCatching { wm.addView(root, lp) }.isFailure) return false
        root.post {
            val o = IntArray(2)
            root.getLocationOnScreen(o)
            ox = o[0].toFloat(); oy = o[1].toFloat()
            placeKaka(0f)
        }
        placeKaka(0f)
        kaka?.play(Mascot.WAVE) { kaka.play(if (mascot == Mascot.SLEEP) Mascot.SLEEP else Mascot.IDLE) }
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; placeKaka(progress); layer.invalidate() }
            start()
        }
        return true
    }

    /** 卡卡从悬浮球的位置、原来的大小（t=0）走到菜单中心并放大（t=1） */
    private fun placeKaka(t: Float) {
        val v = kaka ?: ball ?: return
        val sx = ballX + ballSize / 2f; val sy = ballY + ballSize / 2f
        val cx = sx + (mx - sx) * t; val cy = sy + (my - sy) * t
        val s = (ballSize.toFloat() / big) + (1 - ballSize.toFloat() / big) * t
        v.pivotX = big / 2f; v.pivotY = big / 2f
        v.scaleX = s; v.scaleY = s
        v.translationX = cx - big / 2f - ox
        v.translationY = cy - big / 2f - oy
    }
    // ---------------- 位置 ----------------

    private fun angleOf(i: Int): Double {
        if (items.size == 1) return inward
        val step = spread / (items.size - 1)
        val a = -spread / 2 + step * i
        return if (dockLeft) inward + a else inward - a
    }

    private fun polar(a: Double, r: Float) = (mx + r * cos(a)).toFloat() to (my + r * sin(a)).toFloat()

    /**
     * 第二圈（最近 / 收藏的具体内容）：在内圈外侧竖着排一列，以父项的高度为中心，上下不超出屏幕。
     * 竖排比沿弧线排更好读，也不会在接近竖直的方向上挤成一团。
     */
    private fun chipRect(k: Int, t: Float = 1f): RectF {
        val b = wm.currentWindowMetrics.bounds
        val h = dp(36f)
        val gap = dp(8f)
        val n = subs.size
        val total = n * h + (n - 1) * gap
        val (_, py) = polar(angleOf(subOf), radius)
        val top = (py - total / 2).coerceIn(b.top + dp(16f), (b.bottom - dp(16f) - total).coerceAtLeast(b.top + dp(16f)))
        val y = top + k * (h + gap)
        val w = chipWidth(k)
        val edge = radius + bubble + dp(18f) + dp(24f) * (1 - t)
        return if (dockLeft) {
            val l = (mx + edge).coerceAtMost(b.right - dp(12f) - w)
            RectF(l, y, l + w, y + h)
        } else {
            val r = (mx - edge).coerceAtLeast(b.left + dp(12f) + w)
            RectF(r - w, y, r, y + h)
        }
    }

    /** 第二列最宽多少：最多 170dp；手机竖屏上卡卡变大后，按内圈外侧剩下的宽度收窄 */
    private val chipMax: Float get() {
        val b = wm.currentWindowMetrics.bounds
        val edge = radius + bubble + dp(18f)
        val room = if (dockLeft) b.right - dp(12f) - (mx + edge) else (mx - edge) - (b.left + dp(12f))
        return room.coerceIn(dp(96f), dp(170f))
    }

    /** 自己截断（不依赖 TextUtils.ellipsize）：只取第一行，放不下就加省略号 */
    private fun chipLabel(k: Int): String {
        val s = subs[k].label.replace('\n', ' ')
        val room = chipMax - dp(28f)
        if (chipText.measureText(s) <= room) return s
        val n = chipText.breakText(s, true, room - chipText.measureText("…"), null)
        return s.substring(0, n.coerceAtLeast(1)).trimEnd() + "…"
    }

    private fun chipWidth(k: Int) = chipText.measureText(chipLabel(k)) + dp(28f)
    // ---------------- 命中 ----------------

    private fun hitItem(rx: Float, ry: Float): Int {
        val dx = rx - mx; val dy = ry - my
        if (hypot(dx, dy) < kakaHit) return -1
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

    private fun hitChip(rx: Float, ry: Float): Int {
        if (subOf < 0) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        subs.indices.forEach { k ->
            val r = chipRect(k)
            val dx = maxOf(r.left - rx, 0f, rx - r.right)
            val dy = maxOf(r.top - ry, 0f, ry - r.bottom)
            val d = hypot(dx, dy)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (bestD <= dp(14f)) best else -1
    }

    /** 手指在内圈范围里（还没伸到第二圈） */
    private fun inInner(rx: Float, ry: Float) = hypot(rx - mx, ry - my) < radius + bubble + dp(18f)

    // ---------------- 交互 ----------------

    fun hover(rx: Float, ry: Float) {
        if (closed) return
        val chip = hitChip(rx, ry)
        if (chip >= 0 || (subOf >= 0 && !inInner(rx, ry))) {
            // 在第二圈上：内圈保持父项高亮
            if (chip != subHover) {
                subHover = chip
                if (chip >= 0) layer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                layer.invalidate()
            }
            return
        }
        subHover = -1
        val i = hitItem(rx, ry)
        if (i != hovered) {
            hovered = i
            if (i >= 0) {
                layer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lean(true)
                if (items[i].children != null) openSub(i) else closeSub()
            } else { lean(false); closeSub() }
            layer.invalidate()
        }
    }

    /** 手指抬起：选中就执行；在卡卡身上或“最近 / 收藏”上松手则停留，等用户点；滑出范围取消 */
    fun release(rx: Float, ry: Float) {
        if (closed) return
        val chip = hitChip(rx, ry)
        val i = if (chip >= 0) -1 else hitItem(rx, ry)
        when {
            chip >= 0 -> pickSub(chip)
            i >= 0 && items[i].children == null -> pick(i)
            i >= 0 -> { openSub(i); stay() }
            hypot(rx - mx, ry - my) < kakaHit -> stay()
            else -> close(Mascot.LAND, null)
        }
    }

    private fun tapAt(rx: Float, ry: Float) {
        val chip = hitChip(rx, ry)
        if (chip >= 0) { pickSub(chip); return }
        val i = hitItem(rx, ry)
        when {
            i >= 0 && items[i].children != null -> { hovered = i; openSub(i); layer.invalidate() }
            i >= 0 -> pick(i)
            else -> close(Mascot.LAND, null)
        }
    }

    private fun stay() {
        sticky = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(root, lp) }
        layer.invalidate()
    }

    private fun openSub(i: Int) {
        if (subOf == i) return
        subOf = i
        subs = runCatching { items[i].children?.invoke() }.getOrNull().orEmpty()
        subHover = -1
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { subProgress = it.animatedValue as Float; layer.invalidate() }
            start()
        }
    }

    private fun closeSub() {
        subOf = -1; subs = emptyList(); subHover = -1; subProgress = 0f
    }

    /** 卡卡朝选项那边歪一下、轻轻一跳；没选中时站直 */
    private fun lean(on: Boolean) {
        val v = kaka ?: ball ?: return
        val base = my - big / 2f - oy
        v.animate().cancel()
        if (on) {
            v.translationY = base - dp(7f)
            v.animate().rotation(if (dockLeft) 8f else -8f).translationY(base)
                .setDuration(220).setInterpolator(OvershootInterpolator(2.5f)).start()
        } else v.animate().rotation(0f).translationY(base).setDuration(160).start()
    }

    private fun pick(i: Int) = close(items[i].reaction, items[i].action)

    private fun pickSub(k: Int) = close(null, subs[k].action)

    /** 收起：卡卡走回屏幕边，然后移除窗口，再执行选中的动作 */
    fun close(reaction: Mascot?, then: (() -> Unit)?) {
        if (closed) return
        closed = true
        (kaka ?: ball)?.animate()?.cancel()
        (kaka ?: ball)?.rotation = 0f
        val from = progress
        ValueAnimator.ofFloat(from, 0f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; subProgress = minOf(subProgress, progress); placeKaka(progress); layer.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { wm.removeViewImmediate(root) }
                    onClosed(reaction, then)
                }
            })
            start()
        }
    }

    /** 立刻移除（悬浮球被销毁时） */
    fun dismissNow() {
        if (closed) return
        closed = true
        runCatching { wm.removeViewImmediate(root) }
    }

    // ---------------- 绘制 ----------------

    private fun paintMenu(c: Canvas) {
        c.save()
        c.translate(-ox, -oy)
        val p = progress
        dim.color = Color.argb((90 * p).toInt(), 0, 0, 0)
        c.drawPaint(dim)
        items.forEachIndexed { i, item ->
            val (x, y) = polar(angleOf(i), radius * (0.35f + 0.65f * p))
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
            // 第二圈展开时，内圈的文字都让开（第二圈就排在文字的位置上）
            if (subOf >= 0) return@forEachIndexed
            val tw = text.measureText(item.label)
            val gap = r + dp(8f) + tw / 2 + dp(7f)
            val lx = if (dockLeft) x + gap else x - gap
            val box = RectF(lx - tw / 2 - dp(7f), y - dp(11f), lx + tw / 2 + dp(7f), y + dp(9f))
            pill.alpha = (204 * p).toInt()
            c.drawRoundRect(box, dp(10f), dp(10f), pill)
            text.alpha = (255 * p).toInt()
            c.drawText(item.label, lx, y + dp(4f), text)
        }
        if (subOf >= 0) paintSubs(c)
        c.restore()
    }

    private fun paintSubs(c: Canvas) {
        val sp = subProgress * progress
        if (subs.isEmpty()) {
            val (_, py) = polar(angleOf(subOf), radius)
            val label = com.mistpeaklab.clipdock.tr("还没有内容", "Nothing yet")
            val w = chipText.measureText(label) + dp(28f)
            val edge = radius + bubble + dp(18f)
            val box = if (dockLeft) RectF(mx + edge, py - dp(18f), mx + edge + w, py + dp(18f)) else RectF(mx - edge - w, py - dp(18f), mx - edge, py + dp(18f))
            pill.alpha = (204 * sp).toInt()
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, pill)
            chipText.color = Color.WHITE
            chipText.alpha = (255 * sp).toInt()
            c.drawText(label, box.left + dp(14f), box.centerY() + dp(4.5f), chipText)
            return
        }
        subs.indices.forEach { k ->
            val box = chipRect(k, subProgress)
            val on = k == subHover
            fill.color = if (on) ACCENT else Color.WHITE
            fill.alpha = (255 * sp).toInt()
            fill.setShadowLayer(dp(5f), 0f, dp(2f), 0x33000000)
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill)
            fill.clearShadowLayer()
            chipText.color = if (on) Color.WHITE else 0xFF1F2328.toInt()
            chipText.alpha = (255 * sp).toInt()
            c.drawText(chipLabel(k), box.left + dp(14f), box.centerY() + dp(4.5f), chipText)
        }
    }
    companion object {
        const val ACCENT = 0xFF4C8DF6.toInt()
    }
}

/** 圆形图标样式的球：品牌蓝圆底 + 白色剪贴板图标 */
internal class BallIconView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RadialMenu.ACCENT }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * ctx.resources.displayMetrics.density }
    private val glyph = ctx.getDrawable(com.mistpeaklab.clipdock.R.drawable.ic_gen_copy)?.mutate()?.apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - edge.strokeWidth
        c.drawCircle(width / 2f, height / 2f, r, fill)
        c.drawCircle(width / 2f, height / 2f, r, edge)
        glyph?.let {
            val s = (r * 1.1f).toInt()
            it.setBounds(width / 2 - s / 2, height / 2 - s / 2, width / 2 + s / 2, height / 2 + s / 2)
            it.draw(c)
        }
    }
}
