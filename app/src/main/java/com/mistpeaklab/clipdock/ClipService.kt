package com.mistpeaklab.clipdock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 增强模式（无障碍服务）。负责：
 * 1. 后台记录剪贴板：Android 10 起后台应用读不到剪贴板，只有“拥有焦点的窗口”能读。
 *    检测到“可能复制了”（Ctrl+C / Ctrl+X、点了“复制”、出现“已复制”提示、打开面板）时，
 *    瞬间弹出一个 1 像素透明可获焦窗口，拿到焦点那一刻读剪贴板，随即移除。
 *    读不到时，退而读取当前输入框里选中的文字（密码框跳过）。
 * 2. 全局快捷键：监听实体键盘，按下设定的组合键就切换面板。
 * 3. 面板打开时的键盘操作。
 * 4. 可选的自动粘贴。
 */
class ClipService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClipService? = null
            private set

 /** 设置页正在录制快捷键时为 true，此时不拦截按键 */
 @Volatile var recordingHotkey = false

 private val COPY_WORDS = listOf("复制", "拷贝", "剪切", "copy", "cut")
 private val COPIED_WORDS = listOf("已复制", "复制成功", "已拷贝", "已剪切", "copied")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var probe: View? = null
    private var swallowKeyUp = -1

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        ClipStore.init(this)
 main.postDelayed({ captureClipboard() }, 800)
    }

    override fun onDestroy() {
        PanelHost.hide()
        removeProbe()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * 把文字粘贴进当前焦点输入框：先试标准的“粘贴”动作；
     * 不支持的控件改为在光标处插入文字。稍等一下，让面板先收起、焦点回到输入框。
     */
    fun pasteIntoFocusedLater(text: String, cards: List<FlyCard> = emptyList()) {
 if (cards.isEmpty() || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
     cards.forEach { it.bmp.recycle() }
     main.postDelayed({ pasteInto(text) }, 180)
     return
 }
 flyThenPaste(text, cards, retry = true)
    }

    /** 找到输入框光标位置，卡片飞过去，落地时粘贴；找不到输入框就直接走原来的流程 */
    private fun flyThenPaste(text: String, cards: List<FlyCard>, retry: Boolean) {
 val node = editableFocus()
 // 刚从面板搜索框退出时，焦点还没回到原输入框：稍等再找一次
 if ((node == null || node.packageName == packageName) && retry) {
     main.postDelayed({ flyThenPaste(text, cards, retry = false) }, 160)
     return
 }
 val target = node?.takeIf { it.packageName != packageName }?.let(::caretRect)
 if (target == null) {
     cards.forEach { it.bmp.recycle() }
     pasteInto(text)
     return
 }
 PasteFly.run(this, wm, cards, target) { pasteInto(text) }
    }

    private fun editableFocus(): AccessibilityNodeInfo? = runCatching {
 (findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
     ?.takeIf { it.isEditable }
    }.getOrNull()

    /** 光标在屏幕上的位置：优先取光标前一个字符的精确位置，取不到就用输入框左上部 */
    private fun caretRect(node: AccessibilityNodeInfo): android.graphics.RectF? = runCatching {
 val box = android.graphics.Rect().also { node.getBoundsInScreen(it) }
 if (box.isEmpty) return null
 val len = if (node.isShowingHintText) 0 else node.text?.length ?: 0
 val sel = node.textSelectionEnd.let { if (it in 0..len) it else len }
 val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
 if (len > 0 && node.availableExtraData?.contains(key) == true) {
     val idx = if (sel > 0) sel - 1 else 0
     val args = android.os.Bundle().apply {
  putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, idx)
  putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
     }
     if (node.refreshWithExtraData(key, args)) {
  val r = node.extras.getParcelableArray(key, android.graphics.RectF::class.java)?.getOrNull(0)
  if (r != null && r.height() > 0 && box.contains(r.centerX().toInt(), r.centerY().toInt())) {
      val x = if (sel > 0) r.right else r.left
      return android.graphics.RectF(x, r.top, x + 2, r.bottom)
  }
     }
 }
 val d = resources.displayMetrics.density
 val cy = box.top + minOf(box.height() / 2f, 24 * d)
 android.graphics.RectF(box.left + 16 * d, cy - 10 * d, box.left + 16 * d + 2, cy + 10 * d)
    }.getOrNull()

    private fun pasteInto(text: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
     ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
 if (node == null || !node.isEditable) {
     android.widget.Toast.makeText(this, "已复制，没找到输入框，请按 Ctrl+V 粘贴", android.widget.Toast.LENGTH_SHORT).show()
            return
 }
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return
        val cur = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
 var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s !in 0..cur.length || e !in 0..cur.length) { s = cur.length; e = cur.length }
        val a = minOf(s, e); val b = maxOf(s, e)
        val args = android.os.Bundle().apply {
     putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.substring(0, a) + text + cur.substring(b))
 }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
     val pos = a + text.length
     node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
         putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
  putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
     })
        }
    }

    // ---------------- 按键 ----------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (recordingHotkey) return false
        val code = event.keyCode

 if (event.action == KeyEvent.ACTION_UP && code == swallowKeyUp) {
     swallowKeyUp = -1
     return true
        }
        // 面板需要知道 Ctrl / Shift 是否按住（Ctrl+点击、Shift+点击），只记录，不拦截
 if (PanelHost.isShowing && Hotkey.isModifierKey(code)) PanelHost.handleModifier(event)
 if (event.action != KeyEvent.ACTION_DOWN) return false

        if (prefs.hotkeyEnabled && prefs.hotkey.matches(event)) {
            if (event.repeatCount == 0) {
         if (!PanelHost.isShowing) PanelHost.show(this)
         else if (prefs.closeOnHotkey) PanelHost.hide()
     }
     swallowKeyUp = code
     return true
 }

 if (PanelHost.isShowing && PanelHost.handleKey(event)) {
     swallowKeyUp = code
     return true
        }

        if (event.isCtrlPressed && !event.isAltPressed &&
     (code == KeyEvent.KEYCODE_C || code == KeyEvent.KEYCODE_X)
        ) {
     val selected = selectedTextOfFocused()
     main.postDelayed({ captureClipboard(fallback = selected) }, 250)
        }
        return false
    }

    // ---------------- 无障碍事件 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        when (event.eventType) {
     AccessibilityEvent.TYPE_VIEW_CLICKED -> {
  val label = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
  if (label.length <= 20 && COPY_WORDS.any { label.contains(it) }) {
      main.postDelayed({ captureClipboard() }, 300)
         }
            }
     AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
         val text = event.text.joinToString(" ").lowercase()
         if (COPIED_WORDS.any { text.contains(it) }) main.postDelayed({ captureClipboard() }, 100)
     }
     AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
  if (PanelHost.isShowing && prefs.closeOnAppSwitch && pkg != null &&
      pkg != "com.android.systemui" && event.className?.contains("Activity") == true
  ) PanelHost.hide()
     }
 }
    }

    private fun selectedTextOfFocused(): String? = runCatching {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val t = n.text ?: return null
        val s = n.textSelectionStart
        val e = n.textSelectionEnd
        if (s in 0 until e && e <= t.length && !n.isPassword) t.subSequence(s, e).toString() else null
    }.getOrNull()

    // ---------------- 读剪贴板（焦点探针） ----------------

    fun captureClipboard(fallback: String? = null) {
        if (ClipReader.capture(this)) return
        if (probe != null) return
        val view = object : View(this) {
     var done = false
     override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
         super.onWindowFocusChanged(hasWindowFocus)
  if (hasWindowFocus && !done) {
      done = true
      if (!ClipReader.capture(this@ClipService) && fallback != null) record(fallback)
      removeProbe()
  }
     }
        }
        val lp = WindowManager.LayoutParams(
     1, 1,
     WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
  WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
  WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
     PixelFormat.TRANSLUCENT,
 ).apply {
     gravity = Gravity.TOP or Gravity.START
     alpha = 0f
     windowAnimations = 0
        }
 probe = view
 runCatching { wm.addView(view, lp) }.onFailure {
     probe = null
     fallback?.let(::record)
     return
        }
        main.postDelayed({
     if (probe === view) {
  if (!view.done) fallback?.let(::record)
  removeProbe()
     }
        }, 600)
    }

    private fun removeProbe() {
 val v = probe ?: return
        probe = null
        runCatching { wm.removeViewImmediate(v) }
    }

    private fun record(text: String) {
 ClipStore.add(text, prefs.maxItems)
    }
}

fun Context.isClipServiceOn(): Boolean = ClipService.instance != null
