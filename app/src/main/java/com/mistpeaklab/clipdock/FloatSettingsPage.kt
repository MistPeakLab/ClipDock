package com.mistpeaklab.clipdock

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mistpeaklab.clipdock.floatball.FloatBall
import com.mistpeaklab.clipdock.floatball.Mascot
import com.mistpeaklab.clipdock.floatball.SpriteView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 悬浮球 ----------------

@Composable
fun FloatSettingsPage(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }
    val canHost = remember(tick, v) { ClipService.instance != null || Settings.canDrawOverlays(ctx) }
    fun changed(rebuild: Boolean = false) {
        v++
        if (rebuild) FloatBall.rebuild(ctx) else FloatBall.sync(ctx)
    }
    key(v) {
        PageColumn {
            SubHeader(tr("悬浮球", "Floating bubble"), onBack, R.drawable.ic_gen_pick,
                tr("在屏幕边上常驻一个小入口：点一下打开面板，按住拖动换位置，长按打开环形控制台。",
                    "A small handle on the edge of the screen: tap for the panel, drag to move it, long-press for a quick menu."))
            if (prefs.floatStyle == 1) MascotPreview()
            Group(tr("开关", "On / off")) {
                Toggle(tr("显示悬浮球", "Show the bubble"),
                    if (canHost) tr("开着增强模式时不需要悬浮窗权限", "Needs no overlay permission while enhanced mode is on")
                    else tr("需要先允许“显示在其他应用上层”，或开启增强模式", "Allow “Display over other apps” or turn on enhanced mode first"),
                    prefs.floatEnabled, icon = R.drawable.ic_gen_pick) { on ->
                    prefs.floatEnabled = on
                    if (on && !canHost) {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    changed()
                }
                val until = prefs.floatHiddenUntil
                if (until > System.currentTimeMillis()) {
                    Clickable(tr("今天已隐藏", "Hidden for today"),
                        tr("到 %s 自动恢复", "Comes back at %s", SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(until))),
                        tr("现在恢复", "Show now"), icon = R.drawable.ic_gen_history) { FloatBall.unhideNow(ctx); v++ }
                }
            }
            Group(tr("样式", "Style")) {
                Choice(tr("样子", "Look"), listOf(tr("看板娘卡卡", "Kaka"), tr("圆形图标", "Round icon")),
                    if (prefs.floatStyle == 1) 0 else 1, icon = R.drawable.ic_gen_palette) { prefs.floatStyle = if (it == 0) 1 else 0; changed(true) }
                Choice(tr("大小", "Size"), listOf(tr("小", "S"), tr("中", "M"), tr("大", "L")), prefs.floatSize, icon = R.drawable.ic_gen_grid) {
                    prefs.floatSize = it; changed(true)
                }
                Choice(tr("位置", "Placement"), listOf(tr("贴边", "Snap to edge"), tr("随意摆放", "Anywhere")),
                    if (prefs.floatFree) 1 else 0, icon = R.drawable.ic_gen_pick,
                    sub = if (prefs.floatFree) tr("拖到哪就停在哪，不会缩到边上", "Stays where you drop it")
                    else tr("松手自动贴到左边或右边，不用时缩到边上探头", "Snaps to the left or right edge and peeks out when idle")) {
                    prefs.floatFree = it == 1; changed(true)
                }
                val alphas = listOf(50, 75, 100)
                Choice(tr("收起时的不透明度", "Opacity when tucked away"), alphas.map { "$it%" },
                    alphas.indexOf(prefs.floatAlphaIdle).coerceAtLeast(0), icon = R.drawable.ic_gen_swap) {
                    prefs.floatAlphaIdle = alphas[it]; changed(true)
                }
            }
            if (prefs.floatStyle == 1) Group(tr("卡卡", "Kaka")) {
                Toggle(tr("随机小动作", "Random little moves"),
                    tr("没事时隔一阵随机招手、开心、打哈欠、蹦一下……每次不一样", "Now and then she waves, cheers, yawns or hops — a different one each time"),
                    prefs.floatIdleActs, icon = R.drawable.ic_gen_pick) { prefs.floatIdleActs = it; changed(true) }
            }
            Group(tr("记录", "Recording")) {
                Toggle(tr("暂停记录", "Pause recording"),
                    tr("暂停期间复制的内容不会记下来，卡卡会去睡觉。长按悬浮球也能切换", "Nothing you copy is recorded meanwhile, and Kaka takes a nap. Also in the long-press menu."),
                    prefs.capturePaused, icon = R.drawable.ic_gen_history) { prefs.capturePaused = it; FloatBall.refreshState(); v++ }
            }
            Group(tr("怎么用", "How to use")) {
                ShortcutTable(listOf(
                    tr("点一下", "Tap") to tr("打开 / 关闭面板", "Open / close the panel"),
                    tr("按住拖动", "Drag") to tr("换位置（贴边时松手自动贴到左边或右边）", "Move it (snaps to an edge unless placement is Anywhere)"),
                    tr("长按", "Long-press") to tr("环形控制台：滑到一项松手执行；在中心松手可以再点选", "Quick menu: slide to an item and let go, or let go in the middle and tap"),
                    tr("3 秒不动", "Idle 3 s") to tr("变淡；贴边时缩到边上探头", "Fades; peeks from the edge when snapping"),
                ))
            }
        }
    }
}

/** 设置页顶部的卡卡预览：点她换一个动作看看 */
@Composable
private fun MascotPreview() {
    val shows = remember { listOf(Mascot.WAVE, Mascot.POKE, Mascot.CATCH, Mascot.THROW, Mascot.HAPPY, Mascot.SHY, Mascot.BORED, Mascot.LAND) }
    var i by remember { mutableIntStateOf(-1) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.08f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { c -> SpriteView(c).also { s -> s.play(Mascot.IDLE) } },
            modifier = Modifier.size(112.dp).clickable {
                i = (i + 1) % shows.size
            },
            update = { s ->
                val k = i
                if (k >= 0 && s.tag != k) { s.tag = k; s.play(shows[k]) { s.play(Mascot.IDLE) } }
            },
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(tr("卡卡", "Kaka"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                tr("住在屏幕边上的文件夹猫娘。复制时接住卡片，粘贴时把卡片抛出去，暂停记录就去睡觉。点她看看别的动作。",
                    "A folder cat-girl living on the edge of your screen. She catches what you copy, throws what you paste, and naps while recording is paused. Tap her."),
                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
