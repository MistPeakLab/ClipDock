package com.mistpeaklab.clipdock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager

/** 读写系统剪贴板 */
object ClipReader {
    fun read(ctx: Context): String? = runCatching {
 val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
 if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(ctx)?.toString()
    }.getOrNull()

    /**
     * 读当前剪贴板并记录。只有在本应用拥有焦点（前台）时才读得到。
     * @return [Diag.NEW] / [Diag.DUP] / [Diag.EMPTY]；读不到返回 null
     */
    fun captureResult(ctx: Context): String? {
        val t = read(ctx) ?: return null
        if (t.isBlank()) return Diag.EMPTY
        return if (ClipStore.add(t, Prefs(ctx).maxItems)) Diag.NEW else Diag.DUP
    }

    /** 本应用自己的窗口在前台时读取并记录，结果写进诊断 */
    fun capture(ctx: Context, trigger: String): Boolean {
        val r = captureResult(ctx)
        val empty = r == null || r == Diag.EMPTY
        Diag.log(trigger, null, r ?: Diag.EMPTY, if (empty) emptyReason(ctx) else null)
        return !empty
    }

    /** 没读到文字时，说清楚是哪一种：真的空、系统没放行，还是里面放的不是文字（图片、文件） */
    fun emptyReason(ctx: Context): String = runCatching {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        when {
            clip == null || clip.itemCount == 0 -> "剪贴板是空的，或系统没放行"
            else -> "里面不是文字：" + (0 until clip.description.mimeTypeCount).joinToString { clip.description.getMimeType(it) }
        }
    }.getOrDefault("读取时出错")

    fun write(ctx: Context, text: String) {
 ClipStore.markPicked(text)
 ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("随贴", text))
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

    /** 写入剪贴板；需要时直接粘贴到当前输入框（增强模式），否则提示按 Ctrl+V */
    private fun act(ctx: Context, prefs: Prefs, text: String, mode: Int, cards: () -> List<FlyCard>) {
        val t = TextTools.prepare(prefs, text)
        ClipReader.write(ctx, t)
        val paste = when (mode) { MODE_PASTE -> true; MODE_COPY -> false; else -> prefs.autoPaste }
 if (!paste) return
 val svc = ClipService.instance
        if (svc != null) svc.pasteIntoFocusedLater(t, if (prefs.pasteAnim) cards() else emptyList())
        else android.widget.Toast.makeText(ctx, "已复制，按 Ctrl+V 粘贴（开启增强模式后可直接粘贴）", android.widget.Toast.LENGTH_SHORT).show()
    }
}
