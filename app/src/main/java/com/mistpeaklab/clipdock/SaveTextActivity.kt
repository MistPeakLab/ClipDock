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
            Toast.makeText(this, "没有可以保存的文字", Toast.LENGTH_SHORT).show()
        } else {
            ClipStore.init(this)
            val added = ClipStore.add(s, Prefs(this).maxItems, fromClipboard = false)
            Diag.log(trigger, referrer?.host, if (added) Diag.NEW else Diag.DUP, "${s.length} 字")
            Toast.makeText(this, if (added) "已存到随贴" else "随贴里已经有这条了", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
