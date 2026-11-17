package com.mistpeaklab.clipdock

import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
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
            }, if (level == 2) null else "!", icon = R.drawable.ic_gen_shield) { open(Sub.Permissions) }
            Entry(tr("唤起与面板", "Hotkey & panel"), tr("快捷键、关闭方式、面板大小", "Hotkey, how the panel closes, size"), icon = R.drawable.ic_gen_keyboard) { open(Sub.Panel) }
            Entry(tr("悬浮球", "Floating bubble"), remember(tick) { Prefs(ctx).let { p -> if (!p.floatEnabled) tr("未开启 · 看板娘卡卡常驻屏幕边", "Off · Kaka on the edge of your screen") else if (p.capturePaused) tr("已开启 · 记录已暂停", "On · recording paused") else tr("已开启", "On") } }, icon = R.drawable.ic_gen_pick) { open(Sub.Float) }
            Entry(tr("通知栏与小部件", "Notification & widget"), remember(tick) { if (Prefs(ctx).notifEnabled) tr("常驻通知已开启", "Notification on") else tr("常驻通知、桌面小部件", "Persistent notification, home-screen widget") }, icon = R.drawable.ic_gen_grid) { open(Sub.Notify) }
            Entry(tr("粘贴", "Pasting"), tr("直接粘贴、清理格式、多条合并", "Paste directly, clean up, merging"), icon = R.drawable.ic_gen_copy) { open(Sub.Paste) }
            Entry(tr("记录", "History"), tr("保留条数、补漏检测、清空", "How many to keep, catch-up checks, clear"), icon = R.drawable.ic_gen_history) { open(Sub.Record) }
        }
        Group(tr("数据与安全", "Data & security")) {
            Entry(tr("隐私", "Privacy"), tr("隐藏内容、不记录的应用、验证码", "Hidden items, apps to skip, codes"), icon = R.drawable.ic_gen_lock) { open(Sub.Privacy) }
            Entry(tr("备份与导入导出", "Backup & export"), tr("导出、导入、自动备份", "Export, import, automatic backup"), icon = R.drawable.ic_gen_download) { open(Sub.Backup) }
            if (Features.isOn(ctx, Feature.TRASH)) Entry(tr("回收站", "Trash"), tr("删除的内容保留 %d 天", "Deleted items are kept for %d days", ItemRepo.TRASH_DAYS), icon = R.drawable.ic_gen_trash) { open(Sub.Trash) }
            Entry(tr("收录诊断", "Diagnostics"), tr("复制了却没记下来？看这里", "Copied but not recorded? Look here"), icon = R.drawable.ic_gen_gear) { open(Sub.Diag) }
        }
        Group(tr("其他", "Other")) {
            Entry(tr("外观与语言", "Look & language"), tr("主题、界面语言", "Theme, language"), icon = R.drawable.ic_gen_palette) { open(Sub.Look) }
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
private fun ModeCard(fv: Int, onDetail: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val mode = remember(fv) { prefs.mode }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("功能", "Features"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onDetail != null) TextButton(onDetail) { Text(tr("逐项设置 ›", "Customise ›")) }
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

/** 功能开关按用途分组显示；没列到的放进“其他” */
private val FEATURE_GROUPS: List<Triple<String, String, List<Feature>>> = listOf(
    Triple("收录", "Capture", listOf(Feature.COLLECT, Feature.SOURCE_APP, Feature.SENSITIVE_DETECT)),
    Triple("查找", "Find", listOf(Feature.PINYIN_SEARCH, Feature.CONTENT_KIND)),
    Triple("粘贴", "Paste", listOf(Feature.PASTE_QUEUE, Feature.TRANSFORM, Feature.SPLIT_MERGE)),
    Triple("整理", "Organise", listOf(Feature.NOTE, Feature.GROUP, Feature.TRASH, Feature.HIDE)),
)

private fun featureIcon(f: Feature) = when (f) {
    Feature.PASTE_QUEUE -> R.drawable.ic_gen_queue
    Feature.COLLECT -> R.drawable.ic_gen_add
    Feature.CONTENT_KIND -> R.drawable.ic_gen_grid
    Feature.TRANSFORM -> R.drawable.ic_gen_transform
    Feature.SOURCE_APP -> R.drawable.ic_gen_open
    Feature.SPLIT_MERGE -> R.drawable.ic_gen_list
    Feature.NOTE -> R.drawable.ic_gen_note
    Feature.GROUP -> R.drawable.ic_gen_folder
    Feature.TRASH -> R.drawable.ic_gen_trash
    Feature.PINYIN_SEARCH -> R.drawable.ic_gen_search
    Feature.SENSITIVE_DETECT -> R.drawable.ic_gen_shield
    Feature.HIDE -> R.drawable.ic_gen_lock
    else -> R.drawable.ic_gen_gear
}

@Composable
fun FeaturesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val fv by Features.version.collectAsStateWithLifecycle()
    PageColumn {
        SubHeader(tr("功能", "Features"), onBack, R.drawable.ic_gen_grid,
            tr("按需打开。关掉的功能在面板、主界面、菜单里都不出现，也不在后台运行；随时再打开，数据不会丢。",
                "Turn on what you need. Features that are off disappear everywhere and stop running; turning them back on loses nothing."))
        ModeCard(fv)
        val all = Feature.entries.filter { !it.reserved }
        val listed = FEATURE_GROUPS.flatMap { it.third }.toSet()
        val groups = FEATURE_GROUPS + listOfNotNull(all.filter { it !in listed }.takeIf { it.isNotEmpty() }?.let { Triple("其他", "Other", it) })
        groups.forEach { (zh, en, fs) ->
            val shown = fs.filter { it in all }
            if (shown.isEmpty()) return@forEach
            Group(tr(zh, en)) {
                shown.forEach { f -> key(f, fv) { Toggle(f.title, f.desc, prefs.isOn(f), icon = featureIcon(f)) { prefs.setFeature(f, it) } } }
            }
        }
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
            SubHeader(tr("唤起与面板", "Hotkey & panel"), onBack, R.drawable.ic_gen_keyboard,
                tr("怎么叫出悬浮面板、怎么关掉，以及面板里能用的快捷键。", "How to open and close the floating panel, and the keys that work inside it."))
            Group(tr("唤起", "Opening")) {
                Toggle(tr("启用快捷键唤起", "Hotkey opens the panel"), tr("需要增强模式（无障碍服务）", "Needs enhanced mode (accessibility service)"),
                    prefs.hotkeyEnabled, icon = R.drawable.ic_gen_keyboard) { prefs.hotkeyEnabled = it; v++ }
                RowBase(tr("唤起快捷键", "Hotkey"), tr("点一下重新录制。Win + V 被系统占用时，可换成 Ctrl + Shift + V 或 Alt + V",
                    "Tap to record a new one. If Win + V is taken, try Ctrl + Shift + V or Alt + V"), { recording = true }, R.drawable.ic_gen_edit) {
                    KeyCaps(prefs.hotkey.label())
                }
                Toggle(tr("点桌面图标直接唤起悬浮面板", "App icon opens the floating panel"),
                    tr("关闭时打开主界面。长按桌面图标也能选“悬浮面板”", "Otherwise it opens this screen. Long-press the icon for the panel too."),
                    prefs.iconOpensPanel, icon = R.drawable.ic_gen_open) { prefs.iconOpensPanel = it; v++ }
            }
            Group(tr("关闭方式", "Closing")) {
                Toggle(tr("选中一条后自动收起", "Close after picking an item"), null, prefs.closeOnSelect, icon = R.drawable.ic_gen_check) { prefs.closeOnSelect = it; v++ }
                Toggle(tr("按 Esc 关闭", "Esc closes it"), null, prefs.closeOnEsc, icon = R.drawable.ic_gen_keyboard) { prefs.closeOnEsc = it; v++ }
                Toggle(tr("点面板外空白处关闭", "Tap outside closes it"), null, prefs.closeOnOutside, icon = R.drawable.ic_gen_close) { prefs.closeOnOutside = it; v++ }
                Toggle(tr("再按一次唤起快捷键关闭", "Hotkey again closes it"), null, prefs.closeOnHotkey, icon = R.drawable.ic_gen_swap) { prefs.closeOnHotkey = it; v++ }
                Toggle(tr("切换到其他应用时关闭", "Close when switching apps"), null, prefs.closeOnAppSwitch, icon = R.drawable.ic_gen_grid) { prefs.closeOnAppSwitch = it; v++ }
                Hint(tr("全部关掉后，只能点面板右上角的 × 关闭。", "With all of these off, only the × closes the panel."))
            }
            Group(tr("悬浮面板", "Floating panel")) {
                Choice(tr("每条显示行数", "Lines per item"), (1..4).map { tr("%d 行", "%d", it) }, prefs.previewLines - 1, icon = R.drawable.ic_gen_list) { prefs.previewLines = it + 1; v++ }
                Clickable(tr("面板大小与位置", "Panel size & position"), tr("拖动顶部把手可移动，拖动右下角可缩放；手机和平板分别记住",
                    "Drag the top handle to move and the corner to resize; phone and tablet are remembered separately"), tr("恢复默认", "Reset"), icon = R.drawable.ic_gen_grid) {
                    prefs.resetPanelPosition(); ctx.toast(tr("已恢复默认大小和位置", "Size and position reset"))
                }
            }
            Group(tr("面板里的快捷键", "Keys in the panel")) {
                ShortcutTable(listOf(
                    tr("直接打字", "Type") to tr("搜索", "Search"),
                    "Enter" to tr("粘贴选中的一条", "Paste"),
                    "↑ ↓" to tr("上下选择", "Move"),
                    "1–9" to tr("快选第几条（未搜索时）", "Quick pick (not searching)"),
                    "Tab" to tr("切换 最近 / 收藏 / 分组", "Switch tabs"),
                    "→" to tr("拆开挑选", "Pick part"),
                    "Ctrl + A" to tr("全选", "Select all"),
                    "Shift + ↑↓" to tr("连续选择", "Select a range"),
                    "Ctrl + V" to tr("粘贴到输入框（多条合并）", "Paste (merges multiple)"),
                    "Ctrl + C" to tr("只复制", "Copy only"),
                    "Ctrl + P" to tr("收藏", "Star"),
                    "Delete" to tr("删除", "Delete"),
                    "Ctrl + Z" to tr("撤销删除", "Undo delete"),
                    "Esc" to tr("取消选择 → 清空搜索 → 关闭", "Clear selection → search → close"),
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
            SubHeader(tr("粘贴", "Pasting"), onBack, R.drawable.ic_gen_copy,
                tr("选中一条之后，随贴怎么把它贴进去。", "What ClipDock does when you pick an item."))
            Group(tr("粘贴", "Pasting")) {
                Toggle(tr("Enter / 点击后直接粘贴到输入框", "Enter / tap pastes into the text field"),
                    tr("需要增强模式。关闭时 Enter 和点击只复制；Ctrl+V 与“粘贴”按钮始终直接粘贴，Ctrl+C 始终只复制",
                        "Needs enhanced mode. When off, Enter and tap only copy; Ctrl+V and the Paste button always paste, Ctrl+C always copies"),
                    prefs.autoPaste, icon = R.drawable.ic_gen_copy) { prefs.autoPaste = it; v++ }
                Toggle(tr("粘贴前清理格式", "Clean up before pasting"), tr("去掉零宽字符、行尾空格、多余空行和首尾空白；不改动原记录",
                    "Removes zero-width characters, trailing spaces, extra blank lines; the original is untouched"),
                    prefs.cleanText, icon = R.drawable.ic_gen_tg_clean) { prefs.cleanText = it; v++ }
                Toggle(tr("粘贴动画", "Paste animation"), tr("直接粘贴时，选中的卡片飞向输入框光标", "When pasting directly, the card flies to the cursor"),
                    prefs.pasteAnim, icon = R.drawable.ic_gen_transform) { prefs.pasteAnim = it; v++ }
            }
            Group(tr("多条合并", "Merging")) {
                Choice(tr("合并时的分隔", "Separator"), listOf(tr("换行", "New line"), tr("空一行", "Blank line"), tr("空格", "Space"), tr("直接连接", "Nothing")),
                    prefs.mergeMode, icon = R.drawable.ic_gen_tf_join_lines) { prefs.mergeMode = it; v++ }
                // 用两条示例直接看出效果
                val a = tr("第一条", "first"); val b = tr("第二条", "second")
                val sample = TextTools.join(listOf(a, b), prefs.mergeMode)
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                    Text(tr("效果", "Result"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                    Text(sample.replace(" ", "␣"), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
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
            SubHeader(tr("记录", "History"), onBack, R.drawable.ic_gen_history,
                tr("记多少条、怎么补上没察觉到的复制、怎么清空。", "How much to keep, catching missed copies, clearing."))
            Group(tr("记录", "History")) {
                val opts = Prefs.MAX_ITEMS_OPTIONS
                val cur = prefs.maxItems
                // 以前存下的数不在现在的档位里时也把它列出来，免得没有一项是选中的
                val all = if (cur in opts) opts else (opts + cur).sorted()
                Choice(tr("最多保留条数（收藏的不算）", "Items to keep (favourites don't count)"), all.map { Prefs.maxItemsLabel(it) }, all.indexOf(cur), icon = R.drawable.ic_gen_list) {
                    val n = all[it]
                    if (ClipStore.history().count { h -> !h.pinned } > n) shrinkTo = n
                    else { prefs.maxItems = n; ClipStore.applyLimit(n); v++ }
                }
                Hint(tr("单条最多 100 万字。记录的总字数特别大时（合计超过 2000 万字，或 5000 字以上的长文合计超过 1000 万字），最旧的也会移入回收站。",
                    "Up to 1 million characters per item. If everything together gets very large, the oldest items move to the trash."))
                Toggle(tr("补漏检测", "Catch-up checks"), tr("切换应用、解锁屏幕、长按菜单里点了某一项之后，也去看一眼剪贴板，补上没察觉到的复制。需要增强模式。读取的一瞬间当前窗口会短暂失去焦点，若某个游戏因此暂停，可以关掉",
                    "Also checks the clipboard after switching apps, unlocking, or tapping a long-press menu item. Needs enhanced mode. The current window loses focus for an instant; turn this off if a game pauses because of it."), prefs.passiveCapture, icon = R.drawable.ic_gen_search) { prefs.passiveCapture = it; v++ }
                if (trashOn) Clickable(tr("回收站", "Trash"), tr("删除的、超出上限的记录先放在这里，保留 %d 天", "Deleted and overflowing items are kept here for %d days", ItemRepo.TRASH_DAYS), tr("查看", "Open"), icon = R.drawable.ic_gen_trash) { onOpenTrash() }
                if (hasLegacy) Clickable(tr("升级前的记录", "Records from before 0.6"), tr("升级到 0.6 之前的记录还留着一份。发现升级后少了内容，可以从这里补回来",
                    "A copy of your pre-0.6 history is kept. If something went missing after the upgrade, restore it here."), tr("补回", "Restore"), icon = R.drawable.ic_gen_history) { confirmReimport = true }
                Clickable(tr("清空全部记录", "Clear all history"), if (trashOn) tr("包括收藏的记录；会移入回收站，常用语保留", "Including starred items; they go to the trash, phrases stay")
                    else tr("包括收藏的记录，不可恢复；常用语保留", "Including starred items, permanently; phrases stay"), tr("清空", "Clear"), icon = R.drawable.ic_gen_trash, danger = true) { confirmClear = true }
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
            SubHeader(tr("外观与语言", "Look & language"), onBack, R.drawable.ic_gen_palette,
                tr("主题和界面语言。悬浮面板也跟着变。", "Theme and language; the floating panel follows too."))
            Group(tr("主题", "Theme")) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(tr("跟随系统", "System"), tr("浅色", "Light"), tr("深色", "Dark")).forEachIndexed { i, label ->
                        ThemeTile(i, label, prefs.theme == i, Modifier.weight(1f)) { prefs.theme = i; (ctx as? Activity)?.recreate() }
                    }
                }
            }
            Group(tr("语言", "Language")) {
                Choice(tr("界面语言", "Language"), listOf(tr("跟随系统", "System"), "中文", "English"), prefs.language, icon = R.drawable.ic_gen_tg_cjk) {
                    prefs.language = it
                    L.init(ctx, it)
                    (ctx as? Activity)?.recreate()
                }
            }
        }
    }
}

/** 主题选项：画一个缩小的界面示意（侧栏 + 两张卡片），跟随系统时左浅右深 */
@Composable
private fun ThemeTile(kind: Int, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = listOf(Color(0xFFF6F7FA), Color(0xFFEEF1F6), Color.White, Color(0xFFDCE1E8))
    val dark = listOf(Color(0xFF131417), Color(0xFF1B1D21), Color(0xFF202227), Color(0xFF3A3E47))
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .border(if (selected) 2.dp else 1.dp, if (selected) Accent else cs.outlineVariant, RoundedCornerShape(16.dp)).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp))) {
            @Composable fun Mock(c: List<Color>, m: Modifier) {
                Row(m.background(c[0])) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.28f).background(c[1]))
                    Column(Modifier.weight(1f).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.fillMaxWidth(0.6f).height(5.dp).clip(CircleShape).background(c[3]))
                        repeat(2) { Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)).background(c[2]).border(0.5.dp, c[3], RoundedCornerShape(4.dp))) }
                    }
                }
            }
            when (kind) {
                1 -> Mock(light, Modifier.fillMaxSize())
                2 -> Mock(dark, Modifier.fillMaxSize())
                else -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { Mock(light, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { Mock(dark, Modifier.fillMaxSize()) }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).border(2.dp, if (selected) Accent else cs.outline, CircleShape).padding(4.dp)
                .clip(CircleShape).background(if (selected) Accent else Color.Transparent))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Accent else cs.onSurface)
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
//
// 设置页统一的样式：分组卡片里一行一项；行首可选一个小图标块；开关、几选一、按钮都在行尾。

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.padding(start = 6.dp, bottom = 7.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(Accent))
            Spacer(Modifier.width(7.dp))
            Text(title, fontSize = 12.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)).padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** 行首的小图标块 */
@Composable
fun RowIcon(icon: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, bg: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(17.dp), tint = tint)
    }
}

@Composable
fun RowBase(title: String, sub: String?, onClick: (() -> Unit)?, icon: Int? = null, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { RowIcon(icon); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 品牌色开关：开是蓝底白点，关是浅灰底 */
@Composable
fun BrandSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked, onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Accent, checkedBorderColor = Accent,
            uncheckedThumbColor = cs.onSurfaceVariant, uncheckedTrackColor = cs.surfaceVariant, uncheckedBorderColor = cs.outlineVariant,
        ),
    )
}

@Composable
fun Toggle(title: String, sub: String?, value: Boolean, enabled: Boolean = true, icon: Int? = null, onChange: (Boolean) -> Unit) =
    RowBase(title, sub, if (enabled) ({ onChange(!value) }) else null, icon) { BrandSwitch(value, onChange, enabled) }

@Composable
fun Clickable(title: String, sub: String?, value: String, icon: Int? = null, danger: Boolean = false, onClick: () -> Unit) =
    RowBase(title, sub, onClick, icon) {
        val c = if (danger) Danger else Accent
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c, modifier = Modifier.clip(CircleShape)
            .background(c.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 6.dp))
    }

/** 进入下一级页面的一行；badge 为 "!" 时显示成提醒色 */
@Composable
fun Entry(title: String, sub: String?, badge: String? = null, icon: Int? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, tint = Accent, bg = Accent.copy(alpha = 0.10f))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (badge != null) Text(badge, color = Warn, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Text("›", fontSize = 20.sp, color = cs.onSurfaceVariant)
    }
}

/** 几选一。选项放得下就排在标题右边；放不下（手机竖屏、选项多）就另起一行，可以左右滑 */
@Composable
fun Choice(title: String, options: List<String>, selected: Int, icon: Int? = null, sub: String? = null, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // fill = 另起一行时铺满整行、各选项等分，不出现被切掉一半的选项
    val chips: @Composable (Boolean) -> Unit = { fill ->
        Row(Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).clip(CircleShape).background(cs.surfaceVariant).padding(3.dp)) {
            options.forEachIndexed { i, o ->
                val sel = i == selected
                Text(
                    o, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) Color.White else cs.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.then(if (fill) Modifier.weight(1f) else Modifier).clip(CircleShape)
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { onSelect(i) }.padding(horizontal = if (fill) 2.dp else 12.dp, vertical = 6.dp),
                )
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 粗略估一下这一行要多宽：汉字约 14dp 一个、数字字母约 8dp，每个选项左右留白 24dp，再加标题和边距
        val need = (if (icon != null) 250 else 210) + options.sumOf { o -> 24 + o.fold(0) { w, c -> w + if (c.code > 0x2E7F) 14 else 8 } }
        val avail = maxWidth
        if (avail >= need.dp) RowBase(title, sub, null, icon) { chips(false) }
        else Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { RowIcon(icon); Spacer(Modifier.width(12.dp)) }
                Column {
                    Text(title, fontSize = 15.sp, color = cs.onSurface)
                    if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 每个选项至少要能放下自己的字；实在放不下才左右滑
            val minW = options.sumOf { o -> 10 + o.fold(0) { w, c -> w + if (c.code > 0x2E7F) 14 else 8 } } + 6
            if (avail - 32.dp >= minW.dp) chips(true)
            else Row(Modifier.horizontalScroll(rememberScrollState())) { chips(false) }
        }
    }
}

@Composable
fun Hint(t: String) =
    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

/** 一组键帽：把 “Ctrl + Shift + V” 画成三个按键 */
@Composable
fun KeyCaps(combo: String, small: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        combo.split("+").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, k ->
            if (i > 0) Text("+", fontSize = if (small) 11.sp else 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 3.dp))
            Box(
                Modifier.heightIn(min = if (small) 24.dp else 32.dp).widthIn(min = if (small) 24.dp else 32.dp)
                    .clip(RoundedCornerShape(if (small) 6.dp else 8.dp)).background(cs.surface)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(if (small) 6.dp else 8.dp))
                    .padding(start = 1.dp, end = 1.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(if (small) 5.dp else 7.dp)).background(cs.surfaceVariant)
                    .padding(horizontal = if (small) 6.dp else 9.dp, vertical = if (small) 2.dp else 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(k, fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1) }
        }
    }
}

/** 快捷键对照表：左边键帽，右边说明；平板两列，手机一列 */
@Composable
fun ShortcutTable(rows: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val cols = if (maxWidth >= 560.dp) 2 else 1
        Column {
            rows.chunked(cols).forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    line.forEach { (keys, desc) ->
                        Row(Modifier.weight(1f).padding(vertical = 5.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.widthIn(min = 150.dp)) { KeyCaps(keys, small = true) }
                            Spacer(Modifier.width(8.dp))
                            Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
