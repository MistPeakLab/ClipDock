package com.mistpeaklab.clipdock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 不依赖无障碍的两条收录入口，没有界面，存完就退出：
 * - 在任意应用里选中文字，菜单里点“存到随贴”（系统的文字处理菜单）；
 * - 在任意应用里“分享”一段文字给随贴。
 */
class SaveTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (trigger, text) = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> "PROCESS_TEXT" to intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> "SHARE" to intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> "" to null
        }
        val s = text?.toString()
        if (s.isNullOrBlank()) {
            Toast.makeText(this, tr("没有可以保存的文字", "There is no text to save"), Toast.LENGTH_SHORT).show()
        } else {
            val from = callerPackage()
            ClipStore.init(this)
            // 这里没有界面，存完就退出，进程随时可能被系统回收，所以要当场确认：
            // 先等记录读完（才知道是不是已经有了），存进去以后再等它真的写好
            ClipStore.awaitLoaded()
            val added = ClipStore.add(s, Prefs(this).maxItems, fromClipboard = false, sourcePkg = from)
            ClipStore.flush()
            Diag.log(trigger, from, if (added) Diag.NEW else Diag.DUP, tr("%d 字", "%d chars", s.length))
            Toast.makeText(
                this,
                if (added) tr("已存到随贴", "Saved to ClipDock") else tr("随贴里已经有这条了", "ClipDock already has this one"),
                Toast.LENGTH_SHORT,
            ).show()
        }
        finish()
    }

    /** 是从哪个应用发过来的。经过系统的“分享到…”选择框时拿到的是选择框自己，那种不算 */
    private fun callerPackage(): String? = referrer?.takeIf { it.scheme == "android-app" }?.host
        ?.takeUnless { it == "android" || it == packageName || it.endsWith("intentresolver") || it.endsWith("chooser") }
}
