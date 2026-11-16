package com.mistpeaklab.clipdock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ClipStore.init(this)
        L.init(this)
        setContent { AppTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).secureWindow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // 定时任务偶尔会被系统清掉：排着就不动，没排才补上
        if (AutoBackup.enabled(Prefs(this)) && !AutoBackup.scheduled(this)) AutoBackup.schedule(this)
        AutoBackup.runIfDue(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 主界面在前台、有焦点：可以直接读系统剪贴板，无需任何权限
        if (hasFocus) ClipReader.capture(this, "FOREGROUND")
    }
}

// ---------------- 主题 ----------------

internal val Accent = Color(0xFF4C8DF6)
internal val Danger = Color(0xFFE5484D)
internal val Okay = Color(0xFF2FA866)
internal val Warn = Color(0xFFE8833A)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val prefs = Prefs(LocalContext.current)
    val dark = when (prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
        primary = Accent, background = Color(0xFF131417), surface = Color(0xFF202227),
        surfaceVariant = Color(0xFF2A2D34), surfaceContainer = Color(0xFF1B1D21),
        onSurface = Color(0xFFF0F2F5), onSurfaceVariant = Color(0xFFB4BAC4), outlineVariant = Color(0x33FFFFFF),
        // 弹窗、底部弹层、菜单、选中的筛选标签：都用中性灰 + 品牌蓝，不要 M3 默认的淡紫
        surfaceContainerLowest = Color(0xFF16171A), surfaceContainerLow = Color(0xFF1E2025),
        surfaceContainerHigh = Color(0xFF25282E), surfaceContainerHighest = Color(0xFF2C2F36),
        surfaceTint = Color.Transparent, secondaryContainer = Color(0xFF26354F), onSecondaryContainer = Color(0xFFD6E4FF),
        primaryContainer = Color(0xFF26354F), onPrimaryContainer = Color(0xFFD6E4FF),
    ) else lightColorScheme(
        primary = Accent, background = Color(0xFFF6F7FA), surface = Color.White,
        surfaceVariant = Color(0xFFF0F2F6), surfaceContainer = Color(0xFFEEF1F6),
        onSurface = Color(0xFF1F2328), onSurfaceVariant = Color(0xFF5F6672), outlineVariant = Color(0x1F000000),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FB),
        surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFF0F2F6),
        surfaceTint = Color.Transparent, secondaryContainer = Color(0xFFE3EDFE), onSecondaryContainer = Color(0xFF1C4FA8),
        primaryContainer = Color(0xFFE3EDFE), onPrimaryContainer = Color(0xFF1C4FA8),
    )
    // Text 没写 color 时用 LocalContentColor；默认是黑色，深色模式下会看不见。
    // 在主题这一层给出正确的前景色，所有页面和弹窗（包括 Dialog）都跟着生效；
    // Surface、按钮等组件自己会再覆盖成各自需要的颜色。
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------------- 结构 ----------------

/** 三个主区：记录（会过期的）、收藏（长期留着的）、我的（设置等） */
enum class Tab { Records, Favorites, Me }

/** “我的”下面的子页面 */
enum class Sub { None, Permissions, Features, Panel, Paste, Record, Privacy, Rules, Backup, Trash, Diag, Look }

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var onboarded by remember { mutableStateOf(prefs.onboardingDone) }
    if (!onboarded) {
        Onboarding { prefs.onboardingDone = true; onboarded = true }
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Records) }
    var sub by rememberSaveable { mutableStateOf(Sub.None) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    // 应用刚启动时无障碍服务可能还没连上，稍后再查一次，免得误报“已被系统停止”
    LaunchedEffect(Unit) { delay(2500); tick++ }
    val notice by ClipStore.notice.collectAsStateWithLifecycle()
    val serviceStopped = remember(tick) { tick > 0 && ClipService.instance == null && ctx.isClipServiceEnabledInSystem() }
    BackHandler(sub != Sub.None || tab != Tab.Records) { if (sub != Sub.None) sub = Sub.None else tab = Tab.Records }
    val open: (Sub) -> Unit = { tab = Tab.Me; sub = it }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
        val content: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                if (serviceStopped && sub != Sub.Diag) StoppedBanner { open(Sub.Diag) }
                notice?.let { NoticeBanner(it) { ClipStore.clearNotice() } }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Records -> RecordsPage(tick) { open(it) }
                        Tab.Favorites -> FavoritesPage()
                        Tab.Me -> if (sub == Sub.None) MePage(tick, open) else SubPage(sub, tick, open) { sub = Sub.None }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                Sidebar(tab, tick, Modifier.width(232.dp), onOpen = open) { tab = it; sub = Sub.None }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.weight(1f)) { content() }
                BottomBar(tab) { tab = it; sub = Sub.None }
            }
        }
    }
}

@Composable
private fun SubPage(sub: Sub, tick: Int, open: (Sub) -> Unit, onBack: () -> Unit) = when (sub) {
    Sub.Permissions -> PermissionsPage(tick, onBack)
    Sub.Features -> FeaturesPage(onBack)
    Sub.Panel -> PanelSettingsPage(onBack)
    Sub.Paste -> PasteSettingsPage(onBack)
    Sub.Record -> RecordSettingsPage(onBack) { open(Sub.Trash) }
    Sub.Privacy -> PrivacyPage(onBack) { open(Sub.Rules) }
    Sub.Rules -> AppRulesPage { open(Sub.Privacy) }
    Sub.Backup -> BackupPage(onBack)
    Sub.Trash -> TrashPage(onBack)
    Sub.Diag -> DiagPage(tick, onBack)
    Sub.Look -> LookPage(onBack)
    Sub.None -> {}
}

/** 0 仅主界面，1 基础（悬浮窗），2 增强（无障碍） */
fun abilityLevel(ctx: android.content.Context) = when {
    ClipService.instance != null -> 2
    Settings.canDrawOverlays(ctx) -> 1
    else -> 0
}

// ---------------- 侧边栏 / 底部导航 ----------------

@Composable
private fun Sidebar(tab: Tab, tick: Int, modifier: Modifier, onOpen: (Sub) -> Unit, onSelect: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val items by ClipStore.items.collectAsStateWithLifecycle()
    val counts = remember(items) { items.count { !it.phrase } to items.count { it.phrase || it.pinned } }
    Column(modifier.fillMaxHeight().background(cs.surfaceContainer).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)) {
            androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher_fg), null, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(tr("随贴", "ClipDock"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(tr("悬浮剪贴板", "Floating clipboard"), fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
        }
        NavItem(R.drawable.ic_gen_history, tr("记录", "History"), tab == Tab.Records, counts.first.toString()) { onSelect(Tab.Records) }
        NavItem(R.drawable.ic_gen_star, tr("收藏", "Favorites"), tab == Tab.Favorites, counts.second.toString()) { onSelect(Tab.Favorites) }
        NavItem(R.drawable.ic_gen_person, tr("我的", "Me"), tab == Tab.Me, null) { onSelect(Tab.Me) }
        Spacer(Modifier.weight(1f))
        val level = remember(tick) { abilityLevel(ctx) }
        ModeBadge(level) { onOpen(Sub.Permissions) }
    }
}

@Composable
private fun NavItem(icon: Int, title: String, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (selected) Accent else cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 14.sp, color = if (selected) Accent else cs.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (badge != null) Text(badge, fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    // 外层已经让出了系统栏的位置，这里不要再让一次
    NavigationBar(windowInsets = WindowInsets(0)) {
        listOf(
            Triple(Tab.Records, R.drawable.ic_gen_history, tr("记录", "History")),
            Triple(Tab.Favorites, R.drawable.ic_gen_star, tr("收藏", "Favorites")),
            Triple(Tab.Me, R.drawable.ic_gen_person, tr("我的", "Me")),
        ).forEach { (t, icon, label) ->
            NavigationBarItem(
                selected = tab == t, onClick = { onSelect(t) },
                icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun ModeBadge(level: Int, onClick: () -> Unit) {
    val (label, desc, color) = when (level) {
        2 -> Triple(tr("增强模式", "Enhanced"), tr("后台记录 · 快捷键唤起", "Background recording · hotkey"), Okay)
        1 -> Triple(tr("基础模式", "Basic"), tr("悬浮面板 · 打开时记录", "Floating panel · records when opened"), Accent)
        else -> Triple(tr("仅主界面", "App only"), tr("打开应用时记录", "Records when the app is opened"), Warn)
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

// ---------------- 横幅 ----------------

/** 存储出了问题时的提示（记录读不出来、有改动没存上…），点“知道了”收起 */
@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Danger.copy(alpha = 0.12f)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = onDismiss) { Text(tr("知道了", "OK")) }
    }
}

/** 系统设置里开着无障碍，但服务没在运行：快捷键和后台记录都已失效，用户往往不知道 */
@Composable
private fun StoppedBanner(onDetail: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Warn.copy(alpha = 0.14f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("增强模式已被系统停止", "Enhanced mode was stopped by the system"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Warn)
            Text(
                tr("快捷键和后台记录暂时不可用。到无障碍设置里把随贴关掉再重新打开即可恢复。",
                    "Hotkey and background recording are off. Turn ClipDock off and on again in Accessibility settings."),
                fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDetail) { Text(tr("诊断", "Details")) }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(tr("去处理", "Fix")) }
    }
}

// ---------------- 页头、搜索框 ----------------

@Composable
fun PageHeader(title: String, query: String? = null, onQuery: (String) -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(title, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            actions()
        }
        if (query != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.padding(end = 8.dp).widthIn(max = 640.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp))
                    .background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(21.dp)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_gen_search), null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tr("搜索（支持拼音首字母）", "Search"), color = cs.onSurfaceVariant, fontSize = 14.sp)
                    BasicTextField(query, onQuery, singleLine = true, cursorBrush = SolidColor(Accent),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) SmallIcon(R.drawable.ic_gen_close, tr("清空", "Clear"), cs.onSurfaceVariant) { onQuery("") }
            }
        }
    }
}

@Composable
fun HeaderIcon(res: Int, desc: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(res), desc, Modifier.size(22.dp), tint = tint) }
}

@Composable
fun SmallIcon(res: Int, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), desc, Modifier.size(18.dp), tint = tint)
    }
}

/** 设置类页面的滚动容器：平板上居中、最宽 760dp，手机上铺满 */
@Composable
fun PageColumn(
    scroll: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight().verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * 子页面的页头：圆形返回按钮 + 标题。
 * 给了 icon / desc 时，下面再放一块“这一页是干什么的”的说明：大图标 + 一句话。
 */
@Composable
fun SubHeader(title: String, onBack: () -> Unit, icon: Int? = null, desc: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(cs.surface).border(1.dp, cs.outlineVariant, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", fontSize = 18.sp, color = cs.onSurface) }
            Spacer(Modifier.weight(1f))
            actions()
        }
        if (icon == null) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.padding(top = 10.dp))
        } else {
            Row(Modifier.padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(icon), null, Modifier.size(26.dp), tint = Accent) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    if (desc != null) Text(desc, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// ---------------- 时间 ----------------

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

fun timeLabel(t: Long): String {
    val diff = System.currentTimeMillis() - t
    return when {
        diff < 60_000 -> tr("刚刚", "just now")
        diff < 3_600_000 -> tr("%d 分钟前", "%d min ago", diff / 60_000)
        t >= startOfToday() -> tr("今天 ", "today ") + timeFmt.format(Date(t))
        else -> SimpleDateFormat(tr("M月d日 HH:mm", "MMM d, HH:mm"), Locale.getDefault()).format(Date(t))
    }
}
