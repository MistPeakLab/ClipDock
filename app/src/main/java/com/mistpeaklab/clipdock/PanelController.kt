package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮剪贴板面板。
 * - 增强模式（无障碍）：不获取焦点，输入框不失焦、不弹输入法；键盘由服务转发 handleKey。
 * - 基础模式（悬浮窗权限）：可获焦，打开时能读当前剪贴板；键盘由窗口自己处理。
 * - 直接打字即搜索；Tab 切换“最近 / 常用语”。
 * - 多选遵循常规习惯：Ctrl+A 全选、Ctrl+点击加选、Shift+点击 / Shift+↑↓ 连选；Ctrl+V 粘贴、Ctrl+C 复制。
 * - 标题栏拖动移动，右下角手柄拖动缩放，位置和尺寸会记住。
 */
class PanelController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val windowType: Int,
    private val focusable: Boolean,
    private val onAct: (text: String, mode: Int) -> Unit,
    private val onFocused: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dark = when (prefs.theme) {
        1 -> false; 2 -> true
 else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val cBg = if (dark) 0xF51E1F22.toInt() else 0xF7F6F7FA.toInt()
    private val cCard = if (dark) 0xFF2B2D31.toInt() else 0xFFFFFFFF.toInt()
    private val cField = if (dark) 0xFF26282C.toInt() else 0xFFECEEF2.toInt()
    private val cText = if (dark) 0xFFE8EAED.toInt() else 0xFF1F2328.toInt()
    private val cSub = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B7280.toInt()
    private val cAccent = 0xFF4C8DF6.toInt()
    private val cSelBg = if (dark) 0xFF263452.toInt() else 0xFFE3EDFF.toInt()
    private val cBorder = if (dark) 0x26FFFFFF else 0x1A000000

    private val shadow = 12
    private val minW = 260; private val minH = 240
    private val maxRows = 200

    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var tabRecent: TextView
    private lateinit var tabPhrase: TextView
    private lateinit var selBar: LinearLayout
    private lateinit var selLabel: TextView
    private lateinit var lp: WindowManager.LayoutParams

    private var tab = prefs.panelTab
    private var query = ""
    private var editing = false
    private var items: List<ClipItem> = emptyList()
    private val picked = LinkedHashSet<Long>() // 多选
    private var anchor = -1 // Shift 连选的起点
    private var ctrlHeld = false
    private var shiftHeld = false
    private var touchMeta = 0 // 触摸按下时键盘修饰键状态
    private var selected = 0
    private var shown = false
    private var wDp = prefs.panelW
    private var hDp = prefs.panelH
    private val listener: () -> Unit = { root.post { refresh() } }

    private val dm get() = ctx.resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private fun toDp(px: Int) = (px / dm.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        clampSize()
        root = object : FrameLayout(ctx) {
     override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
  if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
      if (prefs.closeOnOutside) dismiss()
      return true
  }
         return super.dispatchTouchEvent(ev)
            }

     override fun dispatchKeyEvent(event: KeyEvent): Boolean {
  handleModifier(event)
  // 窗口自己有焦点时（基础模式 / 正在用输入法搜索），键盘事件到这里
         if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
         if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
      if (editing || query.isNotEmpty()) clearSearch() else dismiss()
      return true
  }
         return super.dispatchKeyEvent(event)
            }

     override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
         super.onWindowFocusChanged(hasWindowFocus)
  if (hasWindowFocus) onFocused()
     }
 }

        panel = LinearLayout(ctx).apply {
     orientation = LinearLayout.VERTICAL
     background = round(cBg, dp(16).toFloat(), cBorder)
     elevation = dp(8).toFloat()
     setPadding(dp(8), dp(4), dp(8), dp(8))
     clipToOutline = true
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            setMargins(dp(shadow), dp(shadow), dp(shadow), dp(shadow))
        })

        // 标题栏：拖动移动
        val header = LinearLayout(ctx).apply {
     orientation = LinearLayout.HORIZONTAL
     gravity = Gravity.CENTER_VERTICAL
     setPadding(dp(6), 0, 0, 0)
 }
        header.addView(ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher_fg); scaleType = ImageView.ScaleType.CENTER_CROP },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        tabRecent = tabView("最近") { switchTab(0) }
 tabPhrase = tabView("常用语") { switchTab(1) }
        header.addView(tabRecent)
        header.addView(tabPhrase)
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
 header.addView(iconButton(R.drawable.ic_clear, "清空（保留置顶）") { ClipStore.clearUnpinned() })
        header.addView(iconButton(R.drawable.ic_clip, "打开主界面") {
            dismiss()
     ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
 header.addView(iconButton(R.drawable.ic_close, "关闭") { dismiss() })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
 enableDrag(header)

        // 搜索框：直接打字即可；点一下可用输入法（中文）
        search = EditText(ctx).apply {
     hint = "直接打字搜索"
     setHintTextColor(cSub); setTextColor(cText); textSize = 13f
     isSingleLine = true
     imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
     background = round(cField, dp(10).toFloat())
     setPadding(dp(12), dp(6), dp(12), dp(6))
     isFocusable = false; isFocusableInTouchMode = false
     setOnClickListener { startEditing() }
     addTextChangedListener(object : TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
  override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
  override fun afterTextChanged(s: Editable?) {
      val q = s?.toString() ?: ""
      if (q != query) { query = q; selected = 0; refresh() }
  }
     })
 }
        panel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
            topMargin = dp(2); bottomMargin = dp(2)
        })

        scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
 list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 多选操作条
        selBar = LinearLayout(ctx).apply {
     orientation = LinearLayout.HORIZONTAL
     gravity = Gravity.CENTER_VERTICAL
     background = round(cSelBg, dp(10).toFloat())
     setPadding(dp(12), dp(4), dp(4), dp(4))
     visibility = View.GONE
        }
 selLabel = TextView(ctx).apply { setTextColor(cText); textSize = 13f }
        selBar.addView(selLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selBar.addView(pillButton("取消", false) { picked.clear(); refresh() })
 selBar.addView(pillButton("复制", false) { actOnSelection(MODE_COPY) })
        selBar.addView(pillButton("粘贴", true) { actOnSelection(MODE_PASTE) })
        panel.addView(selBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
     topMargin = dp(6); marginEnd = dp(18)
 })

 val grip = ResizeGrip(ctx, cSub)
 root.addView(grip, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END).apply {
     setMargins(0, 0, dp(shadow), dp(shadow))
 })
        enableResize(grip)

 lp = WindowManager.LayoutParams(
     dp(wDp + shadow * 2), dp(hDp + shadow * 2), windowType, baseFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
     gravity = Gravity.TOP or Gravity.START
     val sx = prefs.panelX; val sy = prefs.panelY
     x = if (sx >= 0) sx else (dm.widthPixels - width) / 2
     y = if (sy >= 0) sy else (dm.heightPixels - height) / 2
            windowAnimations = android.R.style.Animation_Dialog
     softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        clampPos()
        wm.addView(root, lp)
 shown = true
 ClipStore.addListener(listener)
 updateTabs()
 refresh()
    }

    fun dismiss() {
 if (!shown) return
 shown = false
        ClipStore.removeListener(listener)
        if (editing) hideIme()
        runCatching { wm.removeView(root) }
        onDismiss()
    }

    private fun baseFlags(): Int {
 var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        f = f or if (focusable) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return f
    }

    // ---------------- 搜索 ----------------

    /** 点搜索框：临时让窗口可获焦并允许输入法，方便输入中文 */
    private fun startEditing() {
        if (editing) return
        editing = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
     WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        runCatching { wm.updateViewLayout(root, lp) }
 search.isFocusable = true; search.isFocusableInTouchMode = true
        search.requestFocus()
 search.setSelection(search.text.length)
 search.post { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0) }
    }

    private fun stopEditing() {
        if (!editing) return
 editing = false
        hideIme()
        search.clearFocus()
        search.isFocusable = false; search.isFocusableInTouchMode = false
 lp.flags = baseFlags()
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun hideIme() = ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)

    private fun setQuery(q: String) {
 search.setText(q)
 search.setSelection(q.length)
    }

    private fun clearSearch() {
        setQuery("")
 stopEditing()
    }

    private fun switchTab(t: Int) {
        if (tab == t) return
 tab = t; prefs.panelTab = t
        selected = 0; picked.clear()
        updateTabs(); refresh()
    }

    private fun updateTabs() {
        listOf(tabRecent to 0, tabPhrase to 1).forEach { (v, i) ->
     val on = tab == i
     v.setTextColor(if (on) cAccent else cSub)
     v.paint.isFakeBoldText = on
            v.background = if (on) round(0x1F4C8DF6, dp(8).toFloat()) else null
     v.invalidate()
        }
    }

    // ---------------- 列表 ----------------

    private fun refresh() {
        if (!shown) return
        val base = if (tab == 0) ClipStore.history() else ClipStore.phrases()
        val q = query.trim()
 items = if (q.isEmpty()) base else base.filter { it.text.contains(q, ignoreCase = true) }
        picked.retainAll(base.map { it.id }.toSet())
 selected = selected.coerceIn(0, max(0, items.size - 1))
        list.removeAllViews()

 selBar.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        selLabel.text = "已选 ${picked.size} 条 · Ctrl+V 粘贴"

 if (items.isEmpty()) {
     list.addView(ImageView(ctx).apply {
         setImageResource(R.drawable.ic_empty)
         imageTintList = ColorStateList.valueOf(cSub)
         alpha = 0.5f
     }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(32) })
     list.addView(TextView(ctx).apply {
         text = when {
      q.isNotEmpty() -> "没有包含“$q”的内容"
      tab == 1 -> "还没有常用语\n在主界面的“常用语”里添加"
      else -> "还没有记录\n复制一些文字后会出现在这里"
  }
  setTextColor(cSub); textSize = 13f; gravity = Gravity.CENTER
  setPadding(dp(16), dp(10), dp(16), dp(32))
     })
     return
        }
        items.take(maxRows).forEachIndexed { i, item -> list.addView(itemView(i, item)) }
        ensureVisible()
    }

    private fun itemView(index: Int, item: ClipItem): View {
 val cursor = index == selected
        val isPicked = item.id in picked
        val row = LinearLayout(ctx).apply {
     orientation = LinearLayout.HORIZONTAL
     gravity = Gravity.CENTER_VERTICAL
     setPadding(dp(10), dp(8), dp(2), dp(8))
     background = RippleDrawable(
         ColorStateList.valueOf(0x334C8DF6),
         round(if (isPicked) cSelBg else cCard, dp(10).toFloat(), if (cursor) cAccent else Color.TRANSPARENT, dp(2)),
         null,
     )
     setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchMeta = ev.metaState; false }
     setOnClickListener { onRowClick(index) }
     setOnLongClickListener { togglePick(index); true }
 }
        val badge = when {
     isPicked -> "✓"
     index < 9 && query.isEmpty() -> "${index + 1}"
            else -> ""
 }
        row.addView(TextView(ctx).apply {
     text = badge
     setTextColor(if (cursor || isPicked) cAccent else cSub); textSize = 11f
     gravity = Gravity.CENTER
 }, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })

        row.addView(TextView(ctx).apply {
     text = item.text.trim().replace(Regex("\\s*\\n\\s*"), " ⏎ ")
     setTextColor(cText); textSize = 13.5f
     maxLines = prefs.previewLines; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!item.phrase) {
     row.addView(iconButton(if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
         if (item.pinned) "取消置顶" else "置顶", small = true,
         tint = if (item.pinned) cAccent else cSub) { ClipStore.togglePin(item.id) })
     row.addView(iconButton(R.drawable.ic_delete, "删除", small = true) { ClipStore.delete(item.id) })
        } else {
     row.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
     .apply { topMargin = dp(5) }
        return row
    }

    // ---------------- 选择与动作 ----------------

    private fun visibleCount() = minOf(items.size, maxRows)

    private fun onRowClick(index: Int) {
        val ctrl = ctrlHeld || (touchMeta and KeyEvent.META_CTRL_ON) != 0
        val shift = shiftHeld || (touchMeta and KeyEvent.META_SHIFT_ON) != 0
        when {
     ctrl -> togglePick(index)
     shift -> selectRange(index)
     else -> items.getOrNull(index)?.let { picked.clear(); act(it.text, MODE_DEFAULT) }
 }
    }

    private fun togglePick(index: Int) {
 val item = items.getOrNull(index) ?: return
        if (!picked.remove(item.id)) picked += item.id
        anchor = index; selected = index
        refresh()
    }

    private fun selectRange(to: Int) {
        val from = if (anchor in items.indices) anchor else selected
        for (i in minOf(from, to)..maxOf(from, to)) items.getOrNull(i)?.let { picked += it.id }
 selected = to
        refresh()
    }

    private fun selectAll() {
        picked.clear()
        items.forEach { picked += it.id }
        refresh()
    }

    /** 有多选时作用于选中的全部（按列表顺序合并），否则作用于光标所在的一条 */
    private fun actOnSelection(mode: Int) {
        val text = if (picked.isNotEmpty()) {
            val base = if (tab == 0) ClipStore.history() else ClipStore.phrases()
     TextTools.join(base.filter { it.id in picked }.map { it.text }, prefs.mergeMode)
        } else items.getOrNull(selected)?.text ?: return
        act(text, mode)
    }

    private fun act(text: String, mode: Int) {
        picked.clear()
 onAct(text, mode)
        if (!shown) return
        if (prefs.closeOnSelect) dismiss() else { stopEditing(); refresh() }
    }

    /** 记录 Ctrl / Shift 是否按住（用于 Ctrl+点击、Shift+点击） */
    fun handleModifier(e: KeyEvent) {
        val down = e.action == KeyEvent.ACTION_DOWN
 when (e.keyCode) {
     KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ctrlHeld = down
     KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> shiftHeld = down
        }
    }

    private fun ensureVisible() {
        val v = list.getChildAt(selected) ?: return
 scroll.post {
            if (v.top < scroll.scrollY) scroll.smoothScrollTo(0, v.top - dp(5))
     else if (v.bottom > scroll.scrollY + scroll.height) scroll.smoothScrollTo(0, v.bottom - scroll.height + dp(5))
        }
    }

    // ---------------- 键盘 ----------------

    /** 返回 true 表示已处理（不再传给前台应用 / 输入框）。 */
    fun handleKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed; val alt = e.isAltPressed; val meta = e.isMetaPressed
        val shift = e.isShiftPressed
        val plain = !ctrl && !alt && !meta
 when (e.keyCode) {
     KeyEvent.KEYCODE_ESCAPE -> {
  when {
      picked.isNotEmpty() -> { picked.clear(); refresh() }
      query.isNotEmpty() || editing -> clearSearch()
      prefs.closeOnEsc -> dismiss()
      else -> return false
  }
  return true
     }
     KeyEvent.KEYCODE_TAB -> if (plain && !shift) { switchTab(1 - tab); return true }
     KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> if (plain) {
         val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
         if (shift) {
      if (picked.isEmpty()) anchor = selected
      selectRange((selected + d).coerceIn(0, max(0, visibleCount() - 1)))
         } else move(d)
  return true
     }
            KeyEvent.KEYCODE_PAGE_DOWN -> { move(5); return true }
     KeyEvent.KEYCODE_PAGE_UP -> { move(-5); return true }
     KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (plain) {
         if (shift) togglePick(selected) else actOnSelection(MODE_DEFAULT)
         return true
     }
     KeyEvent.KEYCODE_FORWARD_DEL -> if (plain) {
  if (picked.isNotEmpty()) { val ids = picked.toList(); picked.clear(); ids.forEach { ClipStore.delete(it) } }
  else items.getOrNull(selected)?.takeIf { !it.phrase }?.let { ClipStore.delete(it.id) }
         return true
     }
 }
        // 标准快捷键（正在用输入法编辑搜索框时，交给搜索框自己处理）
        if (!editing && ctrl && !alt && !meta && !shift) when (e.keyCode) {
     KeyEvent.KEYCODE_A -> { selectAll(); return true }
     KeyEvent.KEYCODE_V -> { actOnSelection(MODE_PASTE); return true }
     KeyEvent.KEYCODE_C -> { actOnSelection(MODE_COPY); return true }
     KeyEvent.KEYCODE_P -> {
         items.getOrNull(selected)?.takeIf { !it.phrase }?.let { ClipStore.togglePin(it.id) }; return true
     }
 }
        if (editing) return false
        if (!plain) return false
        // 搜索框为空时，1–9 是快选
        if (query.isEmpty() && !shift && e.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
     items.getOrNull(e.keyCode - KeyEvent.KEYCODE_1)?.let { act(it.text, MODE_DEFAULT) }
     return true
        }
 if (e.keyCode == KeyEvent.KEYCODE_DEL) {
            if (query.isEmpty()) return true
     setQuery(query.dropLast(1)); return true
        }
 val ch = e.unicodeChar
        if (ch != 0 && !Character.isISOControl(ch)) {
     if (ch == ' '.code && query.isEmpty()) return true
     setQuery(query + String(Character.toChars(ch)))
     return true
 }
        return false
    }

    private fun move(d: Int) {
        if (items.isEmpty()) return
 selected = (selected + d).coerceIn(0, minOf(items.size, maxRows) - 1)
        refresh()
    }

    // ---------------- 拖动 / 缩放 ----------------

    private fun clampSize() {
 val maxW = toDp(dm.widthPixels) - shadow * 2
        val maxH = toDp(dm.heightPixels) - shadow * 2
        wDp = wDp.coerceIn(minW, max(minW, maxW))
 hDp = hDp.coerceIn(minH, max(minH, maxH))
    }

    private fun clampPos() {
        lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - lp.height))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View) {
        var dx0 = 0f; var dy0 = 0f; var x0 = 0; var y0 = 0; var dragging = false
 handle.setOnTouchListener { _, ev ->
     when (ev.actionMasked) {
  MotionEvent.ACTION_DOWN -> { dx0 = ev.rawX; dy0 = ev.rawY; x0 = lp.x; y0 = lp.y; dragging = false }
  MotionEvent.ACTION_MOVE -> {
      val dx = ev.rawX - dx0; val dy = ev.rawY - dy0
      if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) dragging = true
      if (dragging) {
          lp.x = x0 + dx.toInt(); lp.y = y0 + dy.toInt(); clampPos()
          runCatching { wm.updateViewLayout(root, lp) }
      }
  }
         MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { prefs.panelX = lp.x; prefs.panelY = lp.y }
     }
     true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableResize(grip: View) {
 var rx = 0f; var ry = 0f; var w0 = 0; var h0 = 0
        grip.setOnTouchListener { _, ev ->
     when (ev.actionMasked) {
  MotionEvent.ACTION_DOWN -> { rx = ev.rawX; ry = ev.rawY; w0 = wDp; h0 = hDp }
         MotionEvent.ACTION_MOVE -> {
      wDp = (w0 + toDp((ev.rawX - rx).toInt())).coerceIn(minW, max(minW, toDp(dm.widthPixels - lp.x) - shadow * 2))
      hDp = (h0 + toDp((ev.rawY - ry).toInt())).coerceIn(minH, max(minH, toDp(dm.heightPixels - lp.y) - shadow * 2))
      (panel.layoutParams as FrameLayout.LayoutParams).apply { width = dp(wDp); height = dp(hDp) }
      lp.width = dp(wDp + shadow * 2); lp.height = dp(hDp + shadow * 2)
      runCatching { wm.updateViewLayout(root, lp) }
  }
  MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { prefs.panelW = wDp; prefs.panelH = hDp }
     }
     true
        }
    }

    // ---------------- 小部件 ----------------

    private fun round(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeW: Int = 1) =
 GradientDrawable().apply {
     setColor(color); cornerRadius = radius
     if (stroke != Color.TRANSPARENT) setStroke(strokeW, stroke)
 }

    private fun tabView(label: String, onClick: () -> Unit) = TextView(ctx).apply {
 text = label; textSize = 13.5f
        setPadding(dp(10), dp(5), dp(10), dp(5))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
     .apply { marginEnd = dp(2) }
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
 text = label; textSize = 13f
        setTextColor(if (primary) Color.WHITE else cText)
        background = round(if (primary) cAccent else Color.TRANSPARENT, dp(14).toFloat())
        setPadding(dp(12), dp(5), dp(12), dp(5))
 setOnClickListener { onClick() }
 layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
     .apply { marginStart = dp(4) }
    }

    private fun iconButton(res: Int, desc: String, small: Boolean = false, tint: Int = cSub, onClick: () -> Unit) =
 ImageView(ctx).apply {
     setImageResource(res)
     imageTintList = ColorStateList.valueOf(tint)
     contentDescription = desc
     tooltipText = desc
     val p = if (small) dp(7) else dp(8)
     setPadding(p, p, p, p)
     background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, round(Color.WHITE, dp(18).toFloat()))
     isFocusable = false
     setOnClickListener { onClick() }
     val s = if (small) dp(30) else dp(34)
     layoutParams = LinearLayout.LayoutParams(s, s)
        }
}

/** 动作：0 按设置（直接粘贴或只复制） 1 直接粘贴 2 只复制 */
const val MODE_DEFAULT = 0
const val MODE_PASTE = 1
const val MODE_COPY = 2

/** 右下角三道斜线的缩放手柄 */
private class ResizeGrip(ctx: Context, color: Int) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
 this.color = color; strokeWidth = ctx.resources.displayMetrics.density * 1.6f
        strokeCap = android.graphics.Paint.Cap.ROUND; alpha = 160
    }

    override fun onDraw(c: android.graphics.Canvas) {
 val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        for (i in 1..3) {
     val o = i * 5f * d
     c.drawLine(w - 6 * d - o + 4 * d, h - 6 * d, w - 6 * d, h - 6 * d - o + 4 * d, paint)
 }
    }
}
