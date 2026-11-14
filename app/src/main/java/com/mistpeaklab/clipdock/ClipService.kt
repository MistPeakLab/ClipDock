package com.mistpeaklab.clipdock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 增强模式（无障碍服务）。负责：
 * 1. 后台记录剪贴板：Android 10 起后台应用读不到剪贴板，只有“拥有焦点的窗口”能读。
 *    检测到“可能复制了”时（触发来源见 [requestCapture]），
 *    瞬间弹出一个 1 像素透明可获焦窗口，拿到焦点那一刻读剪贴板，随即移除。
 *    Ctrl+C 读不到时，退而读取当前输入框里选中的文字（密码框跳过）。
 *    每次读取的来源和结果都记进 [Diag]，方便定位“为什么没记下来”。
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
 private val COPIED_WORDS = listOf("已复制", "复制成功", "已拷贝", "已剪切", "已複製", "copied")
        private const val SYSTEMUI = "com.android.systemui"

        /** 两次读取之间至少隔这么久；期间到达的触发合并成一次 */
        private const val MIN_GAP = 400L
        /** 切换应用、解锁这类兜底触发的最小间隔 */
        private const val PASSIVE_GAP = 1500L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var probe: View? = null
    private var swallowKeyUp = -1

    // 收录触发的状态（都只在主线程读写）
    private var pendingRun: Runnable? = null
    private var pendingTrigger = ""
    private var pendingFallback: String? = null
    private var pendingDue = 0L
    private var lastProbeAt = 0L
    private var lastPassiveAt = 0L
    private var lastKeyAt = 0L
    private var lastLongClickAt = 0L
    private var currentPkg: String? = null
    private var selActive = false
    private var selPkg: String? = null
    private var selLen = 0
    private var selHash = 0

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { requestCapture("UNLOCK", 800) }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        ClipStore.init(this)
        runCatching { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED) }
        requestCapture("SERVICE_START", 800)
    }

    override fun onDestroy() {
        PanelHost.hide()
        runCatching { unregisterReceiver(unlockReceiver) }
        pendingRun?.let(main::removeCallbacks)
        pendingRun = null
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
        lastKeyAt = SystemClock.uptimeMillis()

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
     requestCapture("KEY_COPY", 250, selected)
        }
        return false
    }

    // ---------------- 无障碍事件 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        if (Diag.sampling) sampleEvent(event, pkg)
        val now = SystemClock.uptimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = clickLabel(event)
                if (label.length <= 40 && COPY_WORDS.any { label.contains(it) }) {
                    lastLongClickAt = 0
                    requestCapture("CLICK_COPY", 300)
                } else if (now - lastLongClickAt < 6000) {
                    // 长按后弹出的菜单里，“复制”常常只是个图标、没有文字：长按之后的第一次点击也读一次
                    lastLongClickAt = 0
                    requestCapture("LONG_CLICK", 350)
                }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> lastLongClickAt = now
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event, pkg)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text.joinToString(" ").lowercase()
                if (COPIED_WORDS.any { text.contains(it) }) {
                    requestCapture(if (pkg == SYSTEMUI) "SYSTEM_CLIP_UI" else "COPIED_TOAST", 100)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (PanelHost.isShowing && prefs.closeOnAppSwitch && pkg != null &&
                    pkg != SYSTEMUI && event.className?.contains("Activity") == true
                ) PanelHost.hide()
                if (pkg == SYSTEMUI) {
                    // Android 13 起复制后系统会弹出剪贴板预览
                    if (event.className?.contains("clipboard", ignoreCase = true) == true) requestCapture("SYSTEM_CLIP_UI", 100)
                } else if (pkg != null && pkg != currentPkg && !isInputMethod(pkg)) {
                    currentPkg = pkg
                    requestCapture("APP_SWITCH", 600)
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ADDED != 0 && clipboardWindowShowing()) {
                    requestCapture("SYSTEM_CLIP_UI", 100)
                }
            }
        }
    }

    /** 被点控件的文字；控件自己没有文字时（外层容器），向下找两层子控件 */
    private fun clickLabel(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text.forEach { sb.append(it).append(' ') }
        event.contentDescription?.let { sb.append(it) }
        if (sb.isBlank()) runCatching { event.source?.let { collectLabel(it, 0, sb, IntArray(1)) } }
        return sb.toString().trim().lowercase()
    }

    private fun collectLabel(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, visited: IntArray) {
        if (sb.length > 60 || visited[0]++ > 12) return
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        if (depth >= 2) return
        for (i in 0 until minOf(n.childCount, 6)) n.getChild(i)?.let { collectLabel(it, depth + 1, sb, visited) }
    }

    /**
     * 选中的文字不见了、而文字本身没变：多半是点了选择工具栏上的“复制”。
     * 文字变了说明是打字替换或删除，不算。这里只是去读剪贴板，不会把选中的文字直接记下来。
     */
    private fun onSelectionChanged(event: AccessibilityEvent, pkg: String?) {
        val full = event.text.firstOrNull()
        val from = event.fromIndex
        val to = event.toIndex
        if (from >= 0 && to >= 0 && from != to) {
            if (!event.isPassword && full != null) {
                selActive = true
                selPkg = pkg
                selLen = full.length
                selHash = full.toString().hashCode()
            } else selActive = false
        } else if (selActive) {
            selActive = false
            if (pkg == selPkg && full != null && full.length == selLen && full.toString().hashCode() == selHash) {
                requestCapture("SELECTION_END", 300)
            }
        }
    }

    private fun clipboardWindowShowing(): Boolean = runCatching {
        windows.any { it.title?.contains("clipboard", ignoreCase = true) == true }
    }.getOrDefault(false)

    private fun isInputMethod(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ime.substringBefore('/') == pkg
    }

    /** 事件采样（诊断页手动开启，限时）：记下事件概要，用来给没适配过的机型找“刚复制了”的特征 */
    private fun sampleEvent(event: AccessibilityEvent, pkg: String?) {
        val sb = StringBuilder(AccessibilityEvent.eventTypeToString(event.eventType).removePrefix("TYPE_"))
        sb.append(' ').append(pkg).append(' ').append(event.className?.toString()?.substringAfterLast('.'))
        val text = event.text.joinToString(" ")
        if (text.isNotEmpty()) sb.append(" [").append(if (text.length <= 8) text else "${text.length}字").append(']')
        runCatching { event.source?.viewIdResourceName }.getOrNull()?.let { sb.append(" id=").append(it.substringAfter('/')) }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            runCatching { windows.mapNotNull { it.title?.toString() } }.getOrNull()?.let { sb.append(" 窗口=").append(it) }
        }
        Diag.sample(sb.toString())
    }

    private fun selectedTextOfFocused(): String? = runCatching {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val t = n.text ?: return null
        val s = n.textSelectionStart
        val e = n.textSelectionEnd
        if (s in 0 until e && e <= t.length && !n.isPassword) t.subSequence(s, e).toString() else null
    }.getOrNull()

    // ---------------- 读剪贴板（焦点探针） ----------------

    private fun isPassive(trigger: String) =
        trigger == "APP_SWITCH" || trigger == "UNLOCK" || trigger == "LONG_CLICK" || trigger == "SELECTION_END"

    /**
     * 请求读一次剪贴板。trigger 是来源：
     * KEY_COPY 键盘复制 / CLICK_COPY 点了“复制” / COPIED_TOAST “已复制”提示 / SYSTEM_CLIP_UI 系统剪贴板预览，
     * 以及“补漏检测”的 LONG_CLICK / SELECTION_END / APP_SWITCH / UNLOCK（没有明确的复制信号，可在设置里关）。
     * 短时间内的多次请求合并成一次；有明确信号的请求会顶替补漏请求，并把时间推到自己需要的时刻。
     */
    fun requestCapture(trigger: String, delay: Long, fallback: String? = null) {
        val now = SystemClock.uptimeMillis()
        val passive = isPassive(trigger)
        if (passive) {
            if (!prefs.passiveCapture || PanelHost.isShowing) return
            if (trigger == "APP_SWITCH" || trigger == "UNLOCK") {
                if (now - lastPassiveAt < PASSIVE_GAP) return
                lastPassiveAt = now
            }
        }
        val due = now + delay
        val p = pendingRun
        if (p != null) {
            if (fallback != null) pendingFallback = fallback
            if (!passive) {
                if (isPassive(pendingTrigger)) pendingTrigger = trigger
                if (due > pendingDue) {
                    main.removeCallbacks(p)
                    pendingDue = due
                    main.postAtTime(p, due)
                }
            }
            return
        }
        pendingTrigger = trigger
        pendingFallback = fallback
        pendingDue = maxOf(due, lastProbeAt + MIN_GAP)
        val r = Runnable {
            pendingRun = null
            val t = pendingTrigger
            val f = pendingFallback
            pendingFallback = null
            // 读的那一瞬间当前窗口会失去焦点：正在用实体键盘打字时不做补漏，免得吞掉按键
            if (isPassive(t) && SystemClock.uptimeMillis() - lastKeyAt < 1000) return@Runnable
            captureClipboard(t, f)
        }
        pendingRun = r
        main.postAtTime(r, pendingDue)
    }

    fun captureClipboard(trigger: String, fallback: String? = null) {
        val pkg = currentPkg
        // 自己的窗口正好有焦点（主界面、可获焦的面板）时能直接读
        ClipReader.captureResult(this)?.let { Diag.log(trigger, pkg, it); return }
        if (probe != null) return
        lastProbeAt = SystemClock.uptimeMillis()
        fun useFallback(result: String) {
            if (fallback != null && record(fallback)) Diag.log(trigger, pkg, Diag.FALLBACK, Diag.resultName(result))
            else Diag.log(trigger, pkg, result, if (result == Diag.EMPTY) ClipReader.emptyReason(this) else null)
        }
        val view = object : View(this) {
            var done = false
            var focused = false

            /** how：这次是靠什么读到的，写进诊断；last：读不到就放弃 */
            fun attempt(how: String, last: Boolean) {
                if (done) return
                val r = ClipReader.captureResult(this@ClipService)
                if (r != null && r != Diag.EMPTY) {
                    done = true
                    Diag.log(trigger, pkg, r, how)
                    removeLater()
                } else if (last) {
                    done = true
                    useFallback(if (focused) Diag.EMPTY else Diag.NO_FOCUS)
                    removeLater()
                }
            }

            /** 不能在焦点回调里当场移除窗口：系统还在用这个窗口处理焦点变化，当场移除会让整个进程崩溃 */
            fun removeLater() {
                val self: View = this
                main.post { if (probe === self) removeProbe() }
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) { focused = true; attempt("焦点回调", true) }
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
            // 不能把窗口 alpha 设成 0：系统把完全透明的窗口当作“不可见”，不可见的窗口拿不到输入焦点。
            // 这个 View 什么都不画，本身就是透明的。
            windowAnimations = 0
        }
        probe = view
        runCatching { wm.addView(view, lp) }.onFailure {
            probe = null
            useFallback(Diag.ADD_FAIL)
            return
        }
        // 焦点回调在有的系统上不来或来得晚，而剪贴板的放行看的是窗口管理器认定的焦点：隔一会儿直接试读
        for (t in longArrayOf(120, 300)) main.postDelayed({ if (probe === view) view.attempt("轮询 ${t}ms", false) }, t)
        main.postDelayed({ if (probe === view) view.attempt("超时前", true) }, 600)
    }

    private fun removeProbe() {
 val v = probe ?: return
        probe = null
        runCatching { wm.removeViewImmediate(v) }
    }

    private fun record(text: String): Boolean = ClipStore.add(text, prefs.maxItems)
}

fun Context.isClipServiceOn(): Boolean = ClipService.instance != null
