package com.mistpeaklab.clipdock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * “存进文库”的小弹窗（透明 Activity，中间一张卡片）。三个来源：
 * - 在任意应用里选中文字 › 存进文库（清单里的 activity-alias，文库功能打开时才启用）；
 * - 分享文字给随贴（文库打开时由 [SaveTextActivity] 转过来）：先选“存为记录 / 存进文库”；
 * - 悬浮面板里条目菜单的“存进文库”（带 [EXTRA_TEXT]）。
 */
class LibrarySaveActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHOOSE = "choose"
        const val EXTRA_FROM = "from"
        private const val ALIAS = "com.mistpeaklab.clipdock.SaveToLibraryAlias"

        fun open(ctx: Context, text: String) {
            ctx.startActivity(Intent(ctx, LibrarySaveActivity::class.java).putExtra(EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 选中文字菜单里的“存进文库”跟着文库功能开关：关着时菜单里不出现 */
        fun syncAlias(ctx: Context) {
            val on = Features.isOn(ctx, Feature.LIBRARY)
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val cn = ComponentName(ctx.packageName, ALIAS)
            runCatching {
                if (ctx.packageManager.getComponentEnabledSetting(cn) != want) {
                    ctx.packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClipStore.init(this)
        LibraryStore.init(this)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> intent?.getCharSequenceExtra(EXTRA_TEXT)
        }?.toString()
        if (text.isNullOrBlank()) {
            toast(tr("没有可以保存的文字", "There is no text to save"))
            finish(); return
        }
        val choose = intent?.getBooleanExtra(EXTRA_CHOOSE, false) == true
        val from = intent?.getStringExtra(EXTRA_FROM)
        setContent {
            AppTheme {
                var lib by remember { mutableStateOf(!choose) }
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }.imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.padding(16.dp).widthIn(max = 480.dp).clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(remember { MutableInteractionSource() }, null) {},
                    ) {
                        if (lib) SaveToLibraryForm(text) { finish() }
                        else ChooseTarget(text, onRecord = { saveAsRecord(text, from); finish() }, onLibrary = { lib = true }, onCancel = { finish() })
                    }
                }
            }
        }
    }

    private fun saveAsRecord(s: String, from: String?) {
        ClipStore.awaitLoaded()
        val added = ClipStore.add(s, Prefs(this).maxItems, fromClipboard = false, sourcePkg = from)
        ClipStore.flush()
        Diag.log("SHARE", from, if (added) Diag.NEW else Diag.DUP, tr("%d 字", "%d chars", s.length))
        toast(if (added) tr("已存到随贴", "Saved to ClipDock") else tr("随贴里已经有这条了", "ClipDock already has this one"))
    }
}

@Composable
private fun ChooseTarget(text: String, onRecord: () -> Unit, onLibrary: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("存到随贴", "Save to ClipDock"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(text.take(200), maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).padding(10.dp))
        Target(R.drawable.ic_gen_history, tr("存为记录", "Save to history"), tr("和复制的内容放在一起，面板里点一下就能贴", "Alongside what you copy; one tap to paste"), onRecord)
        Target(R.drawable.ic_book, tr("存进文库", "Save to library"), tr("长文字、摘抄放这里，用时挑其中一段贴", "For long texts; paste just the part you need"), onLibrary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onCancel) { Text(tr("取消", "Cancel")) } }
    }
}

@Composable
private fun Target(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}
