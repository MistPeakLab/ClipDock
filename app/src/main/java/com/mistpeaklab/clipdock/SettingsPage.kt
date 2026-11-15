package com.mistpeaklab.clipdock

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.data.ItemRepo

// ---------------- 我的（设置首页） ----------------

@Composable
fun MePage(tick: Int, open: (Sub) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val fv by Features.version.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf(prefs.newFeaturesHintPending) }
    val level = remember(tick) { abilityLevel(ctx) }
    PageColumn {
        Text(tr("我的", "Me"), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        if (hint) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.10f)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("0.6 加了不少新功能（依次粘贴、连续收集、隐藏内容、分组…）。现在是简洁模式，界面和以前一样；想用的话可以打开。",
                        "Version 0.6 adds many features (paste queue, collect mode, hidden items, groups…). You're in Simple mode; turn them on if you like."),
                    Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp,
                )
                Column {
                    TextButton({ prefs.newFeaturesHintPending = false; hint = false; open(Sub.Features) }) { Text(tr("看看", "Show")) }
                    TextButton({ prefs.newFeaturesHintPending = false; hint = false }) { Text(tr("不用", "Dismiss")) }
                }
            }
        }
        ModeCard(fv) { open(Sub.Features) }
        Group(tr("基本", "Basics")) {
            Entry(tr("权限与模式", "Permissions"), when (level) {
                2 -> tr("增强模式：后台记录、快捷键唤起", "Enhanced: background recording, hotkey")
                1 -> tr("基础模式：悬浮面板，打开时记录", "Basic: floating panel, records when opened")
                else -> tr("仅主界面：打开随贴时记录", "App only: records when ClipDock is opened")
            }, if (level == 2) null else "!") { open(Sub.Permissions) }
            Entry(tr("唤起与面板", "Hotkey & panel"), tr("快捷键、关闭方式、面板大小", "Hotkey, how the panel closes, size")) { open(Sub.Panel) }
            Entry(tr("粘贴", "Pasting"), tr("直接粘贴、清理格式、多条合并", "Paste directly, clean up, merging")) { open(Sub.Paste) }
            Entry(tr("记录", "History"), tr("保留条数、补漏检测、清空", "How many to keep, catch-up checks, clear")) { open(Sub.Record) }
        }
        Group(tr("数据与安全", "Data & security")) {
            Entry(tr("隐私", "Privacy"), tr("隐藏内容、不记录的应用、验证码", "Hidden items, apps to skip, codes")) { open(Sub.Privacy) }
            Entry(tr("备份与导入导出", "Backup & export"), tr("导出、导入、自动备份", "Export, import, automatic backup")) { open(Sub.Backup) }
            if (Features.isOn(ctx, Feature.TRASH)) Entry(tr("回收站", "Trash"), tr("删除的内容保留 %d 天", "Deleted items are kept for %d days", ItemRepo.TRASH_DAYS)) { open(Sub.Trash) }
            Entry(tr("收录诊断", "Diagnostics"), tr("复制了却没记下来？看这里", "Copied but not recorded? Look here")) { open(Sub.Diag) }
        }
        Group(tr("其他", "Other")) {
            Entry(tr("外观与语言", "Look & language"), tr("主题、界面语言", "Theme, language")) { open(Sub.Look) }
        }
        Text(
            tr("随贴 ClipDock %s · MIT 开源 · 本地存储，不联网", "ClipDock %s · MIT licensed · stored on this device, never online",
                remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("") }),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp, 8.dp),
        )
    }
}

/** 简洁 / 全能 / 自定义 */
@Composable
private fun ModeCard(fv: Int, onDetail: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val mode = remember(fv) { prefs.mode }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("功能", "Features"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onDetail) { Text(tr("逐项设置 ›", "Customise ›")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(Mode.SIMPLE, Mode.FULL)) {
                val sel = mode == m
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                        .border(if (sel) 1.5.dp else 0.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { prefs.applyMode(m) }.padding(12.dp),
                ) {
                    Text(m.title, fontWeight = FontWeight.SemiBold, color = if (sel) Accent else MaterialTheme.colorScheme.onSurface)
                    Text(m.desc, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (mode == Mode.CUSTOM) Text(tr("当前：自定义（逐项调整过）", "Currently: custom"), fontSize = 12.sp, color = Accent, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------- 功能开关 ----------------

@Composable
fun FeaturesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val fv by Features.version.collectAsStateWithLifecycle()
    PageColumn {
        SubHeader(tr("功能", "Features"), onBack)
        ModeCard(fv) {}
        Group(tr("逐项开关", "One by one")) {
            Feature.entries.filter { !it.reserved }.forEach { f ->
                key(f, fv) { Toggle(f.title, f.desc, prefs.isOn(f)) { prefs.setFeature(f, it) } }
            }
        }
        Hint(tr("关掉的功能在面板、主界面、菜单里都不出现，也不在后台做对应的计算。随时可以再打开，数据不会丢。",
            "Features you turn off disappear from the panel, screens and menus, and stop running in the background. Turning them back on loses nothing."))
    }
}

// ---------------- 唤起与面板 ----------------

@Composable
fun PanelSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    key(v) {
        PageColumn {
            SubHeader(tr("唤起与面板", "Hotkey & panel"), onBack)
            Group(tr("唤起", "Opening")) {
                Toggle(tr("启用快捷键唤起", "Hotkey opens the panel"), tr("需要增强模式（无障碍服务）", "Needs enhanced mode (accessibility service)"), prefs.hotkeyEnabled) { prefs.hotkeyEnabled = it; v++ }
                Clickable(tr("唤起快捷键", "Hotkey"), tr("Win + V 若被系统占用，可换成 Ctrl + Shift + V 或 Alt + V", "If Win + V is taken, try Ctrl + Shift + V or Alt + V"), prefs.hotkey.label()) { recording = true }
                Toggle(tr("点桌面图标直接唤起悬浮面板", "App icon opens the floating panel"),
                    tr("关闭时打开主界面。长按桌面图标也能选“悬浮面板”", "Otherwise it opens this screen. Long-press the icon for the panel too."), prefs.iconOpensPanel) { prefs.iconOpensPanel = it; v++ }
            }
            Group(tr("关闭方式", "Closing")) {
                Toggle(tr("选中一条后自动收起", "Close after picking an item"), null, prefs.closeOnSelect) { prefs.closeOnSelect = it; v++ }
                Toggle(tr("按 Esc 关闭", "Esc closes it"), null, prefs.closeOnEsc) { prefs.closeOnEsc = it; v++ }
                Toggle(tr("点面板外空白处关闭", "Tap outside closes it"), null, prefs.closeOnOutside) { prefs.closeOnOutside = it; v++ }
                Toggle(tr("再按一次唤起快捷键关闭", "Hotkey again closes it"), null, prefs.closeOnHotkey) { prefs.closeOnHotkey = it; v++ }
                Toggle(tr("切换到其他应用时关闭", "Close when switching apps"), null, prefs.closeOnAppSwitch) { prefs.closeOnAppSwitch = it; v++ }
                Hint(tr("全部关掉后，只能点面板右上角的 × 关闭。", "With all of these off, only the × closes the panel."))
            }
            Group(tr("悬浮面板", "Floating panel")) {
                Choice(tr("每条显示行数", "Lines per item"), (1..4).map { tr("%d 行", "%d", it) }, prefs.previewLines - 1) { prefs.previewLines = it + 1; v++ }
                Clickable(tr("面板大小与位置", "Panel size & position"), tr("拖动面板右下角可缩放，拖动标题栏可移动；手机上拖动标题栏调整高度",
                    "Drag the corner to resize and the title bar to move; on phones drag the title bar to change height"), tr("恢复默认", "Reset")) {
                    prefs.resetPanelPosition(); ctx.toast(tr("已恢复默认大小和位置", "Size and position reset"))
                }
            }
            Group(tr("面板快捷键", "Panel keys")) {
                Hint(tr(
                    "直接打字：搜索　Tab：切换标签　↑ ↓：选择　Enter：粘贴　1–9：快选（未搜索时）\nCtrl+A：全选　Ctrl+点击：加选 / 取消　Shift+点击 或 Shift+↑↓：连续选择　长按：加选\nCtrl+V：粘贴到输入框（多条自动合并）　Ctrl+C：只复制　Ctrl+P：收藏　Delete：删除　→：拆开挑选\nEsc：取消选择 → 清空搜索 → 关闭",
                    "Type: search   Tab: switch tabs   ↑ ↓: move   Enter: paste   1–9: quick pick (when not searching)\nCtrl+A: select all   Ctrl+click: add/remove   Shift+click or Shift+↑↓: range   Long-press: add\nCtrl+V: paste into the text field (merges multiple)   Ctrl+C: copy only   Ctrl+P: star   Delete: delete   →: pick part\nEsc: clear selection → clear search → close",
                ))
            }
        }
    }
    if (recording) HotkeyDialog(onDismiss = { recording = false }) { h ->
        prefs.hotkey = h; recording = false; v++
        ctx.toast(tr("已设置为 %s", "Set to %s", h.label()))
    }
}

// ---------------- 粘贴 ----------------

@Composable
fun PasteSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }
    key(v) {
        PageColumn {
            SubHeader(tr("粘贴", "Pasting"), onBack)
            Group(tr("粘贴", "Pasting")) {
                Toggle(tr("Enter / 点击后直接粘贴到输入框", "Enter / tap pastes into the text field"),
                    tr("需要增强模式。关闭时 Enter 和点击只复制；Ctrl+V 与“粘贴”按钮始终直接粘贴，Ctrl+C 始终只复制",
                        "Needs enhanced mode. When off, Enter and tap only copy; Ctrl+V and the Paste button always paste, Ctrl+C always copies"), prefs.autoPaste) { prefs.autoPaste = it; v++ }
                Toggle(tr("粘贴前清理格式", "Clean up before pasting"), tr("去掉零宽字符、行尾空格、多余空行和首尾空白；不改动原记录",
                    "Removes zero-width characters, trailing spaces, extra blank lines; the original is untouched"), prefs.cleanText) { prefs.cleanText = it; v++ }
                Toggle(tr("粘贴动画", "Paste animation"), tr("直接粘贴时，选中的卡片飞向输入框光标", "When pasting directly, the card flies to the cursor"), prefs.pasteAnim) { prefs.pasteAnim = it; v++ }
                Choice(tr("多条合并时的分隔", "Separator when merging"), listOf(tr("换行", "New line"), tr("空一行", "Blank line"), tr("空格", "Space"), tr("直接连接", "Nothing")), prefs.mergeMode) { prefs.mergeMode = it; v++ }
            }
        }
    }
}

// ---------------- 记录 ----------------

@Composable
fun RecordSettingsPage(onBack: () -> Unit, onOpenTrash: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var shrinkTo by remember { mutableStateOf<Int?>(null) }
    var confirmReimport by remember { mutableStateOf(false) }
    val trashOn = Features.isOn(ctx, Feature.TRASH)
    val hasLegacy = remember { ClipStore.hasLegacyBackup() }
    // 滚动位置放在 key(v) 外面：改一项设置会重建整页内容，但不能跳回顶部
    val scroll = rememberScrollState()
    key(v) {
        PageColumn(scroll) {
            SubHeader(tr("记录", "History"), onBack)
            Group(tr("记录", "History")) {
                val opts = Prefs.MAX_ITEMS_OPTIONS
                val cur = prefs.maxItems
                // 以前存下的数不在现在的档位里时也把它列出来，免得没有一项是选中的
                val all = if (cur in opts) opts else (opts + cur).sorted()
                Choice(tr("最多保留条数（收藏的不算）", "Items to keep (favourites don't count)"), all.map { Prefs.maxItemsLabel(it) }, all.indexOf(cur)) {
                    val n = all[it]
                    if (ClipStore.history().count { h -> !h.pinned } > n) shrinkTo = n
                    else { prefs.maxItems = n; ClipStore.applyLimit(n); v++ }
                }
                Hint(tr("单条最多 100 万字。记录的总字数特别大时（合计超过 2000 万字，或 5000 字以上的长文合计超过 1000 万字），最旧的也会移入回收站。",
                    "Up to 1 million characters per item. If everything together gets very large, the oldest items move to the trash."))
                Toggle(tr("补漏检测", "Catch-up checks"), tr("切换应用、解锁屏幕、长按菜单里点了某一项之后，也去看一眼剪贴板，补上没察觉到的复制。需要增强模式。读取的一瞬间当前窗口会短暂失去焦点，若某个游戏因此暂停，可以关掉",
                    "Also checks the clipboard after switching apps, unlocking, or tapping a long-press menu item. Needs enhanced mode. The current window loses focus for an instant; turn this off if a game pauses because of it."), prefs.passiveCapture) { prefs.passiveCapture = it; v++ }
                if (trashOn) Clickable(tr("回收站", "Trash"), tr("删除的、超出上限的记录先放在这里，保留 %d 天", "Deleted and overflowing items are kept here for %d days", ItemRepo.TRASH_DAYS), tr("查看", "Open")) { onOpenTrash() }
                if (hasLegacy) Clickable(tr("升级前的记录", "Records from before 0.6"), tr("升级到 0.6 之前的记录还留着一份。发现升级后少了内容，可以从这里补回来",
                    "A copy of your pre-0.6 history is kept. If something went missing after the upgrade, restore it here."), tr("补回", "Restore")) { confirmReimport = true }
                Clickable(tr("清空全部记录", "Clear all history"), if (trashOn) tr("包括收藏的记录；会移入回收站，常用语保留", "Including starred items; they go to the trash, phrases stay")
                    else tr("包括收藏的记录，不可恢复；常用语保留", "Including starred items, permanently; phrases stay"), tr("清空", "Clear")) { confirmClear = true }
            }
        }
    }
    shrinkTo?.let { n ->
        val over = ClipStore.history().count { !it.pinned } - n
        AlertDialog(
            onDismissRequest = { shrinkTo = null },
            title = { Text(tr("改成最多保留 %s 条？", "Keep at most %s items?", Prefs.maxItemsLabel(n))) },
            text = {
                Text(tr("现在的记录比这个数多，最旧的 %d 条", "You have more than that. The oldest %d ", over) +
                    (if (trashOn) tr("会移入回收站，%d 天内可以恢复。", "will move to the trash (restorable for %d days).", ItemRepo.TRASH_DAYS)
                    else tr("会被删除，不能恢复。", "will be deleted permanently.")) + tr("收藏的和常用语不受影响。", " Favourites are not affected."))
            },
            confirmButton = { TextButton({ prefs.maxItems = n; ClipStore.applyLimit(n); shrinkTo = null; v++ }) { Text(tr("确定", "OK")) } },
            dismissButton = { TextButton({ shrinkTo = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
    if (confirmReimport) AlertDialog(
        onDismissRequest = { confirmReimport = false },
        title = { Text(tr("补回升级前的记录？", "Restore pre-0.6 history?")) },
        text = { Text(tr("会把升级到 0.6 之前的记录重新加进列表，已经在列表里的不会重复。升级之后你删掉的那些也会一起回来。",
            "Adds your pre-0.6 records back; items already in the list are not duplicated. Things you deleted since the upgrade come back too.")) },
        confirmButton = {
            TextButton({
                confirmReimport = false
                ClipStore.reimportLegacy { n ->
                    ctx.toast(when {
                        n < 0 -> tr("没能读到升级前的记录文件", "Couldn't read the old history file")
                        n == 0 -> tr("升级前的记录都已经在列表里了", "Everything is already in the list")
                        else -> tr("补回了 %d 条", "Restored %d items", n)
                    })
                }
            }) { Text(tr("补回", "Restore")) }
        },
        dismissButton = { TextButton({ confirmReimport = false }) { Text(tr("取消", "Cancel")) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(tr("清空全部记录？", "Clear all history?")) },
        text = { Text(if (trashOn) tr("收藏的记录也会一起移入回收站，%d 天内可以恢复。", "Starred items go to the trash too, restorable for %d days.", ItemRepo.TRASH_DAYS)
            else tr("收藏的记录也会被删除，且不可恢复。", "Starred items are deleted too, permanently.")) },
        confirmButton = { TextButton({ ClipStore.clearAll(); confirmClear = false }) { Text(tr("清空", "Clear"), color = Danger) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text(tr("取消", "Cancel")) } },
    )
}

// ---------------- 外观与语言 ----------------

@Composable
fun LookPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }
    key(v) {
        PageColumn {
            SubHeader(tr("外观与语言", "Look & language"), onBack)
            Group(tr("外观", "Look")) {
                Choice(tr("主题", "Theme"), listOf(tr("跟随系统", "System"), tr("浅色", "Light"), tr("深色", "Dark")), prefs.theme) {
                    prefs.theme = it; (ctx as? Activity)?.recreate()
                }
            }
            Group(tr("语言", "Language")) {
                Choice(tr("界面语言", "Language"), listOf(tr("跟随系统", "System"), "中文", "English"), prefs.language) {
                    prefs.language = it
                    L.init(ctx, it)
                    (ctx as? Activity)?.recreate()
                }
            }
        }
    }
}

// ---------------- 快捷键录制 ----------------

@Composable
private fun HotkeyDialog(onDismiss: () -> Unit, onSet: (Hotkey) -> Unit) {
    var tip by remember { mutableStateOf(tr("请在实体键盘上按下组合键\n至少包含 Win / Ctrl / Alt 之一", "Press a key combination on the keyboard\nIt must include Win, Ctrl or Alt")) }
    val fr = remember { FocusRequester() }
    DisposableEffect(Unit) {
        ClipService.recordingHotkey = true
        onDispose { ClipService.recordingHotkey = false }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp)
                .focusRequester(fr).focusable()
                .onPreviewKeyEvent { ke ->
                    val e = ke.nativeKeyEvent
                    if (e.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                    if (Hotkey.isModifierKey(e.keyCode)) return@onPreviewKeyEvent true
                    val mods = Hotkey.modsOf(e)
                    if (mods and (Hotkey.CTRL or Hotkey.ALT or Hotkey.META) == 0) {
                        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK) { onDismiss(); return@onPreviewKeyEvent true }
                        tip = tr("需要包含 Win / Ctrl / Alt，请重新按", "It must include Win, Ctrl or Alt. Try again.")
                        return@onPreviewKeyEvent true
                    }
                    onSet(Hotkey(e.keyCode, mods)); true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tr("设置唤起快捷键", "Set the hotkey"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Text(tip, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton({ onSet(Hotkey(KeyEvent.KEYCODE_V, Hotkey.META)) }) { Text(tr("恢复默认 Win + V", "Reset to Win + V")) }
                Spacer(Modifier.width(8.dp))
                TextButton(onDismiss) { Text(tr("取消", "Cancel")) }
            }
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
}

// ---------------- 组件 ----------------

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(title, fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
fun RowBase(title: String, sub: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun Toggle(title: String, sub: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    RowBase(title, sub, if (enabled) ({ onChange(!value) }) else null) { Switch(value, onChange, enabled = enabled) }

@Composable
fun Clickable(title: String, sub: String?, value: String, onClick: () -> Unit) =
    RowBase(title, sub, onClick) {
        Text(value, fontSize = 13.sp, color = Accent, modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 5.dp))
    }

/** 进入下一级页面的一行；badge 为 "!" 时显示成提醒色 */
@Composable
fun Entry(title: String, sub: String?, badge: String? = null, onClick: () -> Unit) =
    RowBase(title, sub, onClick) {
        if (badge != null) Text(badge, color = Warn, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

/** 几选一。选项放得下就排在标题右边；放不下（手机竖屏、选项多）就另起一行，可以左右滑 */
@Composable
fun Choice(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val chips: @Composable () -> Unit = {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).padding(3.dp)) {
            options.forEachIndexed { i, o ->
                val sel = i == selected
                Text(
                    o, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    color = if (sel) Color.White else cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 粗略估一下这一行要多宽：汉字约 14dp 一个、数字字母约 8dp，每个选项左右留白 20dp，再加标题和边距
        val need = 200 + options.sumOf { o -> 20 + o.fold(0) { w, c -> w + if (c.code > 0x2E7F) 14 else 8 } }
        if (maxWidth >= need.dp) RowBase(title, null, null) { chips() }
        else Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) { chips() }
        }
    }
}

@Composable
fun Hint(t: String) =
    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
