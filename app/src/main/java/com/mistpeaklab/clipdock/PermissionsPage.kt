package com.mistpeaklab.clipdock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// ---------------- 权限状态 ----------------

private class Perm(val ok: Boolean)

private fun overlayOk(ctx: Context) = Settings.canDrawOverlays(ctx)
private fun a11yOk() = ClipService.instance != null
private fun batteryOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/**
 * 不是从应用商店装的（文件管理器、浏览器、电脑上装的），Android 13 起开无障碍前要先“允许受限设置”。
 * 这里只能猜：判断安装来源。猜错了也只是多显示一行说明。
 */
private fun maybeRestricted(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val installer = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName }.getOrNull()
    return installer == null || installer.contains("packageinstaller") || installer.contains("filemanager") || installer.contains("documentsui")
}

/** 各厂商“自启动 / 后台运行”设置页，逐个试，都打不开就去应用详情页 */
private fun openVendorBackground(ctx: Context) {
    val m = Build.MANUFACTURER.lowercase()
    val candidates = when {
        "vivo" in m || "iqoo" in m -> listOf(
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
        )
        "xiaomi" in m || "redmi" in m -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
        "huawei" in m || "honor" in m -> listOf("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity")
        "oppo" in m || "oneplus" in m || "realme" in m -> listOf("com.coloros.safecenter/.startupapp.StartupAppListActivity")
        else -> emptyList()
    }
    for (c in candidates) {
        val cn = android.content.ComponentName.unflattenFromString(c) ?: continue
        if (runCatching { ctx.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    ctx.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
}

private fun requestBattery(ctx: Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.onFailure { ctx.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }

/** 一张权限卡片：开了能做什么、现在的状态、去开启 */
@Composable
private fun PermCard(title: String, desc: String, ok: Boolean, action: String, extra: String? = null, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
            val c = if (ok) Okay else Warn
            Text(if (ok) tr("已开启", "On") else tr("未开启", "Off"), fontSize = 12.sp, color = c,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(desc, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
        if (!ok && extra != null) Text(extra, fontSize = 12.sp, lineHeight = 18.sp, color = Warn, modifier = Modifier.padding(top = 6.dp))
        if (!ok) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { Button(onAction) { Text(action) } }
    }
}

@Composable
private fun PermissionCards(tick: Int, profile: Int) {
    val ctx = LocalContext.current
    val overlay = remember(tick) { overlayOk(ctx) }
    val a11y = remember(tick) { a11yOk() }
    val battery = remember(tick) { batteryOk(ctx) }
    val restricted = remember { maybeRestricted(ctx) }
    val keyboard = profile == Prefs.PROFILE_TABLET_KEYBOARD
    val a11yCard = @Composable {
        PermCard(
            tr("增强模式（无障碍服务）", "Enhanced mode (accessibility service)"),
            tr("开了以后：在后台自动记下复制的文字；实体键盘按 Win+V（可改）唤出面板；面板不抢输入框焦点、不弹输入法；选中后直接贴进输入框。",
                "Records what you copy in the background; a keyboard hotkey (Win+V, changeable) opens the panel without stealing focus; picks are pasted straight into the text field."),
            a11y, tr("去开启", "Turn on"),
            extra = if (restricted) tr("开关是灰的、提示“受限设置”？先到 系统设置 › 应用 › 随贴 › 右上角 ⋮ › 允许受限设置，再回来开启。",
                "Switch greyed out (“restricted setting”)? Go to Settings › Apps › ClipDock › ⋮ › Allow restricted settings first.") else null,
        ) { ctx.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
    }
    val overlayCard = @Composable {
        PermCard(
            tr("悬浮面板（显示在其他应用上层）", "Floating panel (display over other apps)"),
            tr("开了以后：从桌面图标、通知栏开关、长按图标随时唤出悬浮面板，面板打开的一瞬间读一次剪贴板。",
                "Open the floating panel from the app icon, quick-settings tile or long-press menu; it reads the clipboard when it opens."),
            overlay, tr("去允许", "Allow"),
        ) { ctx.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) }
    }
    if (keyboard) { a11yCard(); overlayCard() } else { overlayCard(); a11yCard() }
    PermCard(
        tr("保持后台运行", "Keep running in the background"),
        tr("防止系统清理随贴，导致快捷键失效、停止记录。", "Stops the system from killing ClipDock, which would break the hotkey and recording."),
        battery, tr("去设置", "Set up"),
    ) { requestBattery(ctx) }
    TextButton({ openVendorBackground(ctx) }) { Text(tr("还会被清理？打开手机的“自启动 / 后台运行”设置", "Still getting killed? Open your phone's auto-start settings")) }
}

@Composable
fun PermissionsPage(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var t by remember { mutableIntStateOf(tick) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { t++ }
    PageColumn {
        SubHeader(tr("权限与模式", "Permissions"), onBack)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.08f)).padding(16.dp)) {
            Text(tr("为什么没有“允许读取剪贴板”的弹窗？", "Why is there no “allow clipboard access” prompt?"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("从 Android 10 开始，系统只允许“当前在前台的应用”和“默认输入法”读取剪贴板，普通应用申请不到后台读取的权限。所以随贴分三档：不给任何权限也能用；给了悬浮窗权限可以随时唤出面板；开启无障碍服务后才能在后台自动记录、用快捷键唤起。",
                    "Since Android 10 only the foreground app and the keyboard may read the clipboard. So ClipDock works in three levels: with no permission at all; with the overlay permission you can open the panel anytime; with the accessibility service it records in the background and responds to a hotkey."),
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PermissionCards(t, prefs.usageProfile)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.08f)).padding(16.dp)) {
            Text(tr("不开任何权限，也能把文字存进来", "Save text without any permission"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("在任何应用里选中文字，在弹出的菜单里点“存到随贴”（有的应用把它收在 ⋮ 里）；或者用“分享”把文字发给随贴。",
                    "Select text in any app and choose “Save to ClipDock” from the menu (it may be under ⋮), or share text to ClipDock."),
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(tr("数据只保存在本机，应用没有申请联网权限。", "Everything stays on this device; ClipDock has no internet permission."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
    }
}

// ---------------- 首次引导 ----------------

@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var step by remember { mutableIntStateOf(0) }
    var profile by remember { mutableIntStateOf(prefs.usageProfile) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("第 %d / 4 步", "Step %d of 4", step + 1), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ if (profile == Prefs.PROFILE_UNSET) prefs.usageProfile = Prefs.PROFILE_JUST_LOOKING; onDone() }) { Text(tr("跳过", "Skip")) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> {
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher_fg), null, Modifier.size(72.dp))
                    Text(tr("欢迎使用随贴", "Welcome to ClipDock"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(tr("记下你复制过的文字，需要时一键贴回去。只存在本机，不联网。", "Keeps what you copy and lets you paste it back in a tap. Stays on this device, never online."),
                        fontSize = 15.sp, lineHeight = 23.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(tr("你主要怎么用？", "How will you mostly use it?"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    listOf(
                        Triple(Prefs.PROFILE_TABLET_KEYBOARD, tr("平板 + 实体键盘", "Tablet + keyboard"), tr("用 Win+V 这样的快捷键唤出，回车贴进去", "Open with a hotkey like Win+V, press Enter to paste")),
                        Triple(Prefs.PROFILE_PHONE_TOUCH, tr("手机触屏", "Phone, touch"), tr("从图标、通知栏唤出面板，点一下复制", "Open from the icon or quick settings, tap to copy")),
                        Triple(Prefs.PROFILE_JUST_LOOKING, tr("先看看", "Just looking"), tr("先不开任何权限", "No permissions for now")),
                    ).forEach { (p, title, desc) ->
                        val sel = profile == p
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Accent.copy(alpha = 0.12f) else cs.surface)
                                .border(if (sel) 1.5.dp else 1.dp, if (sel) Accent else cs.outlineVariant, RoundedCornerShape(14.dp))
                                .clickable { profile = p }.padding(16.dp),
                        ) {
                            Text(title, fontWeight = FontWeight.SemiBold, color = if (sel) Accent else cs.onSurface)
                            Text(desc, fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                1 -> {
                    Text(tr("要多少功能？", "How much do you want?"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(tr("以后随时可以在 我的 › 功能 里改，也可以逐项开关。", "You can change this anytime in Me › Features, or toggle features one by one."),
                        fontSize = 14.sp, color = cs.onSurfaceVariant)
                    var mode by remember { mutableStateOf(prefs.mode) }
                    listOf(
                        Mode.SIMPLE to tr("记录、搜索、粘贴、收藏。界面最清爽，适合只想要一个好用的剪贴板。", "History, search, paste, favourites. The cleanest screen."),
                        Mode.FULL to tr("再加上：依次粘贴、连续收集、内容类型识别和快捷动作、粘贴前变换、来源应用、拆分合并、备注、分组、隐藏内容、验证码自动清除。",
                            "Adds paste queue, collect mode, content types and actions, transforms, source app, split/merge, notes, groups, hidden items and auto-removal of codes."),
                    ).forEach { (m, desc) ->
                        val sel = mode == m
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Accent.copy(alpha = 0.12f) else cs.surface)
                                .border(if (sel) 1.5.dp else 1.dp, if (sel) Accent else cs.outlineVariant, RoundedCornerShape(14.dp))
                                .clickable { prefs.applyMode(m); mode = m }.padding(16.dp),
                        ) {
                            Text(m.title, fontWeight = FontWeight.SemiBold, color = if (sel) Accent else cs.onSurface)
                            Text(desc, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                2 -> {
                    Text(tr("开启需要的权限", "Turn on what you need"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (profile) {
                            Prefs.PROFILE_TABLET_KEYBOARD -> tr("用键盘快捷键唤出、后台自动记录，需要增强模式。", "The hotkey and background recording need enhanced mode.")
                            Prefs.PROFILE_PHONE_TOUCH -> tr("手机上先开悬浮窗就够用；想在后台自动记录，再开增强模式。", "On a phone the overlay permission is enough to start; turn on enhanced mode for background recording.")
                            else -> tr("都可以先不开，打开随贴时也会记下当前剪贴板。", "You can skip these; ClipDock still records the clipboard when you open it.")
                        },
                        fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant,
                    )
                    PermissionCards(tick, profile)
                }
                else -> {
                    Text(tr("这样用", "How to use it"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    val tips = if (profile == Prefs.PROFILE_TABLET_KEYBOARD) listOf(
                        tr("Win + V：唤出面板，直接打字搜索，回车贴进输入框", "Win + V opens the panel; type to search, Enter pastes"),
                        tr("Ctrl + 点击 / Ctrl + A 多选，Ctrl + V 合并贴进去", "Ctrl + click or Ctrl + A to select several, Ctrl + V pastes them together"),
                        tr("→ 键：把一条展开，只挑其中几个字贴", "→ expands an item so you can paste just part of it"),
                    ) else listOf(
                        tr("长按桌面图标 › “悬浮面板”，或下拉通知栏点“剪贴板”开关", "Long-press the app icon › “Floating panel”, or use the quick-settings tile"),
                        tr("在任何应用里选中文字 › “存到随贴”，不需要任何权限", "Select text anywhere › “Save to ClipDock”, no permission needed"),
                        tr("卡片上点 ☆ 收藏，收藏的不会被清掉", "Tap ☆ on a card to keep it forever"),
                    )
                    tips.forEach { t ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface).padding(14.dp)) {
                            Text("•  ", color = Accent, fontWeight = FontWeight.Bold)
                            Text(t, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            if (step > 0) TextButton({ step-- }) { Text(tr("上一步", "Back")) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (step == 0) prefs.usageProfile = profile
                    if (step < 3) step++ else onDone()
                },
                enabled = step != 0 || profile != Prefs.PROFILE_UNSET,
            ) { Text(if (step < 3) tr("下一步", "Next") else tr("开始使用", "Start")) }
        }
    }
}
