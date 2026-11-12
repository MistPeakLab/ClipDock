package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 像 Word 一样在一段文字里自由选择：
 * - 鼠标：单击放光标、拖动选择、Shift+单击扩展；双击选词、三击选段；双击后拖动按词扩展；Ctrl+单击加选一句、Ctrl+拖动加选一段。
 * - 键盘：←→ 字、↑↓ 行、Home/End 行首行尾、Ctrl+←→ 词、Ctrl+Home/End 全文首尾，加 Shift 扩选。
 * - 触屏：点按放光标、双击选词、长按选词，两端出现手柄可拖动调整，拖到边缘自动滚动。
 * 悬浮面板不获取焦点，系统自带的文字选择用不了，所以自己画选区、光标和手柄。
 */
@SuppressLint("ViewConstructor")
class PiecePicker(
ctx: Context,
val full: String,
private val cAccent: Int,
private val cCard: Int,
private val mods: () -> Int,
private val onChange: () -> Unit,
) : TextView(ctx) {

companion object {
private val ENTITY = Regex(
"https?://[^\\s，。、；！？）)】》”]+|www\\.[^\\s，。、；！？）)】》”]+" +
"|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+" +
"|\\+?\\d[\\d\\- ]{5,}\\d" +
"|\\d+(?:[.,:/\\-]\\d+)*"
)
private const val SENT_END = "。！？!?；;…"
private const val CLOSERS = "”’」』）)】》\"'"
}

private val d = resources.displayMetrics.density
private val len = full.length

// 已确定的其他选段（Ctrl+拖动加选）+ 当前选段 [anchor, caret)
private val extra = mutableListOf<IntRange>()
private var anchor = 0
private var caret = 0
private var wantX = -1f // ↑↓ 移动时保持的横向位置

// 选区卡片：实底 + 阴影、强调色浅底、描边
private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = cCard
setShadowLayer(7 * d, 0f, 2 * d, (cAccent and 0x00FFFFFF) or 0x55000000)
}
private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (cAccent and 0x00FFFFFF) or 0x24000000 }
private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = (cAccent and 0x00FFFFFF) or 0x99000000.toInt()
}
private val tmp = Path()
private val caretPaint = Paint().apply { color = cAccent; strokeWidth = 2 * d }
private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccent }
private val path = Path()
private var caretOn = true
private var touchUi = false // 最近一次是触屏操作：显示手柄

private val blink = object : Runnable {
override fun run() { caretOn = !caretOn; invalidate(); postDelayed(this, 530) }
}

init {
text = full
textSize = 14.5f
setLineSpacing(0f, 1.3f)
isFocusable = false
}

override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(blink, 530) }
override fun onDetachedFromWindow() { removeCallbacks(blink); removeCallbacks(autoScroll); super.onDetachedFromWindow() }

// ---------------- 选区 ----------------

private fun cur() = minOf(anchor, caret) until maxOf(anchor, caret)

private fun ranges(): List<IntRange> = (extra + listOf(cur())).filter { !it.isEmpty() }.sortedBy { it.first }.let(::merge)

private fun merge(rs: List<IntRange>): List<IntRange> {
val out = mutableListOf<IntRange>()
for (r in rs) {
val last = out.lastOrNull()
if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
}
return out
}

fun hasPick() = ranges().isNotEmpty()

fun selectedChars() = ranges().sumOf { r -> r.count { !full[it].isWhitespace() } }

/** 选中的各段按原文顺序拼接：中间隔着换行就换行，隔着空白就空格，否则直接相连 */
fun result(): String {
val rs = ranges()
val sb = StringBuilder()
rs.forEachIndexed { k, r ->
if (k > 0) {
val gap = full.substring(rs[k - 1].last + 1, r.first)
sb.append(when { '\n' in gap -> "\n"; gap.isNotEmpty() && gap.isBlank() -> " "; else -> "" })
}
sb.append(full.substring(r))
}
return sb.toString().trim()
}

private fun changed(scroll: Boolean = true) {
caretOn = true
removeCallbacks(blink); postDelayed(blink, 530)
invalidate(); onChange()
if (scroll) scrollToCaret()
}

fun selectAll() { extra.clear(); anchor = 0; caret = len; changed(false) }

fun clear() { extra.clear(); anchor = caret; changed(false) }

private fun setSel(a: Int, b: Int) { anchor = a.coerceIn(0, len); caret = b.coerceIn(0, len) }

// ---------------- 单位：词 / 句 / 段 ----------------

private fun wordAt(off: Int): IntRange {
if (len == 0) return 0 until 0
val o = off.coerceIn(0, len - 1)
ENTITY.findAll(full).firstOrNull { o in it.range }?.let { return it.range.first until it.range.last + 1 }
val bi = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.CHINA)
bi.setText(full)
var e = bi.following(o)
if (e == android.icu.text.BreakIterator.DONE) e = len
var s = bi.previous()
if (s == android.icu.text.BreakIterator.DONE) s = 0
return s until e
}

private fun paraAt(off: Int): IntRange {
val o = off.coerceIn(0, len)
var s = o; while (s > 0 && full[s - 1] != '\n') s--
var e = o; while (e < len && full[e] != '\n') e++
return s until e
}

private fun sentenceAt(off: Int): IntRange {
val p = paraAt(off)
var s = p.first
var i = p.first
while (i < p.last + 1) {
val c = full[i]
if (c in SENT_END || (c == '.' && (i + 1 == len || full[i + 1].isWhitespace()))) {
var e = i + 1
while (e < len && (full[e] in CLOSERS || full[e] in SENT_END)) e++
if (off < e) return trim(s until e)
s = e; i = e
} else i++
}
return trim(s until p.last + 1)
}

private fun trim(r: IntRange): IntRange {
var a = r.first; var b = r.last + 1
while (a < b && full[a].isWhitespace()) a++
while (b > a && full[b - 1].isWhitespace()) b--
return a until b
}

private fun nextWord(from: Int, dir: Int): Int {
val bi = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.CHINA)
bi.setText(full)
var p = from
while (true) {
val n = if (dir > 0) bi.following(p) else bi.preceding(p)
if (n == android.icu.text.BreakIterator.DONE) return if (dir > 0) len else 0
val seg = if (dir > 0) full.substring(p, n) else full.substring(n, p)
p = n
if (seg.any { it.isLetterOrDigit() }) return p
}
}

// ---------------- 键盘 ----------------

/** 处理光标移动键；返回 true 表示已处理 */
fun handleKey(e: KeyEvent): Boolean {
val shift = e.isShiftPressed
val ctrl = e.isCtrlPressed
val l = layout ?: return false
val target = when (e.keyCode) {
KeyEvent.KEYCODE_DPAD_LEFT -> if (!shift && hasPick() && !ctrl) cur().first else if (ctrl) nextWord(caret, -1) else caret - 1
KeyEvent.KEYCODE_DPAD_RIGHT -> if (!shift && hasPick() && !ctrl) cur().last + 1 else if (ctrl) nextWord(caret, 1) else caret + 1
KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
val line = l.getLineForOffset(caret)
if (wantX < 0) wantX = l.getPrimaryHorizontal(caret)
val step = when (e.keyCode) {
KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1
else -> { val vis = ((parent as? View)?.height ?: height) / maxOf(1, l.getLineBottom(0) - l.getLineTop(0)); if (e.keyCode == KeyEvent.KEYCODE_PAGE_UP) -vis else vis }
}
val nl = line + step
when {
nl < 0 -> 0
nl >= l.lineCount -> len
else -> l.getOffsetForHorizontal(nl, wantX)
}
}
KeyEvent.KEYCODE_MOVE_HOME -> if (ctrl) 0 else l.getLineStart(l.getLineForOffset(caret))
KeyEvent.KEYCODE_MOVE_END -> if (ctrl) len else {
val ln = l.getLineForOffset(caret)
var end = l.getLineEnd(ln)
if (end > l.getLineStart(ln) && end <= len && end > 0 && full[end - 1] == '\n') end--
end
}
else -> return false
}
if (e.keyCode != KeyEvent.KEYCODE_DPAD_UP && e.keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
e.keyCode != KeyEvent.KEYCODE_PAGE_UP && e.keyCode != KeyEvent.KEYCODE_PAGE_DOWN) wantX = -1f
touchUi = false
if (!shift) extra.clear()
caret = target.coerceIn(0, len)
if (!shift) anchor = caret
changed()
return true
}

// ---------------- 绘制 ----------------

/** 一段选区的卡片外形：每行一个圆角矩形，相邻行互相重叠后合并成一个整体 */
private fun cardPath(r: IntRange) {
val l = layout
path.reset()
val fm = paint.fontMetrics
val padX = 4 * d; val padY = 3.5f * d; val rad = 7 * d
val first = l.getLineForOffset(r.first)
val last = l.getLineForOffset(r.last + 1).let { if (it > first && l.getLineStart(it) == r.last + 1) it - 1 else it }
var prevBottom = Float.NaN
for (line in first..last) {
val ls = l.getLineStart(line); val le = l.getLineEnd(line)
val s = maxOf(r.first, ls)
var e = minOf(r.last + 1, le)
while (e > s && full[e - 1] == '\n') e--
val x1 = l.getPrimaryHorizontal(s)
var x2 = if (e >= le) l.getLineRight(line) else l.getPrimaryHorizontal(e)
if (e <= s) x2 = x1 + 4 * d
val base = l.getLineBaseline(line).toFloat()
var top = base + fm.ascent - padY
val bottom = base + fm.descent + padY
if (!prevBottom.isNaN() && top > prevBottom) top = prevBottom // 与上一行接上
tmp.reset()
tmp.addRoundRect(minOf(x1, x2) - padX, top, maxOf(x1, x2) + padX, bottom, rad, rad, Path.Direction.CW)
if (line == first) path.set(tmp) else path.op(tmp, Path.Op.UNION)
prevBottom = bottom
}
}

override fun onDraw(canvas: Canvas) {
val l = layout
if (l != null) {
canvas.save()
canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
for (r in ranges()) {
cardPath(r)
canvas.drawPath(path, cardPaint)
canvas.drawPath(path, tintPaint)
canvas.drawPath(path, strokePaint)
}
canvas.restore()
}
super.onDraw(canvas)
if (l == null) return
val ox = totalPaddingLeft.toFloat(); val oy = totalPaddingTop.toFloat()
if (!hasPick()) {
if (caretOn) {
val line = l.getLineForOffset(caret)
val x = ox + l.getPrimaryHorizontal(caret)
canvas.drawLine(x, oy + l.getLineTop(line) + 2 * d, x, oy + l.getLineBottom(line) - 2 * d, caretPaint)
}
} else if (touchUi) {
val r = cur().takeIf { !it.isEmpty() } ?: return
handlePos(r.first).let { drawHandle(canvas, it[0], it[1], left = true) }
handlePos(r.last + 1).let { drawHandle(canvas, it[0], it[1], left = false) }
}
}

private fun handlePos(off: Int): FloatArray {
val l = layout
val line = l.getLineForOffset(off)
return floatArrayOf(totalPaddingLeft + l.getPrimaryHorizontal(off), totalPaddingTop + l.getLineBottom(line).toFloat())
}

private fun drawHandle(c: Canvas, x: Float, y: Float, left: Boolean) {
val r = 9 * d
// 水滴形：圆 + 指向文字的尖角
val cx = if (left) x - r else x + r
val cy = y + r
c.drawCircle(cx, cy, r, handlePaint)
c.drawRect(if (left) cx else x, y, if (left) x else cx, cy, handlePaint)
}

// ---------------- 触摸 / 鼠标 ----------------

private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
private val dblTime = ViewConfiguration.getDoubleTapTimeout().toLong()
private var downX = 0f
private var downY = 0f
private var lastX = 0f
private var lastY = 0f
private var clicks = 0
private var lastClickAt = 0L
private var lastClickX = 0f
private var lastClickY = 0f
private var dragging = false
private var gran = 0 // 拖动粒度：0 字 1 词 2 段
private var origin = 0 until 0 // 双击/三击时的初始单位
private var dragHandle = 0 // 触屏拖手柄：-1 起点 1 终点
private var touchArmed = false
private var mouse = false
private var ctrlClick = false // Ctrl+单击（没拖动）= 加选一句

private val longPress = Runnable {
touchArmed = true
parent?.requestDisallowInterceptTouchEvent(true)
performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
val w = wordAt(offsetAt(downX, downY))
extra.clear(); setSel(w.first, w.last + 1); origin = w; gran = 1; dragging = true
touchUi = true
changed(false)
}

private val autoScroll = object : Runnable {
override fun run() {
if (!dragging && dragHandle == 0) return
val sv = parent as? ScrollView ?: return
val edge = 28 * d
val yIn = lastY - sv.scrollY
val dy = when { yIn < edge -> -(12 * d).toInt(); yIn > sv.height - edge -> (12 * d).toInt(); else -> 0 }
if (dy != 0) {
sv.scrollBy(0, dy)
lastY += dy
dragMove(lastX, lastY)
}
postDelayed(this, 16)
}
}

private fun offsetAt(x: Float, y: Float): Int {
val l = layout ?: return 0
val line = l.getLineForVertical((y - totalPaddingTop).toInt().coerceAtLeast(0))
return l.getOffsetForHorizontal(line, x - totalPaddingLeft).coerceIn(0, len)
}

private fun hitHandle(x: Float, y: Float): Int {
if (!touchUi || !hasPick()) return 0
val r = cur(); if (r.isEmpty()) return 0
val reach = 22 * d
val a = handlePos(r.first); val b = handlePos(r.last + 1)
if (hypot(x - (a[0] - 9 * d), y - (a[1] + 9 * d)) < reach) return -1
if (hypot(x - (b[0] + 9 * d), y - (b[1] + 9 * d)) < reach) return 1
return 0
}

@SuppressLint("ClickableViewAccessibility")
override fun onTouchEvent(ev: MotionEvent): Boolean {
when (ev.actionMasked) {
MotionEvent.ACTION_DOWN -> {
mouse = ev.isFromSource(InputDevice.SOURCE_MOUSE)
downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
dragging = false; touchArmed = false; wantX = -1f; ctrlClick = false
val meta = ev.metaState or mods()
val ctrl = meta and KeyEvent.META_CTRL_ON != 0
val shift = meta and KeyEvent.META_SHIFT_ON != 0
if (!mouse) {
dragHandle = hitHandle(ev.x, ev.y)
if (dragHandle != 0) {
parent?.requestDisallowInterceptTouchEvent(true)
// 让被拖的一端成为 caret
val r = cur()
if (dragHandle < 0) { anchor = r.last + 1; caret = r.first } else { anchor = r.first; caret = r.last + 1 }
post(autoScroll)
return true
}
}
val now = SystemClock.uptimeMillis()
clicks = if (now - lastClickAt < dblTime && hypot(ev.x - lastClickX, ev.y - lastClickY) < slop * 2) clicks % 3 + 1 else 1
lastClickAt = now; lastClickX = ev.x; lastClickY = ev.y
val off = offsetAt(ev.x, ev.y)
if (mouse) {
parent?.requestDisallowInterceptTouchEvent(true)
touchUi = false
when {
shift -> { caret = off; gran = 0 }
clicks == 2 -> { if (!ctrl) extra.clear() else extra += cur(); val w = wordAt(off); setSel(w.first, w.last + 1); origin = w; gran = 1 }
clicks == 3 -> { extra.clear(); val p = paraAt(off); setSel(p.first, p.last + 1); origin = p; gran = 2 }
ctrl -> { extra += cur(); setSel(off, off); gran = 0; ctrlClick = true }
else -> { extra.clear(); setSel(off, off); gran = 0 }
}
dragging = true
post(autoScroll)
changed(false)
} else {
if (clicks == 2) {
removeCallbacks(longPress)
val w = wordAt(off); extra.clear(); setSel(w.first, w.last + 1); touchUi = true
changed(false)
} else postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
}
}
MotionEvent.ACTION_MOVE -> {
lastX = ev.x; lastY = ev.y
if (dragHandle != 0) { caret = offsetAt(ev.x, ev.y - 14 * d); changed(false); return true }
val far = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
if (!mouse && !touchArmed) { if (far) removeCallbacks(longPress); return true }
if (dragging && (far || mouse)) dragMove(ev.x, ev.y)
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
removeCallbacks(longPress)
val wasTap = !mouse && !touchArmed && dragHandle == 0 && clicks == 1 &&
abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop && ev.actionMasked == MotionEvent.ACTION_UP
if (wasTap) { extra.clear(); val o = offsetAt(ev.x, ev.y); setSel(o, o); touchUi = false; changed(false) }
if (mouse && ctrlClick && abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop) {
val sn = sentenceAt(offsetAt(downX, downY)); setSel(sn.first, sn.last + 1); changed(false)
}
dragging = false; touchArmed = false; dragHandle = 0
removeCallbacks(autoScroll)
}
}
return true
}

private fun dragMove(x: Float, y: Float) {
val off = offsetAt(x, y)
when (gran) {
1, 2 -> {
val u = if (gran == 1) wordAt(off) else paraAt(off)
if (off >= origin.first) { anchor = origin.first; caret = maxOf(u.last + 1, origin.last + 1) }
else { anchor = origin.last + 1; caret = u.first }
}
else -> caret = off
}
changed(false)
}

// ---------------- 滚动 / 飞行卡片 ----------------

private fun scrollToCaret() {
val sv = parent as? ScrollView ?: return
post {
val l = layout ?: return@post
val line = l.getLineForOffset(caret)
val top = l.getLineTop(line) + totalPaddingTop
val bottom = l.getLineBottom(line) + totalPaddingTop
val pad = (16 * d).toInt()
if (top < sv.scrollY) sv.smoothScrollTo(0, top - pad)
else if (bottom > sv.scrollY + sv.height) sv.smoothScrollTo(0, bottom - sv.height + pad)
}
}

/** 选中文字的第一个字在屏幕上的位置（飞行卡片的起点） */
fun firstPieceScreenPos(): IntArray {
val loc = IntArray(2)
getLocationOnScreen(loc)
val l = layout ?: return loc
val off = ranges().firstOrNull()?.first ?: caret
loc[0] += totalPaddingLeft + l.getPrimaryHorizontal(off).toInt()
loc[1] += totalPaddingTop + l.getLineTop(l.getLineForOffset(off))
return loc
}

/** 把选中的文字画成一张小卡片（飞行动画用） */
fun chip(text: String, card: Int, border: Int, textColor: Int, maxW: Int): Bitmap? = runCatching {
val tv = TextView(context).apply {
this.text = text.trim().replace(Regex("\\s*\\n\\s*"), " ⏎ ")
textSize = 13.5f; setTextColor(textColor); maxLines = 2
ellipsize = android.text.TextUtils.TruncateAt.END
setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
background = android.graphics.drawable.GradientDrawable().apply {
setColor(card); cornerRadius = 10 * d; setStroke((1.5f * d).toInt(), border)
}
}
tv.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)
Bitmap.createBitmap(tv.measuredWidth, tv.measuredHeight, Bitmap.Config.ARGB_8888).also { tv.draw(Canvas(it)) }
}.getOrNull()
}