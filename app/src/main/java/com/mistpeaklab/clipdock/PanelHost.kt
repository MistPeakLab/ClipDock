package com.mistpeaklab.clipdock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast

/** 读写系统剪贴板 */
object ClipReader {
    /** 剪贴板里的文字，以及来源应用有没有把它标记成“敏感”（Android 13 起密码类应用会这么做） */
    private class Clip(val text: String, val sensitive: Boolean)

    private fun readClip(ctx: Context): Clip? = runCatching {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
        val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        Clip(text, sensitive)
    }.getOrNull()

    fun read(ctx: Context): String? = readClip(ctx)?.text

    /**
     * 读当前剪贴板并记录。只有在本应用拥有焦点（前台）时才读得到。
     * @return [Diag.NEW] / [Diag.DUP] / [Diag.EMPTY] / [Diag.SKIPPED]；读不到返回 null
     */
    fun captureResult(ctx: Context, sourcePkg: String? = null): String? {
        ClipStore.init(ctx)
        val c = readClip(ctx) ?: return null
        if (c.text.isBlank()) return Diag.EMPTY
        val prefs = Prefs(ctx)
        if (prefs.skipSensitiveSources && (c.sensitive || sourcePkg in AppInfo.PASSWORD_MANAGERS)) return Diag.SKIPPED
        return if (ClipStore.add(c.text, prefs.maxItems, sourcePkg = sourcePkg)) Diag.NEW else Diag.DUP
    }

    /** 本应用自己的窗口在前台时读取并记录，结果写进诊断 */
    fun capture(ctx: Context, trigger: String): Boolean {
        val r = captureResult(ctx)
        val empty = r == null || r == Diag.EMPTY
        Diag.log(trigger, null, r ?: Diag.EMPTY, if (empty) emptyReason(ctx) else null)
        return !empty && r != Diag.SKIPPED
    }

    /** 没读到文字时，说清楚是哪一种：真的空、系统没放行，还是里面放的不是文字（图片、文件） */
    fun emptyReason(ctx: Context): String = runCatching {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        when {
            clip == null || clip.itemCount == 0 -> tr("剪贴板是空的，或系统没放行", "The clipboard is empty or was blocked")
            else -> tr("里面不是文字：", "Not text: ") + (0 until clip.description.mimeTypeCount).joinToString { clip.description.getMimeType(it) }
        }
    }.getOrDefault(tr("读取时出错", "Error while reading"))

    fun write(ctx: Context, text: String) {
        ClipStore.markPicked(text)
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tr("随贴", "ClipDock"), text))
    }
}

/**
 * 悬浮面板的宿主，按可用能力选择窗口类型：
 * - 无障碍服务开启：无障碍悬浮层，不抢焦点，键盘操作由服务转发；
 * - 只有“显示在其他应用上层”权限：普通悬浮窗，可获得焦点（因此打开时能直接读剪贴板，也能响应键盘）。
 */
object PanelHost {
    private var panel: PanelController? = null

    val isShowing get() = panel != null

    fun canShow(ctx: Context) = ClipService.instance != null || Settings.canDrawOverlays(ctx)

    fun toggle(ctx: Context) { if (panel != null) hide() else show(ctx) }

    fun show(ctx: Context): Boolean {
        if (panel != null) return true
        ClipStore.init(ctx)
        val svc = ClipService.instance
        val host: Context
        val type: Int
        val focusable: Boolean
        when {
            svc != null -> { host = svc; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; focusable = false }
            Settings.canDrawOverlays(ctx) -> { host = ctx.applicationContext; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; focusable = true }
            else -> return false
        }
        svc?.captureClipboard("PANEL_OPEN")
        val prefs = Prefs(host)
        panel = PanelController(
            host, host.getSystemService(WindowManager::class.java), prefs, type, focusable,
            onAct = { text, mode, cards -> act(host, prefs, text, mode, cards) },
            onFocused = { ClipReader.capture(host, "PANEL_OPEN") },
            onDismiss = { panel = null },
        ).also { it.show() }
        return true
    }

    fun hide() { panel?.dismiss() }

    fun handleKey(e: KeyEvent): Boolean = panel?.handleKey(e) ?: false

    fun handleModifier(e: KeyEvent) { panel?.handleModifier(e) }

    /** 不经过面板直接送出一段文字（依次粘贴、验证身份之后），没有飞行动画 */
    fun deliver(ctx: Context, text: String, mode: Int) {
        val c = ClipService.instance ?: ctx.applicationContext
        act(c, Prefs(c), text, mode) { emptyList() }
    }

    /** 写入剪贴板；需要时直接粘贴到当前输入框（增强模式），否则提示按 Ctrl+V */
    private fun act(ctx: Context, prefs: Prefs, text: String, mode: Int, cards: () -> List<FlyCard>) {
        val t = TextTools.prepare(prefs, text)
        ClipReader.write(ctx, t)
        val paste = when (mode) { MODE_PASTE -> true; MODE_COPY -> false; else -> prefs.autoPaste }
        if (!paste) return
        val svc = ClipService.instance
        if (svc != null) svc.pasteIntoFocusedLater(t, if (prefs.pasteAnim) cards() else emptyList())
        else Toast.makeText(ctx, tr("已复制，按 Ctrl+V 或长按输入框粘贴（开启增强模式后可直接粘贴）", "Copied. Paste with Ctrl+V or long-press the field (enhanced mode pastes directly)"), Toast.LENGTH_SHORT).show()
    }
}
