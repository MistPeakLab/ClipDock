package com.mistpeaklab.clipdock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
 enableEdgeToEdge()
        super.onCreate(savedInstanceState)
 ClipStore.init(this)
 setContent { AppTheme { AppRoot() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
 super.onWindowFocusChanged(hasFocus)
 // 主界面在前台、有焦点：可以直接读系统剪贴板，无需任何权限
 if (hasFocus) ClipReader.capture(this)
    }
}

// ---------------- 主题 ----------------

private val Accent = Color(0xFF4C8DF6)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val prefs = Prefs(LocalContext.current)
    val dark = when (prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
 primary = Accent, background = Color(0xFF141518), surface = Color(0xFF1C1D21),
        surfaceVariant = Color(0xFF25272C), surfaceContainer = Color(0xFF1A1B1F),
 onSurface = Color(0xFFE8EAED), onSurfaceVariant = Color(0xFF9AA0A6), outlineVariant = Color(0x22FFFFFF),
    ) else lightColorScheme(
 primary = Accent, background = Color(0xFFF6F7FA), surface = Color.White,
 surfaceVariant = Color(0xFFF0F2F6), surfaceContainer = Color(0xFFEEF1F6),
        onSurface = Color(0xFF1F2328), onSurfaceVariant = Color(0xFF6B7280), outlineVariant = Color(0x14000000),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------- 结构 ----------------

enum class Page(val title: String, val icon: Int) {
    All("全部", R.drawable.ic_clip),
    Pinned("置顶", R.drawable.ic_pin),
    Phrases("常用语", R.drawable.ic_keyboard),
    Today("今天", R.drawable.ic_today),
    Links("链接", R.drawable.ic_link),
    Abilities("权限与模式", R.drawable.ic_shield),
    Settings("设置", R.drawable.ic_settings),
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(Page.All) }
    var query by remember { mutableStateOf("") }
    val items by ClipStore.items.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
 var drawerOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxSize().systemBarsPadding()) {
     if (wide) Sidebar(page, items, tick, Modifier.width(240.dp)) { page = it }
     Column(Modifier.weight(1f).fillMaxHeight()) {
  TopBar(page, query, { query = it }, showMenu = !wide) { drawerOpen = true }
  when (page) {
      Page.Abilities -> AbilitiesPage(tick)
      Page.Settings -> SettingsPage()
      Page.Phrases -> PhrasesPage(items.filter { it.phrase }, query)
      else -> ClipGrid(filter(items, page, query), page, query)
  }
     }
 }
 if (!wide && drawerOpen) {
     Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { drawerOpen = false })
     Sidebar(page, items, tick, Modifier.width(260.dp).fillMaxHeight().systemBarsPadding()) {
         page = it; drawerOpen = false
     }
 }
    }
}

private val urlRegex = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun filter(all: List<ClipItem>, page: Page, q: String): List<ClipItem> {
    val history = all.filter { !it.phrase }
    var l = when (page) {
        Page.Pinned -> history.filter { it.pinned }
        Page.Phrases -> all.filter { it.phrase }
        Page.Today -> startOfToday().let { t -> history.filter { it.time >= t } }
        Page.Links -> history.filter { urlRegex.containsMatchIn(it.text) }
 else -> history
    }
    if (q.isNotBlank()) l = l.filter { it.text.contains(q.trim(), ignoreCase = true) }
    return l
}

// ---------------- 侧边栏 ----------------

@Composable
fun Sidebar(page: Page, items: List<ClipItem>, tick: Int, modifier: Modifier, onSelect: (Page) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxHeight().background(cs.surfaceContainer).padding(horizontal = 12.dp, vertical = 16.dp)) {
 Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)) {
     Image(R.mipmap.ic_launcher_fg, Modifier.size(34.dp))
     Spacer(Modifier.width(8.dp))
     Column {
  Text("随贴", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
  Text("悬浮剪贴板", fontSize = 11.sp, color = cs.onSurfaceVariant)
     }
 }
 SectionLabel("剪贴板")
        listOf(Page.All, Page.Pinned, Page.Phrases, Page.Today, Page.Links).forEach { p ->
     NavItem(p, page == p, filter(items, p, "").size.toString()) { onSelect(p) }
 }
 Spacer(Modifier.height(16.dp))
 SectionLabel("应用")
 val level = remember(tick) { abilityLevel(ctx) }
 NavItem(Page.Abilities, page == Page.Abilities, if (level == 2) null else "!") { onSelect(Page.Abilities) }
        NavItem(Page.Settings, page == Page.Settings, null) { onSelect(Page.Settings) }
 Spacer(Modifier.weight(1f))
 ModeBadge(level) { onSelect(Page.Abilities) }
    }
}

@Composable
private fun SectionLabel(t: String) =
    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))

@Composable
private fun NavItem(p: Page, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
     .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
     .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
 verticalAlignment = Alignment.CenterVertically,
    ) {
 Icon(painterResource(p.icon), null, Modifier.size(20.dp), tint = if (selected) Accent else cs.onSurfaceVariant)
 Spacer(Modifier.width(12.dp))
        Text(p.title, Modifier.weight(1f), fontSize = 14.sp, color = if (selected) Accent else cs.onSurface,
     fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
 if (badge != null) {
     val warn = badge == "!"
     Text(badge, fontSize = 12.sp, color = if (warn) Color(0xFFE8833A) else cs.onSurfaceVariant,
         fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal)
 }
    }
}

/** 0 仅主界面，1 基础（悬浮窗），2 增强（无障碍） */
fun abilityLevel(ctx: android.content.Context) = when {
    ClipService.instance != null -> 2
    Settings.canDrawOverlays(ctx) -> 1
    else -> 0
}

@Composable
private fun ModeBadge(level: Int, onClick: () -> Unit) {
    val (label, desc, color) = when (level) {
 2 -> Triple("增强模式", "后台记录 · 快捷键唤起", Color(0xFF2FA866))
 1 -> Triple("基础模式", "悬浮面板 · 打开时记录", Accent)
 else -> Triple("仅主界面", "打开应用时记录", Color(0xFFE8833A))
    }
    Row(
 Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f))
     .clickable(onClick = onClick).padding(12.dp),
 verticalAlignment = Alignment.CenterVertically,
    ) {
 Box(Modifier.size(8.dp).clip(CircleShape).background(color))
 Spacer(Modifier.width(10.dp))
 Column {
     Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
     Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
    }
}

// ---------------- 顶栏 ----------------

@Composable
fun TopBar(page: Page, query: String, onQuery: (String) -> Unit, showMenu: Boolean, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenu) {
     IconButton(onClick = onMenu) { Icon(painterResource(R.drawable.ic_menu), "菜单", tint = cs.onSurface) }
     Spacer(Modifier.width(4.dp))
 }
        Text(page.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
 Spacer(Modifier.width(20.dp))
        if (page.ordinal <= Page.Links.ordinal) {
     Row(
  Modifier.weight(1f).widthIn(max = 520.dp).height(42.dp).clip(RoundedCornerShape(21.dp))
      .background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(21.dp)).padding(horizontal = 14.dp),
  verticalAlignment = Alignment.CenterVertically,
     ) {
  Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
  Spacer(Modifier.width(8.dp))
  Box(Modifier.weight(1f)) {
      if (query.isEmpty()) Text("搜索剪贴板", color = cs.onSurfaceVariant, fontSize = 14.sp)
      BasicTextField(query, onQuery, singleLine = true, cursorBrush = SolidColor(Accent),
   textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface, fontSize = 14.sp),
   modifier = Modifier.fillMaxWidth())
  }
     }
     Spacer(Modifier.width(12.dp))
     Spacer(Modifier.weight(0.01f))
     FilledTonalButton(onClick = {
  val ok = ClipReader.capture(ctx)
  Toast.makeText(ctx, if (ok) "已读取当前剪贴板" else "剪贴板为空", Toast.LENGTH_SHORT).show()
     }) {
  Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp)); Text("读取当前剪贴板")
     }
     Spacer(Modifier.width(8.dp))
     Button(onClick = {
  if (!PanelHost.show(ctx)) Toast.makeText(ctx, "需要先授予“显示在其他应用上层”权限", Toast.LENGTH_SHORT).show()
  else (ctx as? android.app.Activity)?.moveTaskToBack(true)
     }) {
  Icon(painterResource(R.drawable.ic_open), null, Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp)); Text("悬浮面板")
     }
        } else Spacer(Modifier.weight(1f))
    }
}

// ---------------- 卡片网格 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipGrid(list: List<ClipItem>, page: Page, query: String) {
    val ctx = LocalContext.current
    val current by ClipStore.current.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
 Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
     Icon(painterResource(R.drawable.ic_empty), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
     Spacer(Modifier.height(12.dp))
     Text(
  when {
      query.isNotBlank() -> "没有找到包含“$query”的内容"
      page == Page.Pinned -> "还没有置顶的内容"
      else -> "还没有记录，复制一些文字试试"
  },
  color = MaterialTheme.colorScheme.onSurfaceVariant,
     )
 }
 return
    }
    LazyVerticalStaggeredGrid(
 columns = StaggeredGridCells.Adaptive(260.dp),
 contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
 verticalItemSpacing = 12.dp,
 horizontalArrangement = Arrangement.spacedBy(12.dp),
 modifier = Modifier.fillMaxSize(),
    ) {
 items(list, key = { it.id }) { item ->
     ClipCard(item, isCurrent = item.text == current) {
  ClipReader.write(ctx, TextTools.prepare(Prefs(ctx), item.text))
  Toast.makeText(ctx, "已复制，按 Ctrl+V 粘贴", Toast.LENGTH_SHORT).show()
     }
 }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

private fun timeLabel(t: Long): String {
    val diff = System.currentTimeMillis() - t
    return when {
 diff < 60_000 -> "刚刚"
 diff < 3_600_000 -> "${diff / 60_000} 分钟前"
 t >= startOfToday() -> "今天 " + timeFmt.format(Date(t))
 else -> dateFmt.format(Date(t))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipCard(item: ClipItem, isCurrent: Boolean, onCopy: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val isLink = urlRegex.containsMatchIn(item.text)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
     .border(if (isCurrent) 1.5.dp else 1.dp, if (isCurrent) Accent else cs.outlineVariant, RoundedCornerShape(14.dp))
     .combinedClickable(onClick = onCopy, onLongClick = { expanded = !expanded })
     .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
    ) {
 Text(
     item.text.trim(), color = if (isLink) Accent else cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
     maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
     modifier = Modifier.padding(end = 8.dp),
 )
        Spacer(Modifier.height(8.dp))
 Row(verticalAlignment = Alignment.CenterVertically) {
     if (isCurrent) {
  Text("当前", fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
  Spacer(Modifier.width(6.dp))
     }
     Text("${timeLabel(item.time)} · ${item.text.length} 字", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
     SmallIcon(if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin, if (item.pinned) "取消置顶" else "置顶",
  if (item.pinned) Accent else cs.onSurfaceVariant) { ClipStore.togglePin(item.id) }
     SmallIcon(R.drawable.ic_add, "加入常用语", cs.onSurfaceVariant) {
          val ok = ClipStore.addPhrase(item.text)
   Toast.makeText(ctx, if (ok) "已加入常用语" else "常用语里已有这条", Toast.LENGTH_SHORT).show()
      }
      SmallIcon(R.drawable.ic_copy, "复制", cs.onSurfaceVariant, onCopy)
     SmallIcon(R.drawable.ic_delete, "删除", cs.onSurfaceVariant) { ClipStore.delete(item.id) }
 }
    }
}

@Composable
private fun SmallIcon(res: Int, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), desc, Modifier.size(17.dp), tint = tint)
    }
}

@Composable
fun Image(res: Int, modifier: Modifier) =
    androidx.compose.foundation.Image(painterResource(res), null, modifier)

// ---------------- 权限与模式 ----------------

@Composable
fun AbilitiesPage(tick: Int) {
    val ctx = LocalContext.current
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val a11y = remember(tick) { ClipService.instance != null }
    val battery = remember(tick) {
 ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    PageColumn {
 Explain(
     "为什么没有“允许读取剪贴板”的弹窗？",
     "从 Android 10 开始，系统只允许“当前在前台的应用”和“默认输入法”读取剪贴板。后台读取剪贴板的权限只发给系统应用，普通应用申请了也拿不到，所以不存在这种授权弹窗。\n\n" +
  "因此随贴分三档：不给任何权限也能用；给了悬浮窗权限可以随时唤出面板；开启无障碍服务后才能在后台自动记录、用快捷键唤起。",
 )
        Step(
     "主界面读取", "已可用",
     "打开随贴时自动读取当前剪贴板。无需任何权限。", true, null, null,
 )
 Step(
     "悬浮面板（基础模式）", if (overlay) "已开启" else "未开启",
     "授予“显示在其他应用上层”后，可以从桌面图标、通知栏开关、桌面长按菜单随时唤出悬浮面板；面板打开的瞬间会读取当前剪贴板。",
     overlay, "去授权",
 ) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
 Step(
     "后台记录 + 快捷键（增强模式）", if (a11y) "已开启" else "未开启",
     "开启随贴的无障碍服务后：后台自动记录每次复制；实体键盘按 Win+V（可改）唤起；面板不抢输入框焦点、不弹输入法；可选自动粘贴。\n" +
  "如果开关是灰的、提示“受限设置”：系统设置 › 应用 › 随贴 › 右上角 ⋮ › 允许受限设置。",
     a11y, "去开启",
 ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
 Step(
     "保持后台运行", if (battery) "已允许" else "建议开启",
     "防止系统清理服务导致快捷键失效、停止记录。",
     battery, "去设置",
 ) {
     runCatching {
  ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
     }.onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
 }
        Text("数据只保存在本机，应用没有申请联网权限。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
     modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Explain(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.08f)).padding(16.dp)) {
 Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
 Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Step(title: String, state: String, desc: String, ok: Boolean, action: String?, onAction: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
 Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
     .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
 Column(Modifier.weight(1f)) {
     Row(verticalAlignment = Alignment.CenterVertically) {
  Text(title, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
  Spacer(Modifier.width(8.dp))
  val c = if (ok) Color(0xFF2FA866) else Color(0xFFE8833A)
  Text(state, fontSize = 11.sp, color = c, modifier = Modifier.clip(RoundedCornerShape(4.dp))
      .background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
     }
     Spacer(Modifier.height(4.dp))
     Text(desc, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
 }
 if (action != null && onAction != null && !ok) {
     Spacer(Modifier.width(12.dp))
     Button(onClick = onAction) { Text(action) }
 }
    }
}

@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
 Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
     .padding(horizontal = 20.dp, vertical = 8.dp).widthIn(max = 760.dp),
 verticalArrangement = Arrangement.spacedBy(12.dp),
 content = content,
    )
}
