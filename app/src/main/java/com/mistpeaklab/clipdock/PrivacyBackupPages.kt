package com.mistpeaklab.clipdock

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.data.AppRule
import com.mistpeaklab.clipdock.data.Backup
import com.mistpeaklab.clipdock.data.ItemRepo
import com.mistpeaklab.clipdock.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 隐私 ----------------

@Composable
fun PrivacyPage(onBack: () -> Unit, onOpenRules: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val fv by Features.version.collectAsStateWithLifecycle()
    val items by ClipStore.items.collectAsStateWithLifecycle()
    var v by remember { mutableIntStateOf(0) }
    var confirmDrop by remember { mutableStateOf(false) }
    val hideOn = remember(fv) { Features.isOn(ctx, Feature.HIDE) }
    val sensOn = remember(fv) { Features.isOn(ctx, Feature.SENSITIVE_DETECT) }
    val hiddenCount = items.count { it.hidden }
    val scroll = rememberScrollState()
    key(v, fv) {
        PageColumn(scroll) {
            SubHeader(tr("隐私", "Privacy"), onBack, R.drawable.ic_gen_lock,
                tr("哪些内容不记、哪些要验证后才能看。数据只在本机。", "What isn't recorded and what needs verification. Data stays on this device."))
            if (Vault.state == Vault.STATE_BROKEN && hiddenCount > 0) {
                Group(tr("无法显示的隐藏内容", "Hidden items that can't be shown")) {
                    Hint(tr("系统清除了随贴的密钥（例如清除过应用数据的一部分、刷机），已隐藏的 %d 条内容再也无法解开。可以把它们清理掉，之后隐藏功能会用新的密钥继续工作。",
                        "The system removed ClipDock's key, so %d hidden items can never be decrypted. Clear them and hiding will work again with a new key.", hiddenCount))
                    Clickable(tr("清理这些内容", "Clear them"), null, tr("清理", "Clear")) { confirmDrop = true }
                }
            }
            Group(tr("隐藏内容", "Hidden items")) {
                Toggle(Feature.HIDE.title, Feature.HIDE.desc, hideOn, icon = R.drawable.ic_gen_lock) { prefs.setFeature(Feature.HIDE, it) }
                if (hideOn) {
                    Choice(tr("验证一次后多久不用再验证", "Stay unlocked for"), listOf(tr("每次", "Always ask"), "1 " + tr("分钟", "min"), "5 " + tr("分钟", "min"), "15 " + tr("分钟", "min")),
                        listOf(0, 1, 5, 15).indexOf(prefs.unlockMinutes).coerceAtLeast(0), icon = R.drawable.ic_gen_history) { prefs.unlockMinutes = listOf(0, 1, 5, 15)[it]; v++ }
                    Clickable(tr("立即锁上", "Lock now"), tr("现在就要求重新验证", "Require verification again right away"), tr("锁上", "Lock"), icon = R.drawable.ic_gen_lock) { Vault.lock(); ctx.toast(tr("已锁上", "Locked")) }
                    Hint(tr("隐藏的内容在本机加密保存：数据库文件被拷走也看不到；查看、粘贴前要验证指纹或锁屏密码（没设锁屏密码的设备用应用密码）。防不了已经取得最高权限（root）的人。",
                        "Hidden items are encrypted on this device, so a copied database file reveals nothing; viewing or pasting needs your fingerprint or screen lock (or an app passcode). It cannot protect against someone with root access."))
                }
            }
            Group(tr("自动识别", "Detection")) {
                Toggle(Feature.SENSITIVE_DETECT.title, Feature.SENSITIVE_DETECT.desc, sensOn, icon = R.drawable.ic_gen_shield) { prefs.setFeature(Feature.SENSITIVE_DETECT, it) }
                if (sensOn) Choice(tr("验证码多久后自动清除", "Remove codes after"), listOf(tr("不清除", "Never"), "5 " + tr("分钟", "min"), "10 " + tr("分钟", "min"), "30 " + tr("分钟", "min")),
                    listOf(0, 5, 10, 30).indexOf(prefs.otpMinutes).coerceAtLeast(0), icon = R.drawable.ic_gen_tf_numbers) { prefs.otpMinutes = listOf(0, 5, 10, 30)[it]; v++ }
            }
            Group(tr("不记录", "Don't record")) {
                Toggle(tr("不记录密码管理器里复制的内容", "Skip password managers"),
                    tr("Bitwarden、1Password、KeePass 等；以及被来源应用标记为“敏感”的内容（例如系统的密码自动填充）", "Bitwarden, 1Password, KeePass…, and anything the source app marks as sensitive"),
                    prefs.skipSensitiveSources, icon = R.drawable.ic_gen_shield) { prefs.skipSensitiveSources = it; v++ }
                Entry(tr("按应用设置", "Per-app rules"), tr("在某些应用里复制的不记录，或自动隐藏", "Don't record, or auto-hide, what you copy in certain apps"), icon = R.drawable.ic_gen_grid) { onOpenRules() }
            }
            Group(tr("屏幕", "Screen")) {
                Toggle(tr("主界面禁止截屏", "Block screenshots of this screen"), tr("也不会出现在最近任务的缩略图里", "Also hides it from the recent-apps thumbnails"), prefs.secureWindow, icon = R.drawable.ic_gen_pick) {
                    prefs.secureWindow = it; v++
                    (ctx as? android.app.Activity)?.recreate()
                }
            }
        }
    }
    if (confirmDrop) AlertDialog(
        onDismissRequest = { confirmDrop = false },
        title = { Text(tr("清理无法解开的内容？", "Clear undecryptable items?")) },
        text = { Text(tr("这 %d 条内容会被彻底删除。它们已经无法解开，删除不会让你失去任何还能看到的东西。", "These %d items will be deleted permanently. They can't be decrypted anyway.", hiddenCount)) },
        confirmButton = { TextButton({ ClipStore.dropUnreadableHidden(); confirmDrop = false }) { Text(tr("清理", "Clear"), color = Danger) } },
        dismissButton = { TextButton({ confirmDrop = false }) { Text(tr("取消", "Cancel")) } },
    )
}

/** 按应用：正常 / 不记录 / 自动隐藏 */
@Composable
fun AppRulesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rules by ClipStore.rules.collectAsStateWithLifecycle()
    val items by ClipStore.items.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
            launchable.filter { it != ctx.packageName }.map { it to AppInfo.label(ctx, it) }.sortedBy { it.second.lowercase() }
        }
    }
    val hideOn = Features.isOn(ctx, Feature.HIDE)
    // 有规则的、最近复制过的排在前面
    val recent = remember(items) { items.mapNotNull { it.sourcePkg }.distinct() }
    val list = remember(all, rules, recent, query) {
        val l = all ?: return@remember null
        val q = query.trim().lowercase()
        l.filter { q.isEmpty() || it.second.lowercase().contains(q) || it.first.contains(q) }
            .sortedWith(compareByDescending<Pair<String, String>> { it.first in rules }.thenByDescending { it.first in recent })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubHeader(tr("按应用设置", "Per-app rules"), onBack, R.drawable.ic_gen_grid,
            tr("在某些应用里复制的内容不记录，或者自动隐藏。", "Skip or auto-hide what you copy in certain apps."))
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("搜索应用", "Search apps")) }, modifier = Modifier.fillMaxWidth())
        if (list == null) Text(tr("正在读取应用列表…", "Loading apps…"), modifier = Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(list, key = { it.first }) { (pkg, label) ->
                val r = rules[pkg]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 15.sp)
                        Text(pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val opts = buildList {
                        add(null to tr("正常", "Normal"))
                        add(AppRule.SKIP to tr("不记录", "Skip"))
                        if (hideOn) add(AppRule.HIDE to tr("隐藏", "Hide"))
                    }
                    SingleChoiceSegmentedButtonRow {
                        opts.forEachIndexed { i, (value, text) ->
                            SegmentedButton(selected = r == value, onClick = { ClipStore.setRule(pkg, value) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) {
                                Text(text, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 备份与导入导出 ----------------

private val fileStamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
private val showStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
fun BackupPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var v by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }

    // 导出选项
    var exportAsk by remember { mutableStateOf(false) }
    var withTrash by remember { mutableStateOf(false) }
    var withHidden by remember { mutableStateOf(false) }
    var exportPwd by remember { mutableStateOf("") }
    var pendingOpts by remember { mutableStateOf<Backup.Options?>(null) }

    // 导入
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importInfo by remember { mutableStateOf<Backup.Info?>(null) }
    var importPwd by remember { mutableStateOf("") }
    var importReplace by remember { mutableStateOf(false) }

    // 自动备份
    var setPwd by remember { mutableStateOf(false) }
    var newPwd by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val o = pendingOpts
        pendingOpts = null
        if (uri == null || o == null) return@rememberLauncherForActivityResult
        busy = tr("正在导出…", "Exporting…")
        ClipStore.export(uri, o) { r ->
            busy = null
            r.onSuccess { ctx.toast(tr("已导出 %d 条", "Exported %d items", it)) }
                .onFailure { ctx.toast(tr("导出失败：%s", "Export failed: %s", it.message ?: "")) }
        }
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = tr("正在导出…", "Exporting…")
        ClipStore.exportText(uri) { r ->
            busy = null
            r.onSuccess { ctx.toast(tr("已导出 %d 条", "Exported %d items", it)) }.onFailure { ctx.toast(tr("导出失败：%s", "Export failed: %s", it.message ?: "")) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = tr("正在读取文件…", "Reading file…")
        ClipStore.peek(uri) { r ->
            busy = null
            r.onSuccess { importUri = uri; importInfo = it; importPwd = ""; importReplace = false }
                .onFailure { ctx.toast(if (it is Backup.TooNewException) tr("这个文件来自更新版本的随贴，请先升级", "This file is from a newer ClipDock. Please update first.")
                    else tr("读不了这个文件：%s", "Can't read this file: %s", it.message ?: "")) }
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.backupTree = uri.toString()
        if (prefs.backupFreq == 0) prefs.backupFreq = 1
        AutoBackup.schedule(ctx)
        v++
    }

    val scroll = rememberScrollState()
    key(v) {
        PageColumn(scroll) {
            SubHeader(tr("备份与导入导出", "Backup & export"), onBack, R.drawable.ic_gen_download,
                tr("换机、重装都能完整恢复。文件只写到你选的位置，随贴本身不联网。", "Restore everything after a reinstall or on a new device. Files go only where you choose."))
            // 两个最常用的动作做成大卡片
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigAction(R.drawable.ic_gen_download, tr("导出全部", "Export everything"), tr("存成 .clipdock 文件，可设密码", "A .clipdock file, optional password"),
                    Modifier.weight(1f).fillMaxHeight()) { exportAsk = true }
                BigAction(R.drawable.ic_gen_folder, tr("从文件导入", "Import from file"), tr(".clipdock、旧版 history.json、纯文本", ".clipdock, old history.json, plain text"),
                    Modifier.weight(1f).fillMaxHeight()) { importLauncher.launch(arrayOf("*/*")) }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
            Group(tr("其他导出", "Other export")) {
                Clickable(tr("导出为文本", "Export as text"), tr("给人看的纯文本，不含隐藏的内容；不能再导入", "Plain text for reading; excludes hidden items; can't be imported back"), tr("导出", "Export"), icon = R.drawable.ic_gen_note) {
                    textLauncher.launch("ClipDock-" + fileStamp.format(Date()) + ".txt")
                }
            }
            Group(tr("自动备份", "Automatic backup")) {
                val dirName = remember(prefs.backupTree) {
                    prefs.backupTree.takeIf { it.isNotEmpty() }?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':') }.getOrNull() }
                }
                Clickable(tr("备份到哪个文件夹", "Backup folder"), dirName?.let { tr("当前：%s", "Current: %s", it.ifEmpty { "/" }) } ?: tr("还没选", "Not chosen"),
                    if (dirName == null) tr("选择", "Choose") else tr("更换", "Change"), icon = R.drawable.ic_gen_folder) { treeLauncher.launch(null) }
                if (prefs.backupTree.isNotEmpty()) {
                    Choice(tr("频率", "How often"), listOf(tr("关闭", "Off"), tr("每天", "Daily"), tr("每周", "Weekly")), prefs.backupFreq, icon = R.drawable.ic_gen_history) { prefs.backupFreq = it; AutoBackup.schedule(ctx); v++ }
                    Choice(tr("保留份数", "Keep"), listOf("3", "7", "14", "30"), listOf(3, 7, 14, 30).indexOf(prefs.backupKeep).coerceAtLeast(1), icon = R.drawable.ic_gen_queue) { prefs.backupKeep = listOf(3, 7, 14, 30)[it]; v++ }
                    Clickable(tr("备份密码", "Backup password"),
                        if (prefs.backupKey.isEmpty()) tr("不加密（隐藏的内容不会写进备份）", "Not encrypted (hidden items are left out)")
                        else tr("已加密（隐藏的内容也会备份）。恢复时需要这个密码", "Encrypted (hidden items included). You'll need this password to restore."),
                        if (prefs.backupKey.isEmpty()) tr("设置", "Set") else tr("修改", "Change"), icon = R.drawable.ic_gen_lock) { newPwd = ""; setPwd = true }
                    Clickable(tr("立即备份一次", "Back up now"),
                        when {
                            prefs.lastBackupError.isNotEmpty() -> tr("上次失败：%s", "Last attempt failed: %s", prefs.lastBackupError)
                            prefs.lastBackupAt > 0 -> tr("上次备份：%s", "Last backup: %s", showStamp.format(Date(prefs.lastBackupAt)))
                            else -> tr("还没备份过", "Never backed up")
                        }, tr("备份", "Back up"), icon = R.drawable.ic_gen_download) {
                        busy = tr("正在备份…", "Backing up…")
                        AutoBackup.run(ctx) { err -> scope.launch { busy = null; ctx.toast(err?.let { tr("备份失败：%s", "Backup failed: %s", it) } ?: tr("备份完成", "Backup done")); v++ } }
                    }
                }
                Hint(tr("备份文件只写到你选的文件夹，随贴本身不联网。选一个网盘同步的文件夹，就能把备份同步到别处。",
                    "Backups go only to the folder you choose; ClipDock itself never goes online. Pick a cloud-synced folder to keep copies elsewhere."))
            }
        }
    }

    if (exportAsk) AlertDialog(
        onDismissRequest = { exportAsk = false },
        title = { Text(tr("导出", "Export")) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withTrash, { withTrash = it }); Text(tr("包括回收站里的", "Include the trash")) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withHidden, { withHidden = it }, enabled = exportPwd.isNotEmpty())
                    Text(tr("包括隐藏的内容（需要设密码）", "Include hidden items (needs a password)"))
                }
                OutlinedTextField(exportPwd, { exportPwd = it; if (it.isEmpty()) withHidden = false }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text(tr("密码（可不填）", "Password (optional)")) })
                Text(tr("设了密码，文件会加密；忘了密码就无法导入。", "With a password the file is encrypted; forget it and the file can't be imported."), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                exportAsk = false
                val pwd = exportPwd.takeIf { it.isNotEmpty() }?.toCharArray()
                val go = {
                    pendingOpts = Backup.Options(includeTrash = withTrash, includeHidden = withHidden && pwd != null, password = pwd,
                        app = "ClipDock Android " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault(""))
                    exportLauncher.launch("ClipDock-" + fileStamp.format(Date()) + "." + Backup.EXT)
                }
                // 导出隐藏的内容等于把它们解开写进文件，先验证身份
                if (withHidden && pwd != null) Unlock.run(ctx, go) else go()
            }) { Text(tr("选择保存位置", "Choose where to save")) }
        },
        dismissButton = { TextButton({ exportAsk = false }) { Text(tr("取消", "Cancel")) } },
    )

    importInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { importInfo = null },
            title = { Text(tr("导入", "Import")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (info.kind) {
                        Backup.Kind.CLIPDOCK -> tr("随贴导出的文件：%d 条、%d 个分组", "ClipDock file: %d items, %d groups", info.items, info.groups) +
                            (if (info.createdAt > 0) tr("，导出于 %s", ", exported %s", showStamp.format(Date(info.createdAt))) else "")
                        Backup.Kind.LEGACY_JSON -> tr("旧版本的记录文件：%d 条", "Old history file: %d items", info.items)
                        Backup.Kind.TEXT -> tr("纯文本：会作为一条记录导入", "Plain text: imported as one item")
                    })
                    if (info.encrypted) OutlinedTextField(importPwd, { importPwd = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        label = { Text(tr("文件的密码", "File password")) })
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!importReplace, { importReplace = false }); Text(tr("合并：加进来，已有的不重复", "Merge: add, without duplicates")) }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(importReplace, { importReplace = true }); Text(tr("替换：现有的先移进回收站", "Replace: move current items to the trash first")) }
                }
            },
            confirmButton = {
                TextButton({
                    val uri = importUri ?: return@TextButton
                    importInfo = null
                    busy = tr("正在导入…", "Importing…")
                    ClipStore.import(uri, importPwd.takeIf { info.encrypted }?.toCharArray(), importReplace) { r ->
                        busy = null
                        r.onSuccess { s -> ctx.toast(tr("导入完成：新增 %d 条，更新 %d 条，跳过 %d 条", "Done: %d added, %d updated, %d skipped", s.added, s.updated, s.skipped)) }
                            .onFailure { e ->
                                ctx.toast(when (e) {
                                    is com.mistpeaklab.clipdock.data.Crypto.BadKeyException -> tr("密码不对，或者文件已损坏。什么都没有改动。", "Wrong password or damaged file. Nothing was changed.")
                                    is Backup.NeedPasswordException -> tr("这个文件需要密码", "This file needs a password")
                                    else -> tr("导入失败，什么都没有改动：%s", "Import failed; nothing was changed: %s", e.message ?: "")
                                })
                            }
                    }
                }, enabled = !info.encrypted || importPwd.isNotEmpty()) { Text(tr("导入", "Import")) }
            },
            dismissButton = { TextButton({ importInfo = null }) { Text(tr("取消", "Cancel")) } },
        )
    }

    if (setPwd) AlertDialog(
        onDismissRequest = { setPwd = false },
        title = { Text(tr("自动备份的密码", "Backup password")) },
        text = {
            Column {
                OutlinedTextField(newPwd, { newPwd = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(tr("密码（留空 = 不加密）", "Password (empty = no encryption)")) })
                Text(tr("恢复备份时需要它，请记牢。随贴会把它安全地记在本机，之后自动备份不用再输。", "You'll need it to restore, so remember it. ClipDock keeps it securely on this device for automatic backups."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                setPwd = false
                val p = newPwd
                busy = tr("正在设置…", "Setting up…")
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { AutoBackup.setPassword(ctx, p.takeIf { it.isNotEmpty() }?.toCharArray()) }
                    busy = null
                    ctx.toast(if (ok) tr("已设置", "Saved") else tr("这台设备无法安全地记住密码", "This device can't store the password securely"))
                    v++
                }
            }) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton({ setPwd = false }) { Text(tr("取消", "Cancel")) } },
    )
}

/** 备份页上的大动作卡片：图标 + 标题 + 一句说明，整块可点 */
@Composable
private fun BigAction(icon: Int, title: String, desc: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Accent), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(desc, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
