package com.mistpeaklab.clipdock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/** 面板里被选中卡片的截图和它在屏幕上的位置 */
class FlyCard(val bmp: Bitmap, val x: Int, val y: Int) {
companion object {
/** 截取一个已显示的视图；不在屏幕上或太小时返回 null */
fun of(v: View): FlyCard? {
if (!v.isShown || v.width <= 0 || v.height <= 0) return null
val r = android.graphics.Rect()
if (!v.getLocalVisibleRect(r) || r.height() < v.height / 2) return null
val bmp = runCatching { Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
v.draw(Canvas(bmp))
val loc = IntArray(2)
v.getLocationOnScreen(loc)
return FlyCard(bmp, loc[0], loc[1])
}
}
}

/**
 * 粘贴动画：卡片（多张时先叠成一摞）沿弧线飞向输入框光标并缩小消失，
 * 落点泛起一圈涟漪。落地那一刻执行真正的粘贴（onLand）。
 * 用一个不可触摸、不可获焦的全屏无障碍悬浮层绘制，不影响任何操作。
 */
object PasteFly {
private const val ACCENT = 0xFF4C8DF6.toInt()
private val ROT = floatArrayOf(0f, -5f, 4f, -3f, 6f)

fun run(ctx: Context, wm: WindowManager, cards: List<FlyCard>, target: RectF, onLand: () -> Unit) {
val dens = ctx.resources.displayMetrics.density
fun dp(v: Float) = v * dens
val root = FrameLayout(ctx)
val lp = WindowManager.LayoutParams(
WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
PixelFormat.TRANSLUCENT,
).apply {
gravity = Gravity.TOP or Gravity.START
layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
windowAnimations = 0
}
var landed = false
fun land() { if (!landed) { landed = true; onLand() } }
fun cleanup() {
runCatching { wm.removeViewImmediate(root) }
cards.forEach { it.bmp.recycle() }
}

val radius = dp(10f)
// 第一张放在最上面：倒序添加
val views = cards.map { c ->
ImageView(ctx).apply {
setImageBitmap(c.bmp)
outlineProvider = object : ViewOutlineProvider() {
override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, radius)
}
clipToOutline = true
alpha = 0f
}
}
views.asReversed().forEachIndexed { i, v ->
val c = cards[views.size - 1 - i]
root.addView(v, FrameLayout.LayoutParams(c.bmp.width, c.bmp.height))
}
val ripple = View(ctx).apply {
background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
alpha = 0f
}
val rs = dp(28f).toInt()
root.addView(ripple, FrameLayout.LayoutParams(rs, rs))

if (runCatching { wm.addView(root, lp) }.isFailure) {
cards.forEach { it.bmp.recycle() }
onLand(); return
}

root.post {
val o = IntArray(2)
root.getLocationOnScreen(o)
val n = cards.size
// 摞起来的中心：所有卡片中心的平均
val cx0 = cards.map { it.x + it.bmp.width / 2f }.average().toFloat() - o[0]
val cy0 = cards.map { it.y + it.bmp.height / 2f }.average().toFloat() - o[1]
val tx = target.centerX() - o[0]
val ty = target.centerY() - o[1]
val caretH = target.height().coerceAtLeast(dp(14f))

val stackMs = if (n > 1) 220f else 0f
val flyMs = 380f
val stagger = 28f
val rippleMs = 300f
val landAt = stackMs + flyMs
val total = landAt + stagger * (n - 1) + rippleMs

val stackEase = DecelerateInterpolator(2f)
val flyEase = PathInterpolator(0.5f, 0f, 0.3f, 1f)
val rippleEase = DecelerateInterpolator(1.6f)
val lift = dp(12f)

views.forEach { it.alpha = 1f }
ripple.translationX = tx - rs / 2f
ripple.translationY = ty - rs / 2f

ValueAnimator.ofFloat(0f, total).apply {
duration = total.toLong()
interpolator = LinearInterpolator()
addUpdateListener { a ->
val t = a.animatedValue as Float
views.forEachIndexed { i, v ->
val c = cards[i]
val w = c.bmp.width.toFloat(); val h = c.bmp.height.toFloat()
val sx = c.x - o[0] + w / 2; val sy = c.y - o[1] + h / 2
// 摞起来的位置：略微错开、带一点旋转
val px = cx0 + dp(3f) * i
val py = cy0 + dp(3f) * i
val rot = ROT[i % ROT.size]
val k = if (stackMs == 0f) 1f else stackEase.getInterpolation((t / stackMs).coerceIn(0f, 1f))
var x = sx + (px - sx) * k
var y = sy + (py - sy) * k
var r = rot * k
var s = 1f
var al = 1f
val ft = ((t - stackMs - stagger * i) / flyMs).coerceIn(0f, 1f)
if (ft > 0f) {
val f = flyEase.getInterpolation(ft)
// 二次贝塞尔：向上拱起的弧线
val mx = (px + tx) / 2
val my = minOf(py, ty) - dp(90f)
val u = 1 - f
x = u * u * px + 2 * u * f * mx + f * f * tx
y = u * u * py + 2 * u * f * my + f * f * ty
r = rot * u
val end = (caretH * 1.3f / h).coerceIn(0.05f, 0.45f)
s = 1f + (end - 1f) * f
al = if (ft < 0.6f) 1f else 1f - (ft - 0.6f) / 0.4f
}
v.translationX = x - w / 2
v.translationY = y - h / 2
v.rotation = r
v.scaleX = s; v.scaleY = s
v.alpha = al
v.elevation = lift * (if (ft > 0f) 1f - ft else k)
}
if (t >= landAt) {
land()
val rt = ((t - landAt) / rippleMs).coerceIn(0f, 1f)
val e = rippleEase.getInterpolation(rt)
val sc = 0.3f + 1.9f * e
ripple.scaleX = sc; ripple.scaleY = sc
ripple.alpha = 0.45f * (1f - rt)
}
}
addListener(object : AnimatorListenerAdapter() {
override fun onAnimationEnd(animation: Animator) { land(); cleanup() }
override fun onAnimationCancel(animation: Animator) { land() }
})
start()
}
}
}

}
