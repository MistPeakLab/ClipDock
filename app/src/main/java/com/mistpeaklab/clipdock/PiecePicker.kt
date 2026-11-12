package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 把一条长文本拆开挑选：按 句 / 行 / 词 切成片段，点选、加选、连选，或拖动选任意文字。
 * 选择用逐字标记保存，所以片段选择和自由拖选可以混用。
 * 自己处理触摸，不依赖系统的文字选择（悬浮面板不获取焦点，系统选择用不了）。
 */
@SuppressLint("ViewConstructor")
class PiecePicker(
ctx: Context,
val full: String,
unitInit: Int,
private val cAccent: Int,
private val mods: () -> Int,
private val onChange: () -> Unit,
private val onDoubleTap: () -> Unit,
) : TextView(ctx) {

companion object {
const val SENTENCE = 0
const val LINE = 1
const val WORD = 2
val UNIT_NAMES = listOf("句", "行", "词")

private val SENT_END = "。！？!?；;…\n"
private val CLOSERS = "”’」』）)】》\"'"
private val ENTITY = Regex(
"https?://[^\\s，。、；！？）)】》”]+|www\\.[^\\s，。、；！？）)】》”]+" +
"|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+" +
"|\\+?\\d[\\d\\- ]{5,}\\d" +
"|\\d+(?:[.,:/\\-]\\d+)*"
)

fun segments(t: String, unit: Int): List<IntRange> {
val raw = mutableListOf<IntRange>()
when (unit) {
LINE -> {
var s = 0
for (i in 0..t.length) if (i == t.length || t[i] == '\n') { raw += s until i; s = i + 1 }
}
SENTENCE -> {
var s = 0
var i = 0
while (i < t.length) {
val c = t[i]
val end = c in SENT_END || (c == '.' && (i + 1 == t.length || t[i + 1].isWhitespace()))
if (end) {
var e = i + 1
if (c != '\n') while (e < t.length && (t[e] in CLOSERS || t[e] in SENT_END && t[e] != '\n')) e++
raw += s until e
s = e; i = e
} else i++
}
if (s < t.length) raw += s until t.length
}
else -> {
val ents = ENTITY.findAll(t).map { it.range }.toList()
var pos = 0
fun words(a: Int, b: Int) {
if (a >= b) return
val bi = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.CHINA)
bi.setText(t.substring(a, b))
var st = bi.first()
var en = bi.next()
while (en != android.icu.text.BreakIterator.DONE) {
val piece = t.substring(a + st, a + en)
if (piece.any { it.isLetterOrDigit() }) raw += (a + st) until (a + en)
st = en; en = bi.next()
}
}
for (r in ents) { words(pos, r.first); raw += r; pos = r.last + 1 }
words(pos, t.length)
}
}
// 去掉首尾空白，丢弃空片段
return raw.mapNotNull { r ->
var a = r.first; var b = r.last + 1
while (a < b && t[a].isWhitespace()) a++
while (b > a && t[b - 1].isWhitespace()) b--
if (a < b) a until b else null
}
}
}

var unit = unitInit
private set
private var segs: List<IntRange> = segments(full, unit)
private val sel = BooleanArray(full.length)
var cursor = 0
private set
private var anchor = 0

private val selBg = (cAccent and 0x00FFFFFF) or 0x55000000
private val curBg = (cAccent and 0x00FFFFFF) or 0x22000000

init {
textSize = 14.5f
setLineSpacing(0f, 1.3f)
setTextIsSelectable(false)
isFocusable = false
render()
}

val segmentCount get() = segs.size

fun setUnit(u: Int) {
if (u == unit) return
val at = segs.getOrNull(cursor)?.first ?: 0
unit = u
segs = segments(full, u)
cursor = segIndexAt(at)
anchor = cursor
render(); onChange()
}

fun cycleUnit() = setUnit((unit + 1) % 3)

fun hasPick() = sel.any { it }

fun selectedChars() = full.indices.count { sel[it] && !full[it].isWhitespace() }

/** 要粘贴的文字：有选择用选择，否则用光标所在片段 */
fun result(): String = if (hasPick()) selectedText() else segs.getOrNull(cursor)?.let { full.substring(it) } ?: full

/** 选中的各段按原文顺序拼接：原文中间隔着换行就换行，隔着空格就空格，否则直接相连 */
private fun selectedText(): String {
val runs = mutableListOf<IntRange>()
var i = 0
while (i < full.length) {
if (sel[i]) { val s = i; while (i < full.length && sel[i]) i++; runs += s until i } else i++
}
val sb = StringBuilder()
runs.forEachIndexed { k, r ->
if (k > 0) {
val gap = full.substring(runs[k - 1].last + 1, r.first)
sb.append(
when {
'\n' in gap -> "\n"
gap.first().isWhitespace() || gap.last().isWhitespace() -> " "
else -> ""
}
)
}
sb.append(full.substring(r))
}
return sb.toString().trim()
}

fun move(d: Int, extend: Boolean) {
if (segs.isEmpty()) return
cursor = (cursor + d).coerceIn(0, segs.size - 1)
if (extend) selectSegRange(anchor, cursor, keep = false) else anchor = cursor
render(); onChange(); scrollToCursor()
}

fun toggleCursor() {
val r = segs.getOrNull(cursor) ?: return
val on = !r.all { sel[it] }
for (i in r) sel[i] = on
anchor = cursor
render(); onChange()
}

fun selectAll() { sel.fill(true); render(); onChange() }

fun clear() { sel.fill(false); render(); onChange() }

private fun selectSegRange(a: Int, b: Int, keep: Boolean) {
if (!keep) sel.fill(false)
val s = segs[minOf(a, b)].first
val e = segs[maxOf(a, b)].last
for (i in s..e) sel[i] = true
}

private fun segIndexAt(off: Int): Int {
if (segs.isEmpty()) return 0
segs.forEachIndexed { i, r -> if (off in r) return i }
// 落在空白里：取最近的片段
return segs.indices.minBy { i -> minOf(abs(segs[i].first - off), abs(segs[i].last - off)) }
}

private fun render() {
val sp = SpannableString(full)
segs.getOrNull(cursor)?.let { r ->
sp.setSpan(BackgroundColorSpan(curBg), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
var i = 0
while (i < full.length) {
if (sel[i]) {
val s = i
while (i < full.length && sel[i]) i++
sp.setSpan(BackgroundColorSpan(selBg), s, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
} else i++
}
segs.getOrNull(cursor)?.let { r ->
sp.setSpan(ForegroundColorSpan(cAccent), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
setText(sp, BufferType.SPANNABLE)
}

fun scrollToCursor() {
val sv = parent as? ScrollView ?: return
val r = segs.getOrNull(cursor) ?: return
post {
val l = layout ?: return@post
val top = l.getLineTop(l.getLineForOffset(r.first)) + paddingTop
val bottom = l.getLineBottom(l.getLineForOffset(r.last)) + paddingTop
val pad = (24 * resources.displayMetrics.density).toInt()
if (top < sv.scrollY) sv.smoothScrollTo(0, top - pad)
else if (bottom > sv.scrollY + sv.height) sv.smoothScrollTo(0, bottom - sv.height + pad)
}
}

/** 选中文字的第一个字在屏幕上的位置（飞行卡片的起点） */
fun firstPieceScreenPos(): IntArray {
val l = layout
val off = full.indices.firstOrNull { sel[it] } ?: segs.getOrNull(cursor)?.first ?: 0
val loc = IntArray(2)
getLocationOnScreen(loc)
if (l == null) return loc
val line = l.getLineForOffset(off)
loc[0] += paddingLeft + l.getPrimaryHorizontal(off).toInt()
loc[1] += paddingTop + l.getLineTop(line)
return loc
}

// ---------------- 触摸 / 鼠标 ----------------

private var downOff = 0
private var downX = 0f
private var downY = 0f
private var downMods = 0
private var dragging = false
private var armed = false
private var base: BooleanArray? = null
private var lastTapAt = 0L
private var lastTapSeg = -1
private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
private val longPress = Runnable {
armed = true
parent?.requestDisallowInterceptTouchEvent(true)
performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
startDrag()
}

private fun startDrag() {
dragging = true
base = if (downMods and KeyEvent.META_CTRL_ON != 0) sel.copyOf() else null
dragTo(downOff)
}

private fun dragTo(off: Int) {
val b = base
if (b != null) b.copyInto(sel) else sel.fill(false)
val a = minOf(downOff, off); val e = maxOf(downOff, off)
for (i in a until minOf(e + 1, full.length)) sel[i] = true
render(); onChange()
}

private fun offsetAt(x: Float, y: Float) = getOffsetForPosition(x, y).coerceIn(0, maxOf(0, full.length - 1))

@SuppressLint("ClickableViewAccessibility")
override fun onTouchEvent(ev: MotionEvent): Boolean {
val mouse = ev.isFromSource(InputDevice.SOURCE_MOUSE)
when (ev.actionMasked) {
MotionEvent.ACTION_DOWN -> {
downX = ev.x; downY = ev.y
downOff = offsetAt(ev.x, ev.y)
downMods = ev.metaState or mods()
dragging = false; armed = false
if (mouse) parent?.requestDisallowInterceptTouchEvent(true)
else postDelayed(longPress, 320)
}
MotionEvent.ACTION_MOVE -> {
val far = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
if (mouse && far && !dragging) startDrag()
if (!mouse && far && !armed) removeCallbacks(longPress)
if (dragging) dragTo(offsetAt(ev.x, ev.y))
}
MotionEvent.ACTION_UP -> {
removeCallbacks(longPress)
if (!dragging) tap()
dragging = false; armed = false
}
MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragging = false; armed = false }
}
return true
}

private fun tap() {
if (segs.isEmpty()) return
val i = segIndexAt(downOff)
val now = SystemClock.uptimeMillis()
val ctrl = downMods and KeyEvent.META_CTRL_ON != 0
val shift = downMods and KeyEvent.META_SHIFT_ON != 0
when {
ctrl -> { cursor = i; toggleCursor(); return }
shift -> { cursor = i; selectSegRange(anchor, i, keep = false) }
else -> {
if (i == lastTapSeg && now - lastTapAt < 350) {
lastTapSeg = -1
sel.fill(false); for (k in segs[i]) sel[k] = true
cursor = i; render(); onChange(); onDoubleTap(); return
}
sel.fill(false); for (k in segs[i]) sel[k] = true
cursor = i; anchor = i
}
}
lastTapAt = now; lastTapSeg = i
render(); onChange()
}

/** 把选中的文字画成一张小卡片（飞行动画用） */
fun chip(text: String, card: Int, border: Int, textColor: Int, maxW: Int): Bitmap? = runCatching {
val d = resources.displayMetrics.density
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
