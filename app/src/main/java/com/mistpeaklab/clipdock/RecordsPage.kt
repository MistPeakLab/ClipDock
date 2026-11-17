package com.mistpeaklab.clipdock

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.util.ContentKind
import com.mistpeaklab.clipdock.util.Search
import com.mistpeaklab.clipdock.util.TextTransforms
import com.mistpeaklab.clipdock.util.Transform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------- 筛选 ----------------

private class Chip(val key: String, val label: String, val count: Int)

private const val F_ALL = "all"
private const val F_TODAY = "today"
private const val F_LINK = "link"

private fun applyFilter(list: List<ClipItem>, key: String): List<ClipItem> = when {
    key == F_TODAY -> startOfToday().let { t -> list.filter { it.time >= t } }
    key == F_LINK -> list.filter { it.hasLink }
    key.startsWith("kind:") -> list.filter { it.kind.name == key.substring(5) }
    key.startsWith("app:") -> list.filter { it.sourcePkg == key.substring(4) }
    key == "phrases" -> list.filter { it.phrase }
    key == "pinned" -> list.filter { !it.phrase && it.pinned }
    key.startsWith("group:") -> list.filter { it.groupId == key.substring(6) }
    else -> list
}

@Composable
private fun ChipRow(chips: List<Chip>, selected: String, onSelect: (String) -> Unit) {
    if (chips.size <= 1) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.key }) { c ->
            FilterChip(
                selected = c.key == selected, onClick = { onSelect(c.key) },
                label = { Text(if (c.count >= 0) "${c.label} ${c.count}" else c.label, fontSize = 13.sp) },
            )
        }
    }
}

// ---------------- 记录 ----------------

@Composable
fun RecordsPage(tick: Int, onOpen: (Sub) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val items by ClipStore.items.collectAsStateWithLifecycle()
    val loaded by ClipStore.loaded.collectAsStateWithLifecycle()
    val collect by ClipStore.collect.collectAsStateWithLifecycle()
    val fv by Features.version.collectAsStateWithLifecycle()
    val kindOn = remember(fv) { Features.isOn(ctx, Feature.CONTENT_KIND) }
    val sourceOn = remember(fv) { Features.isOn(ctx, Feature.SOURCE_APP) }
    val pinyin = remember(fv) { Features.isOn(ctx, Feature.PINYIN_SEARCH) }
    val collectOn = remember(fv) { Features.isOn(ctx, Feature.COLLECT) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(F_ALL) }
    var layout by rememberSaveable { mutableIntStateOf(prefs.recordsLayout) }
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selectMode by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ClipItem?>(null) }
    var wheelFor by remember { mutableStateOf<ClipItem?>(null) }
    var wheelAnchor by remember { mutableStateOf<Rect?>(null) }
    var quick by remember { mutableStateOf<MenuState?>(null) }
    var dialogItem by remember { mutableStateOf<ClipItem?>(null) }
    val phone = LocalConfiguration.current.screenWidthDp < 600

    val history = remember(items) { items.filter { !it.phrase } }
    // tick 也算进去：过了零点，“今天”要跟着变
    val chips = remember(history, kindOn, sourceOn, tick) {
        val out = ArrayList<Chip>()
        out += Chip(F_ALL, tr("全部", "All"), history.size)
        out += Chip(F_TODAY, tr("今天", "Today"), applyFilter(history, F_TODAY).size)
        out += Chip(F_LINK, tr("链接", "Links"), history.count { it.hasLink })
        if (kindOn) {
            val byKind = history.groupingBy { it.kind }.eachCount()
            for (k in ContentKind.entries) if (k != ContentKind.TEXT && k != ContentKind.URL) byKind[k]?.let { out += Chip("kind:" + k.name, Kinds.label(k), it) }
        }
        if (sourceOn) {
            history.mapNotNull { it.sourcePkg }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
                .forEach { (pkg, n) -> out += Chip("app:$pkg", AppInfo.label(ctx, pkg), n) }
        }
        out
    }
    val shown = remember(history, filter, query, pinyin, tick) {
        val l = applyFilter(history, filter)
        val terms = Search.terms(query)
        if (terms.isEmpty()) l else l.filter { it.matches(terms, pinyin) }
    }
    val focused = shown.firstOrNull { it.id == focusId } ?: items.firstOrNull { it.id == focusId }

    LaunchedEffect(items, shown) {
        if (focusId != null && items.none { it.id == focusId }) focusId = null
        else if (focusId != null && shown.none { it.id == focusId }) focusId = null
    }

    fun toggleSelect(item: ClipItem) {
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    fun openDialog(item: ClipItem, state: MenuState) {
        dialogItem = item
        quick = state
    }

    fun run(action: ItemAction, item: ClipItem) {
        when (action) {
            ItemAction.Copy -> Act.copy(ctx, item)
            ItemAction.View -> Act.withText(ctx, item) { openDialog(item, MenuState.Viewer(it)) }
            ItemAction.Transform -> Act.withText(ctx, item) { openDialog(item, MenuState.Transform(it)) }
            ItemAction.Pin -> ClipStore.togglePin(item.id)
            ItemAction.Group -> openDialog(item, MenuState.Group)
            ItemAction.Note -> openDialog(item, MenuState.Note)
            ItemAction.Hide -> {
                if (item.hidden) {
                    Unlock.run(ctx) { if (!ClipStore.unhide(item.id)) ctx.toast(tr("这条内容解不开了", "Can't decrypt this item")) }
                } else {
                    ctx.toast(if (ClipStore.hide(item.id)) tr("已隐藏", "Hidden") else tr("这条不能隐藏（太长，或这台设备不支持加密）", "Can't hide this (too long, or encryption unavailable)"))
                }
            }
            ItemAction.Share -> Act.withText(ctx, item) { Act.share(ctx, it) }
            ItemAction.Edit -> Act.withText(ctx, item) { openDialog(item, MenuState.Edit(it)) }
            ItemAction.Delete -> {
                ClipStore.delete(item.id)
                Undo.offer(ctx, listOf(item.id))
                if (focusId == item.id) focusId = null
            }
            ItemAction.More -> menuFor = item
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val list = shown
        if (list.isEmpty()) return false
        val idx = list.indexOfFirst { it.id == focusId }.let { if (it < 0) 0 else it }
        val step = when (e.key) {
            Key.DirectionDown -> 1
            Key.DirectionUp -> -1
            else -> 0
        }
        if (step != 0) {
            focusId = list[(idx + step).coerceIn(0, list.lastIndex)].id
            return true
        }
        if (e.key == Key.Enter && !e.isCtrlPressed) { focused?.let { run(ItemAction.Copy, it) }; return true }
        if (e.isCtrlPressed && e.key == Key.C) { focused?.let { run(ItemAction.Copy, it) }; return true }
        if (e.key == Key.DirectionRight) { focused?.let { run(ItemAction.View, it) }; return true }
        if (e.key == Key.Delete || e.key == Key.Backspace) { focused?.let { run(ItemAction.Delete, it) }; return true }
        if (e.key == Key.Escape) { focusId = null; selected = emptySet(); selectMode = false; return true }
        return false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("记录", "History"), query, { query = it }) {
            HeaderIcon(
                if (layout == 0) R.drawable.ic_gen_list else R.drawable.ic_gen_grid,
                if (layout == 0) tr("详情视图", "Detail view") else tr("聚拢视图", "Card view"),
            ) {
                layout = 1 - layout
                prefs.recordsLayout = layout
                focusId = null
            }
            HeaderIcon(
                R.drawable.ic_gen_check, tr("多选", "Select"),
                tint = if (selectMode) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                selectMode = !selectMode
                if (!selectMode) selected = emptySet()
            }
            HeaderIcon(R.drawable.ic_gen_swap, tr("导入导出", "Import & export")) { onOpen(Sub.Backup) }
        }
        ChipRow(chips, if (chips.any { it.key == filter }) filter else F_ALL) { filter = it }
        if (collectOn) CollectBar(collect)
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 820.dp
            when {
                layout == 0 -> GatheredRecords(
                    ctx, shown, loaded, fv, phone, focusId, selectMode, selected,
                    empty = if (query.isNotBlank()) tr("没有找到包含“%s”的内容", "Nothing matches “%s”", query)
                    else tr("还没有记录，复制一些文字试试", "Nothing yet. Copy some text and it shows up here."),
                    onFocus = { focusId = it.id },
                    onToggle = ::toggleSelect,
                    onMenu = { menuFor = it },
                    onWheel = { item, rect -> wheelAnchor = rect; wheelFor = item },
                    onKey = ::onKey,
                )
                else -> DetailRecords(
                    ctx, shown, loaded, fv, focusId, phone,
                    empty = if (query.isNotBlank()) tr("没有找到包含“%s”的内容", "Nothing matches “%s”", query)
                    else tr("还没有记录，复制一些文字试试", "Nothing yet. Copy some text and it shows up here."),
                    onFocus = { focusId = it.id },
                    onWheel = { wheelAnchor = null; wheelFor = it },
                    onAction = ::run,
                    onClearFocus = { focusId = null },
                    onKey = ::onKey,
                    wide = wide,
                )
            }
            if (layout == 0 && focused != null && !selectMode && selected.isEmpty()) {
                GatheredDock(focused, phone, Modifier.align(Alignment.BottomCenter), onAction = { run(it, focused) })
            }
        }
        if (selected.isNotEmpty()) SelectionBar(shown, selected) { selected = it }
        if (layout == 1 && !phone) KeyHints()
    }

    menuFor?.let { ItemMenu(it) { menuFor = null } }
    wheelFor?.let { item ->
        ActionWheel(item, wheelAnchor, onAction = { run(it, item) }, onDismiss = { wheelFor = null; wheelAnchor = null })
    }
    when (val d = quick) {
        is MenuState.Viewer -> TextViewerDialog(d.text) { quick = null; dialogItem = null }
        is MenuState.Transform -> TransformDialog(d.text) { quick = null; dialogItem = null }
        is MenuState.Edit -> TextEditDialog(tr("编辑", "Edit"), d.text) { t ->
            if (t != null) dialogItem?.let { ClipStore.updateText(it.id, t) }
            quick = null; dialogItem = null
        }
        MenuState.Note -> TextEditDialog(tr("备注", "Note"), dialogItem?.note ?: "", singleLine = true, allowEmpty = true) { t ->
            if (t != null) dialogItem?.let { ClipStore.setNote(it.id, t) }
            quick = null; dialogItem = null
        }
        MenuState.Group -> GroupPickerDialog(dialogItem?.groupId) { gid, chosen ->
            if (chosen) dialogItem?.let { ClipStore.setGroup(listOf(it.id), gid) }
            quick = null; dialogItem = null
        }
        else -> {}
    }
}

private enum class ItemAction { Copy, View, Transform, Pin, Group, Note, Hide, Share, Edit, Delete, More }

@Composable
private fun GatheredRecords(
    ctx: Context, list: List<ClipItem>, loaded: Boolean, fv: Int, phone: Boolean,
    focusId: String?, selectMode: Boolean, picked: Set<String>, empty: String,
    onFocus: (ClipItem) -> Unit, onToggle: (ClipItem) -> Unit,
    onMenu: (ClipItem) -> Unit, onWheel: (ClipItem, Rect) -> Unit,
    onKey: (KeyEvent) -> Boolean,
) {
    val current by ClipStore.current.collectAsStateWithLifecycle()
    val bounds = remember { mutableMapOf<String, Rect>() }
    val fr = remember { FocusRequester() }
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(Unit) { if (!phone) runCatching { fr.requestFocus() } }
    // 只在选中的那一条不在屏幕上时才滚过去（键盘 ↑↓ 走到屏幕外）；点一下可见的卡片不要让列表跳
    LaunchedEffect(focusId) {
        val i = list.indexOfFirst { it.id == focusId }
        if (i < 0) return@LaunchedEffect
        val info = gridState.layoutInfo
        val v = info.visibleItemsInfo.firstOrNull { it.index == i }
        val fully = v != null && v.offset.y >= info.viewportStartOffset && v.offset.y + v.size.height <= info.viewportEndOffset - 80
        if (!fully) gridState.animateScrollToItem(i)
    }
    Box(Modifier.fillMaxSize().focusRequester(fr).focusable().onKeyEvent { onKey(it) }) {
        if (list.isEmpty()) {
            EmptyRecords(loaded, empty)
            return@Box
        }
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Adaptive(if (phone) 168.dp else 280.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(list, key = { _, item -> item.id }) { _, item ->
                ItemCard(
                    item, fv, isCurrent = !item.hidden && item.text == current,
                    picked = item.id == focusId || item.id in picked,
                    selecting = selectMode || picked.isNotEmpty(),
                    onClick = {
                        if (selectMode) onToggle(item) else { fr.requestFocus(); onFocus(item) }
                    },
                    onLongClick = { if (selectMode) onToggle(item) else onWheel(item, bounds[item.id] ?: Rect.Zero) },
                    onMenu = { onMenu(item) },
                    reorder = null,
                    onDoubleClick = { Act.copy(ctx, item) },
                    modifier = Modifier.onGloballyPositioned { bounds[item.id] = it.boundsInWindow() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailRecords(
    ctx: Context, list: List<ClipItem>, loaded: Boolean, fv: Int, focusId: String?, phone: Boolean,
    empty: String, onFocus: (ClipItem) -> Unit, onWheel: (ClipItem) -> Unit,
    onAction: (ItemAction, ClipItem) -> Unit, onClearFocus: () -> Unit,
    onKey: (KeyEvent) -> Boolean, wide: Boolean,
) {
    val focused = list.firstOrNull { it.id == focusId }
    val fr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(wide) { if (wide) runCatching { fr.requestFocus() } }
    LaunchedEffect(focusId) {
        val i = list.indexOfFirst { it.id == focusId }
        if (i < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val v = info.visibleItemsInfo.firstOrNull { it.index == i }
        val fully = v != null && v.offset >= info.viewportStartOffset && v.offset + v.size <= info.viewportEndOffset
        if (!fully) listState.animateScrollToItem(i)
    }
    val dense = @Composable { modifier: Modifier ->
        Box(modifier.focusRequester(fr).focusable().onKeyEvent { onKey(it) }) {
            if (list.isEmpty()) {
                EmptyRecords(loaded, empty)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(list, key = { _, item -> item.id }) { _, item ->
                        RecordRow(
                            ctx, item,
                            selected = item.id == focusId,
                            onClick = { fr.requestFocus(); onFocus(item) },
                            onDoubleClick = { Act.copy(ctx, item) },
                            onLongClick = { onWheel(item) },
                        )
                    }
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            dense(Modifier.weight(0.9f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            RecordInspector(ctx, focused, onAction, Modifier.weight(1.1f).fillMaxHeight())
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            dense(Modifier.fillMaxSize())
            if (focused != null) {
                ModalBottomSheet(
                    onDismissRequest = onClearFocus,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    RecordInspector(ctx, focused, onAction, Modifier.fillMaxWidth().height(560.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(
    ctx: Context, item: ClipItem, selected: Boolean,
    onClick: () -> Unit, onDoubleClick: () -> Unit, onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordTypeBadge(item)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (item.hidden) tr("已隐藏的内容 · 点按验证后复制", "Hidden · tap to verify and copy") else item.preview,
                fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (item.hasLink) Accent else cs.onSurface,
            )
            Text(
                recordMeta(ctx, item),
                fontSize = 10.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (item.pinned) Text("★", color = Accent, fontSize = 12.sp)
    }
}

private fun recordMeta(ctx: Context, item: ClipItem): String {
    val out = ArrayList<String>(3)
    out += timeLabel(item.time)
    out += tr("%d 字", "%d chars", item.chars)
    item.sourcePkg?.let { out += AppInfo.label(ctx, it) }
    return out.joinToString(" · ")
}

@Composable
private fun RecordTypeBadge(item: ClipItem) {
    val cs = MaterialTheme.colorScheme
    val tint = when {
        item.hidden -> cs.onSurfaceVariant
        item.kind == ContentKind.URL -> Accent
        item.kind == ContentKind.PHONE -> Okay
        item.kind == ContentKind.EMAIL -> Accent
        else -> cs.onSurfaceVariant
    }
    val icon = when {
        item.hidden -> R.drawable.ic_gen_lock
        item.kind == ContentKind.URL -> R.drawable.ic_gen_open
        else -> R.drawable.ic_gen_note
    }
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(15.dp), tint = tint)
    }
}

/**
 * 详情视图右侧：这一条的“阅读区”。
 * 上面是和动作条同一套的横向动作胶囊（复制 / 拆开 / 变换 / 分享 / 更多），
 * 中间是可以选中文字的全文（占满剩余高度），下面是信息和整理动作。
 */
@Composable
private fun RecordInspector(
    ctx: Context, item: ClipItem?, onAction: (ItemAction, ClipItem) -> Unit, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    if (item == null) {
        Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_empty), null, Modifier.size(64.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(10.dp))
            Text(tr("选一条记录，全文和动作会出现在这里", "Pick an item; its full text and actions appear here"), fontSize = 13.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(tr("↑ ↓ 选择 · Enter 复制 · 双击复制 · 长按打开动作轮", "↑ ↓ move · Enter copy · double-click copy · long-press for the wheel"), fontSize = 11.5.sp, color = cs.onSurfaceVariant)
        }
        return
    }
    val on = { f: Feature -> Features.isOn(ctx, f) }
    val light = cs.background.luminance() > 0.5f
    BoxWithConstraints(modifier) {
    // 窄（手机底部弹层、平板分屏）：动作胶囊里各项等分、图标在上文字在下，不横向滚动
    val compact = maxWidth < 480.dp
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        // 标题行：类型 + 时间等，右边收藏
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordTypeBadge(item)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (item.hidden) tr("已隐藏的内容", "Hidden item") else Kinds.label(item.kind), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(recordMeta(ctx, item), fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (item.pinned) Accent.copy(alpha = 0.12f) else cs.surfaceVariant)
                    .clickable { onAction(ItemAction.Pin, item) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star),
                    if (item.pinned) tr("取消收藏", "Unstar") else tr("收藏", "Star"), Modifier.size(19.dp), tint = if (item.pinned) Accent else cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 动作胶囊：和记录页的动作条同一套样式
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(if (light) Color(0xFFF3F4F7) else Color(0xFF2A2C32))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(26.dp)).padding(5.dp)
                .then(if (compact) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(Accent).clickable { onAction(ItemAction.Copy, item) }
                    .padding(horizontal = if (compact) 14.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_gen_copy), null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(7.dp))
                Text(tr("复制", "Copy"), color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(2.dp))
            val acts = buildList {
                if (!item.hidden) add(Triple(R.drawable.ic_gen_pick, tr("拆开", "Pick"), ItemAction.View))
                if (!item.hidden && on(Feature.TRANSFORM)) add(Triple(R.drawable.ic_gen_transform, tr("变换", "Transform"), ItemAction.Transform))
                add(Triple(R.drawable.ic_gen_share, tr("分享", "Share"), ItemAction.Share))
                add(Triple(R.drawable.ic_gen_more, tr("更多", "More"), ItemAction.More))
            }
            for ((ic, label, act) in acts) {
                if (compact) Column(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(14.dp)).clickable { onAction(act, item) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(ic), null, Modifier.size(17.dp), tint = cs.onSurface)
                    Text(label, fontSize = 10.5.sp, lineHeight = 13.sp, color = cs.onSurface, maxLines = 1)
                }
                else DockItem(ic, label, cs.onSurface) { onAction(act, item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 全文：占满剩余高度，可以直接选中一部分复制
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(cs.surface)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            if (item.hidden) {
                Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(R.drawable.ic_gen_lock), null, Modifier.size(32.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("已隐藏。验证身份后才能查看或复制。", "Hidden. Verify to view or copy it."), fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            } else {
                val shown = remember(item.id, item.text) { item.text.take(20_000) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SelectionContainer {
                        Text(shown, fontSize = 15.sp, lineHeight = 24.sp, color = if (item.hasLink) Accent else cs.onSurface)
                    }
                    if (item.text.length > shown.length) Text(tr("（太长了，这里只显示前 2 万字；“拆开”里能看到全文）", "(Too long: showing the first 20,000 characters; Pick shows all)"),
                        fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (item.note != null) {
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.08f))
                .clickable { onAction(ItemAction.Note, item) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gen_note), null, Modifier.size(15.dp), tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text(item.note, fontSize = 13.sp, color = Accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 整理：小胶囊按钮，排不下就换行
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (on(Feature.GROUP)) OrganizeChip(R.drawable.ic_gen_folder, tr("分组", "Group")) { onAction(ItemAction.Group, item) }
            if (on(Feature.NOTE)) OrganizeChip(R.drawable.ic_gen_note, if (item.note == null) tr("备注", "Note") else tr("改备注", "Edit note")) { onAction(ItemAction.Note, item) }
            if (on(Feature.HIDE)) OrganizeChip(R.drawable.ic_gen_lock, if (item.hidden) tr("取消隐藏", "Unhide") else tr("隐藏", "Hide")) { onAction(ItemAction.Hide, item) }
            if (!item.hidden) OrganizeChip(R.drawable.ic_gen_edit, tr("编辑", "Edit")) { onAction(ItemAction.Edit, item) }
            OrganizeChip(R.drawable.ic_gen_trash, tr("删除", "Delete"), danger = true) { onAction(ItemAction.Delete, item) }
        }
    }
    }
}

@Composable
private fun OrganizeChip(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (danger) Danger else cs.onSurface
    Row(
        Modifier.height(38.dp).clip(CircleShape).border(1.dp, if (danger) Danger.copy(alpha = 0.4f) else cs.outlineVariant, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = if (danger) Danger else cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = fg)
    }
}

/**
 * 动作条：点选一条后出现，放“用这条内容”的动作（复制、拆开、变换、收藏、更多）。
 * 浮在卡片上方，外面完全透明；颜色跟随浅色 / 深色主题。
 */
@Composable
private fun GatheredDock(item: ClipItem, phone: Boolean, modifier: Modifier = Modifier, onAction: (ItemAction) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val light = cs.background.luminance() > 0.5f
    val dockBg = if (light) Color(0xFFF3F4F7) else Color(0xFF2A2C32)
    val divider = cs.outlineVariant
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(shape).background(dockBg).border(1.dp, divider, shape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Accent)
                    .clickable { onAction(ItemAction.Copy) }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_gen_copy), null, Modifier.size(19.dp), tint = Color.White)
                Spacer(Modifier.width(7.dp))
                Text(tr("复制", "Copy"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(4.dp))
            if (!item.hidden) DockItem(R.drawable.ic_gen_pick, tr("拆开", "Pick"), cs.onSurface) { onAction(ItemAction.View) }
            if (!item.hidden && Features.isOn(ctx, Feature.TRANSFORM)) { DockDivider(divider); DockItem(R.drawable.ic_gen_transform, tr("变换", "Transform"), cs.onSurface) { onAction(ItemAction.Transform) } }
            if (!phone) {
                DockDivider(divider)
                DockItem(if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star,
                    if (item.pinned) tr("已收藏", "Starred") else tr("收藏", "Star"),
                    cs.onSurface, tint = if (item.pinned) Accent else null) { onAction(ItemAction.Pin) }
            }
            DockDivider(divider)
            DockItem(R.drawable.ic_gen_more, tr("更多", "More"), cs.onSurface) { onAction(ItemAction.More) }
        }
    }
}

@Composable
private fun DockDivider(color: Color) {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(22.dp).background(color))
}

@Composable
private fun DockItem(icon: Int, label: String, fg: Color, tint: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), label, Modifier.size(19.dp), tint = tint ?: fg.copy(alpha = 0.85f))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun EmptyRecords(loaded: Boolean, text: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_empty), null, Modifier.size(72.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        Text(if (!loaded) tr("正在读取记录…", "Loading…") else text, color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun KeyHints() {
    Text(
        tr("↑↓ 选择 · Enter 复制 · → 查看全文 · Delete 删除 · 长按动作轮",
            "↑↓ select · Enter copy · → view all · Delete delete · long-press for actions"),
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * 动作轮：长按一条时出现，放“整理这一条”的动作（收藏、分组、备注、隐藏、分享、删除），中间是复制。
 * 和动作条分工：动作条管“用”，动作轮管“整理”，两边几乎不重复。
 * 样式按 0.6.1 组件图：实心圆按钮 + 细描边，按钮之间用粗的浅色弧线（带轮廓）连起来。
 */
@Composable
private fun ActionWheel(item: ClipItem, anchor: Rect?, onAction: (ItemAction) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val light = cs.background.luminance() > 0.5f
    val btnBg = if (light) Color.White else Color(0xFF2C2F36)
    val ringFill = if (light) Color(0xFFE9ECF1) else Color(0xFF3A3E47)
    val ringLine = if (light) Color(0xFFC9CED6) else Color(0xFF575C67)
    val actions = buildList {
        add(Triple(ItemAction.Pin, if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star, if (item.pinned) tr("已收藏", "Starred") else tr("收藏", "Star")))
        if (Features.isOn(ctx, Feature.GROUP)) add(Triple(ItemAction.Group, R.drawable.ic_gen_folder, tr("分组", "Group")))
        if (Features.isOn(ctx, Feature.NOTE)) add(Triple(ItemAction.Note, R.drawable.ic_gen_note, tr("备注", "Note")))
        if (Features.isOn(ctx, Feature.HIDE)) add(Triple(ItemAction.Hide, R.drawable.ic_gen_lock, if (item.hidden) tr("取消隐藏", "Unhide") else tr("隐藏", "Hide")))
        if (!item.hidden) add(Triple(ItemAction.Edit, R.drawable.ic_gen_edit, tr("编辑", "Edit")))
        add(Triple(ItemAction.Share, R.drawable.ic_gen_share, tr("分享", "Share")))
        add(Triple(ItemAction.Delete, R.drawable.ic_gen_trash, tr("删除", "Delete")))
    }.take(7)
    val n = actions.size
    val radius = 98f
    val btn = 66.dp
    val position = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val cx = anchor?.center?.x ?: (windowSize.width / 2f)
                val cy = anchor?.center?.y ?: (windowSize.height / 2f)
                val x = (cx - popupContentSize.width / 2f).roundToInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (cy - popupContentSize.height / 2f).roundToInt().coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(300.dp)) {
                // 一圈柔和的底色光晕：把轮盘和后面的卡片文字隔开，但不把整个屏幕压暗
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        0f to cs.background.copy(alpha = 0.94f), 0.72f to cs.background.copy(alpha = 0.88f), 1f to Color.Transparent,
                        center = center, radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
                val r = radius.dp.toPx()
                val topLeft = Offset(center.x - r, center.y - r)
                val arcSize = Size(r * 2, r * 2)
                // 按钮占掉的角度（再留一点缝），弧线只画在按钮之间
                val gap = (Math.toDegrees(kotlin.math.asin((btn.toPx() / 2f + 2.dp.toPx()) / r).toDouble()) * 2).toFloat()
                val step = 360f / n
                for (i in 0 until n) {
                    val start = -90f + i * step + gap / 2f
                    val sweep = step - gap
                    if (sweep <= 0f) continue
                    drawArc(ringLine, start, sweep, false, topLeft, arcSize, style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(ringFill, start, sweep, false, topLeft, arcSize, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            // 中间：复制
            Column(
                Modifier.size(74.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Accent)
                    .clickable { onAction(ItemAction.Copy); onDismiss() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_gen_copy), null, Modifier.size(22.dp), tint = Color.White)
                Text(tr("复制", "Copy"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            actions.forEachIndexed { i, (action, icon, label) ->
                val angle = (-90f + i * 360f / n) * (PI.toFloat() / 180f)
                val danger = action == ItemAction.Delete
                val tint = when {
                    danger -> Danger
                    action == ItemAction.Pin && item.pinned -> Accent
                    else -> cs.onSurface
                }
                Column(
                    Modifier.offset(x = (cos(angle) * radius).dp, y = (sin(angle) * radius).dp)
                        .size(btn).shadow(6.dp, CircleShape).clip(CircleShape).background(btnBg)
                        .border(1.5.dp, ringLine, CircleShape)
                        .clickable { onAction(action); onDismiss() }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(icon), label, Modifier.size(22.dp), tint = tint)
                    Spacer(Modifier.height(2.dp))
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** 连续收集：开始后接下来复制的内容都接在同一条后面 */
@Composable
private fun CollectBar(c: ClipStore.Collect?) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (c != null) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (c == null) tr("连续收集：开始后，接下来复制的内容会合并成一条", "Collect: after you start, everything you copy is joined into one item")
            else tr("正在收集 · 已收 %d 段", "Collecting · %d parts", c.parts),
            Modifier.weight(1f), fontSize = 13.sp, color = if (c != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c == null) TextButton({ ClipStore.startCollect(); ctx.toast(tr("开始收集，去复制吧", "Collecting — go copy things")) }) { Text(tr("开始", "Start")) }
        else TextButton({ ClipStore.stopCollect(); ctx.toast(tr("收集结束", "Collecting stopped")) }) { Text(tr("结束", "Stop")) }
    }
}

// ---------------- 收藏 ----------------

@Composable
fun FavoritesPage() {
    val ctx = LocalContext.current
    val items by ClipStore.items.collectAsStateWithLifecycle()
    val groups by ClipStore.groups.collectAsStateWithLifecycle()
    val loaded by ClipStore.loaded.collectAsStateWithLifecycle()
    val fv by Features.version.collectAsStateWithLifecycle()
    val groupOn = remember(fv) { Features.isOn(ctx, Feature.GROUP) }
    val pinyin = remember(fv) { Features.isOn(ctx, Feature.PINYIN_SEARCH) }
    var query by rememberSaveable { mutableStateOf("") }
    var chip by rememberSaveable { mutableStateOf(F_ALL) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }

    val favs = remember(items) { items.filter { it.phrase } + items.filter { !it.phrase && it.pinned } }
    val chips = remember(favs, groups, groupOn) {
        val out = arrayListOf(
            Chip(F_ALL, tr("全部", "All"), favs.size),
            Chip("phrases", tr("常用语", "Phrases"), favs.count { it.phrase }),
            Chip("pinned", tr("收藏的记录", "Starred"), favs.count { !it.phrase }),
        )
        // 分组里可以放任何条目（不只是收藏的），所以计数按全部条目算
        if (groupOn) groups.forEach { g -> out += Chip("group:" + g.id, g.name, items.count { it.groupId == g.id }) }
        out
    }
    val cur = if (chips.any { it.key == chip }) chip else F_ALL
    val shown = remember(items, favs, cur, query, pinyin) {
        val base = if (cur.startsWith("group:")) applyFilter(items, cur) else applyFilter(favs, cur)
        val terms = Search.terms(query)
        if (terms.isEmpty()) base else base.filter { it.matches(terms, pinyin) }
    }
    val canReorder = cur == "phrases" && query.isBlank()

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("收藏", "Favorites"), query, { query = it }) {
            HeaderIcon(R.drawable.ic_gen_add, tr("新增常用语", "New phrase"), Accent) { adding = true }
            if (groupOn) HeaderIcon(R.drawable.ic_gen_gear, tr("管理分组", "Manage groups")) { managing = true }
        }
        ChipRow(chips, cur) { chip = it }
        ItemList(
            shown, loaded, selected, { selected = it }, reorder = canReorder,
            empty = when {
                query.isNotBlank() -> tr("没有找到包含“%s”的内容", "Nothing matches “%s”", query)
                cur.startsWith("group:") -> tr("这个分组还是空的。在条目的 ⋮ 菜单里选“移到分组”", "This group is empty. Use “Move to group” in an item's ⋮ menu.")
                else -> tr("邮箱、地址、固定回复……点右上角 ＋ 新增常用语；记录卡片上点 ☆ 收藏。收藏的内容不受条数上限影响。",
                    "Add phrases with ＋, or tap ☆ on a history item. Favourites never expire.")
            },
        )
    }
    if (adding) TextEditDialog(tr("新增常用语", "New phrase"), "") { t ->
        adding = false
        if (t != null) {
            val gid = cur.takeIf { it.startsWith("group:") }?.substring(6)
            if (!ClipStore.addPhrase(t, gid)) ctx.toast(tr("常用语里已有这条", "This phrase already exists"))
        }
    }
    if (managing) GroupManagerDialog { managing = false }
}

// ---------------- 列表 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemList(
    list: List<ClipItem>, loaded: Boolean, selected: Set<String>, onSelected: (Set<String>) -> Unit,
    empty: String, reorder: Boolean = false,
) {
    val ctx = LocalContext.current
    val current by ClipStore.current.collectAsStateWithLifecycle()
    val fv by Features.version.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ClipItem?>(null) }
    // 列表变了（删掉了、被淘汰了）就把已经不在的选中项去掉
    val live = remember(list, selected) { selected.filterTo(HashSet()) { id -> list.any { it.id == id } } }
    if (live.size != selected.size) onSelected(live)
    BackHandler(selected.isNotEmpty()) { onSelected(emptySet()) }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) SelectionBar(list, selected, onSelected)
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(painterResource(R.drawable.ic_empty), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text(if (!loaded) tr("正在读取记录…", "Loading…") else empty, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
            }
            return@Column
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(280.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(list, key = { _, item -> item.id }) { idx, item ->
                ItemCard(
                    item, fv, isCurrent = !item.hidden && item.text == current, picked = item.id in selected, selecting = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) onSelected(if (item.id in selected) selected - item.id else selected + item.id)
                        else Act.copy(ctx, item)
                    },
                    onLongClick = { onSelected(if (item.id in selected) selected - item.id else selected + item.id) },
                    onMenu = { menuFor = item },
                    reorder = if (reorder && item.phrase) Pair(idx > 0, idx < list.lastIndex) else null,
                )
            }
        }
    }
    menuFor?.let { ItemMenu(it) { menuFor = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: ClipItem, fv: Int, isCurrent: Boolean, picked: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit, reorder: Pair<Boolean, Boolean>?,
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val kindOn = remember(fv) { Features.isOn(ctx, Feature.CONTENT_KIND) }
    val sourceOn = remember(fv) { Features.isOn(ctx, Feature.SOURCE_APP) }
    val noteOn = remember(fv) { Features.isOn(ctx, Feature.NOTE) }
    Column(
        modifier.fillMaxWidth().shadow(if (picked) 8.dp else 0.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface)
            .border(if (picked) 1.5.dp else 1.dp, if (picked) Accent else cs.outlineVariant, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        if (item.hidden) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gen_lock), null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(tr("已隐藏的内容 · 点按验证后复制", "Hidden · tap to verify and copy"), color = cs.onSurfaceVariant, fontSize = 14.sp)
            }
        } else {
            val color = if (kindOn && item.kind == ContentKind.COLOR) Kinds.color(item.text) else null
            Row(verticalAlignment = Alignment.Top) {
                if (color != null) {
                    Box(Modifier.padding(top = 2.dp, end = 8.dp).size(18.dp).clip(CircleShape).background(Color(color)).border(1.dp, cs.outlineVariant, CircleShape))
                }
                Text(
                    item.preview, color = if (item.hasLink) Accent else cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        if (noteOn && item.note != null) {
            Text("✎ " + item.note, fontSize = 12.sp, color = Accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picked && selecting) Text("✓ ", color = Accent, fontWeight = FontWeight.Bold)
            if (isCurrent) {
                Text(tr("当前", "Current"), fontSize = 10.5.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
            }
            val meta = buildList {
                if (item.phrase) add(tr("常用语", "Phrase")) else add(timeLabel(item.time))
                add(tr("%d 字", "%d chars", item.chars))
                if (kindOn && item.kind != ContentKind.TEXT) add(Kinds.label(item.kind))
                if (sourceOn && item.sourcePkg != null) add(AppInfo.label(ctx, item.sourcePkg))
                if (item.expireAt != null) add(tr("将自动清除", "auto-removes"))
            }.joinToString(" · ")
            Text(meta, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!selecting) {
                if (reorder != null) {
                    if (reorder.first) SmallIcon(R.drawable.ic_gen_up, tr("上移", "Move up"), cs.onSurfaceVariant) { ClipStore.movePhrase(item.id, -1) }
                    if (reorder.second) SmallIcon(R.drawable.ic_gen_down, tr("下移", "Move down"), cs.onSurfaceVariant) { ClipStore.movePhrase(item.id, 1) }
                }
                if (!item.phrase) SmallIcon(
                    if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star,
                    if (item.pinned) tr("取消收藏", "Unstar") else tr("收藏", "Star"), if (item.pinned) Accent else cs.onSurfaceVariant,
                ) { ClipStore.togglePin(item.id) }
                if (kindOn && !item.hidden && Kinds.actionLabel(item.kind) != null) {
                    SmallIcon(R.drawable.ic_gen_open, Kinds.actionLabel(item.kind)!!, cs.onSurfaceVariant) { Kinds.run(ctx, item.kind, item.text) }
                }
                SmallIcon(R.drawable.ic_gen_more, tr("更多", "More"), cs.onSurfaceVariant, onMenu)
            }
        }
    }
}

/** 多选后的操作条 */
@Composable
private fun SelectionBar(list: List<ClipItem>, selected: Set<String>, onSelected: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var grouping by remember { mutableStateOf(false) }
    val picked = list.filter { it.id in selected }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Accent.copy(alpha = 0.12f)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("已选 %d 条", "%d selected", picked.size), fontSize = 13.sp, color = Accent, modifier = Modifier.padding(horizontal = 6.dp))
        TextButton({ onSelected(list.mapTo(HashSet()) { it.id }) }) { Text(tr("全选", "All")) }
        TextButton({
            Act.texts(ctx, picked) { ts -> ClipReader.write(ctx, TextTools.prepare(prefs, TextTools.join(ts, prefs.mergeMode))); ctx.toast(tr("已合并复制 %d 条", "Copied %d items", ts.size)) }
            onSelected(emptySet())
        }) { Text(tr("复制", "Copy")) }
        if (Features.isOn(ctx, Feature.PASTE_QUEUE)) TextButton({
            Act.texts(ctx, picked) { ts ->
                PasteQueue.start(ctx, ts.map { TextTools.prepare(prefs, it) }, pasteFirst = false)
                ctx.toast(tr("去要粘贴的地方，点屏幕上方的“下一条”", "Go to where you want to paste and tap “Next” at the top"))
                (ctx as? Activity)?.moveTaskToBack(true)
            }
            onSelected(emptySet())
        }) { Text(tr("依次粘贴", "Paste one by one")) }
        if (Features.isOn(ctx, Feature.SPLIT_MERGE) && picked.count { !it.hidden } >= 2) TextButton({
            if (ClipStore.merge(selected, TextTools.separator(prefs.mergeMode)) != null) ctx.toast(tr("已合并成一条", "Merged into one item"))
            onSelected(emptySet())
        }) { Text(tr("合并成一条", "Merge")) }
        if (picked.any { !it.phrase }) TextButton({
            val on = picked.any { !it.phrase && !it.pinned }
            ClipStore.setPinned(selected, on)
            onSelected(emptySet())
        }) { Text(if (picked.any { !it.phrase && !it.pinned }) tr("收藏", "Star") else tr("取消收藏", "Unstar")) }
        if (Features.isOn(ctx, Feature.GROUP)) TextButton({ grouping = true }) { Text(tr("分组", "Group")) }
        TextButton({ val ids = selected.toList(); ClipStore.deleteAll(ids.toSet()); Undo.offer(ctx, ids); onSelected(emptySet()) }) { Text(tr("删除", "Delete"), color = Danger) }
        TextButton({ onSelected(emptySet()) }) { Text(tr("取消", "Cancel")) }
    }
    if (grouping) GroupPickerDialog(null) { gid, chosen ->
        grouping = false
        if (chosen) { ClipStore.setGroup(selected, gid); onSelected(emptySet()) }
    }
}

// ---------------- 动作 ----------------

/** 条目上的动作。隐藏的条目要先验证身份 */
object Act {
    /** 拿到正文后再做事；隐藏的先验证 */
    fun withText(ctx: Context, item: ClipItem, block: (String) -> Unit) {
        if (!item.hidden) { block(item.text); return }
        Unlock.run(ctx) {
            val t = ClipStore.plain(item)
            if (t == null) ctx.toast(tr("这条内容解不开了", "This item can no longer be decrypted")) else block(t)
        }
    }

    /** 几条一起取正文（按传入的顺序）；里面有隐藏的就先验证一次 */
    fun texts(ctx: Context, items: List<ClipItem>, block: (List<String>) -> Unit) {
        val go = { block(items.mapNotNull { ClipStore.plain(it) }) }
        if (items.any { it.hidden }) Unlock.run(ctx, go) else go()
    }

    fun copy(ctx: Context, item: ClipItem) = withText(ctx, item) { t ->
        ClipReader.write(ctx, TextTools.prepare(Prefs(ctx), t))
        ctx.toast(tr("已复制，按 Ctrl+V 或长按输入框粘贴", "Copied. Paste with Ctrl+V or long-press a text field."))
    }

    fun share(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, tr("分享", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class MenuRow(val icon: Int, val label: String, val danger: Boolean = false, val act: () -> Unit)

@Composable
private fun MenuSection(title: String, rows: List<MenuRow>) {
    if (rows.isEmpty()) return
    Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp))
    rows.forEach { MenuRowView(it) }
}

@Composable
private fun MenuRowView(r: MenuRow) {
    val cs = MaterialTheme.colorScheme
    val fg = if (r.danger) Danger else cs.onSurface
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = r.act)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (r.danger) Danger.copy(alpha = 0.1f) else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(painterResource(r.icon), null, Modifier.size(18.dp), tint = if (r.danger) Danger else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(r.label, fontSize = 15.sp, color = fg)
    }
}

private sealed interface MenuState {
    data object Menu : MenuState
    data class Viewer(val text: String) : MenuState
    data class Edit(val text: String) : MenuState
    data object Note : MenuState
    data object Group : MenuState
    data class Transform(val text: String) : MenuState
    data class Library(val text: String) : MenuState
}

/** 条目的 ⋮ 菜单：按功能开关列出能做的事 */
@Composable
private fun ItemMenu(item: ClipItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<MenuState>(MenuState.Menu) }
    fun on(f: Feature) = Features.isOn(ctx, f)
    when (val s = state) {
        MenuState.Menu -> {
            // 分两组：用这条内容 / 整理这一条；删除单独放最后
            val use = buildList<MenuRow> {
                add(MenuRow(R.drawable.ic_gen_pick, tr("查看全文 / 选择一部分", "View / select part")) { Act.withText(ctx, item) { state = MenuState.Viewer(it) } })
                add(MenuRow(R.drawable.ic_gen_copy, tr("复制", "Copy")) { Act.copy(ctx, item); onDismiss() })
                if (on(Feature.TRANSFORM)) add(MenuRow(R.drawable.ic_gen_transform, tr("变换后复制…", "Transform and copy…")) { Act.withText(ctx, item) { state = MenuState.Transform(it) } })
                add(MenuRow(R.drawable.ic_gen_share, tr("分享", "Share")) { Act.withText(ctx, item) { Act.share(ctx, it) }; onDismiss() })
            }
            val organize = buildList<MenuRow> {
                add(MenuRow(R.drawable.ic_gen_edit, tr("编辑", "Edit")) { Act.withText(ctx, item) { state = MenuState.Edit(it) } })
                if (!item.phrase && !item.hidden) add(MenuRow(R.drawable.ic_gen_add, tr("加入常用语", "Add to phrases")) {
                    ctx.toast(if (ClipStore.addPhrase(item.text)) tr("已加入常用语", "Added to phrases") else tr("常用语里已有这条", "Already a phrase"))
                    onDismiss()
                })
                if (on(Feature.LIBRARY) && !item.hidden) add(MenuRow(R.drawable.ic_book, tr("存进文库", "Save to library")) { state = MenuState.Library(item.text) })
                if (on(Feature.NOTE)) add(MenuRow(R.drawable.ic_gen_note, if (item.note == null) tr("加备注", "Add note") else tr("改备注", "Edit note")) { state = MenuState.Note })
                if (on(Feature.GROUP)) add(MenuRow(R.drawable.ic_gen_folder, tr("移到分组…", "Move to group…")) { state = MenuState.Group })
                if (on(Feature.SPLIT_MERGE) && !item.phrase && !item.hidden && item.text.contains('\n')) add(MenuRow(R.drawable.ic_gen_list, tr("按行拆分成多条", "Split into lines")) {
                    val n = ClipStore.split(item.id)
                    ctx.toast(if (n > 0) tr("拆成了 %d 条", "Split into %d items", n) else tr("不到两行，不用拆", "Fewer than two lines"))
                    onDismiss()
                })
                if (on(Feature.HIDE)) {
                    if (item.hidden) add(MenuRow(R.drawable.ic_gen_lock, tr("取消隐藏", "Unhide")) { Unlock.run(ctx) { if (!ClipStore.unhide(item.id)) ctx.toast(tr("这条内容解不开了", "Can't decrypt this item")) }; onDismiss() })
                    else add(MenuRow(R.drawable.ic_gen_lock, tr("隐藏", "Hide")) {
                        ctx.toast(if (ClipStore.hide(item.id)) tr("已隐藏", "Hidden") else tr("这条不能隐藏（太长，或这台设备不支持加密）", "Can't hide this (too long, or encryption unavailable)"))
                        onDismiss()
                    })
                }
            }
            val delete = MenuRow(R.drawable.ic_gen_trash, tr("删除", "Delete"), danger = true) { ClipStore.delete(item.id); Undo.offer(ctx, listOf(item.id)); onDismiss() }
            val cs = MaterialTheme.colorScheme
            Dialog(onDismissRequest = onDismiss) {
                Column(
                    Modifier.widthIn(min = 300.dp, max = 400.dp).clip(RoundedCornerShape(24.dp)).background(cs.surface)
                        .verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                ) {
                    Text(
                        if (item.hidden) tr("已隐藏的内容", "Hidden item") else item.preview.take(80),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    MenuSection(tr("使用", "Use"), use)
                    MenuSection(tr("整理", "Organize"), organize)
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                    MenuRowView(delete)
                }
            }
        }
        is MenuState.Viewer -> TextViewerDialog(s.text, onDismiss)
        is MenuState.Edit -> TextEditDialog(tr("编辑", "Edit"), s.text) { t -> if (t != null) ClipStore.updateText(item.id, t); onDismiss() }
        MenuState.Note -> TextEditDialog(tr("备注", "Note"), item.note ?: "", singleLine = true, allowEmpty = true) { t -> if (t != null) ClipStore.setNote(item.id, t); onDismiss() }
        MenuState.Group -> GroupPickerDialog(item.groupId) { gid, chosen -> if (chosen) ClipStore.setGroup(listOf(item.id), gid); onDismiss() }
        is MenuState.Transform -> TransformDialog(s.text, onDismiss)
        is MenuState.Library -> SaveToLibraryDialog(s.text) { onDismiss() }
    }
}

// ---------------- 对话框 ----------------

/** 全文：可以拖动选择其中一部分，用系统菜单复制 */
@Composable
private fun TextViewerDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shown = remember(text) { if (text.length <= 50_000) text else text.take(50_000) + tr("\n…（只显示前 5 万字）", "\n… (first 50,000 characters)") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp).heightIn(max = 560.dp)) {
            Text(tr("长按选择要用的部分，再点“复制”", "Long-press to select the part you need, then Copy"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(shown, fontSize = 15.sp, lineHeight = 23.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ ClipReader.write(ctx, text); ctx.toast(tr("已复制全文", "Copied all")); onDismiss() }) { Text(tr("复制全文", "Copy all")) }
                TextButton(onDismiss) { Text(tr("关闭", "Close")) }
            }
        }
    }
}

/** 编辑一段文字。onDone(null) 表示取消 */
@Composable
fun TextEditDialog(title: String, initial: String, singleLine: Boolean = false, allowEmpty: Boolean = false, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 10,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onDone(text) }, enabled = allowEmpty || text.isNotBlank()) { Text(tr("保存", "Save")) } },
        dismissButton = { TextButton({ onDone(null) }) { Text(tr("取消", "Cancel")) } },
    )
}

/** 选一个分组。onDone(分组 ID 或 null=移出分组, 是否做了选择) */
@Composable
private fun GroupPickerDialog(current: String?, onDone: (String?, Boolean) -> Unit) {
    val groups by ClipStore.groups.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDone(null, false) }) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp).widthIn(min = 260.dp)) {
            Text(tr("移到分组", "Move to group"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(tr("不分组", "No group"), fontSize = 15.sp, color = if (current == null) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onDone(null, true) }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
                items(groups, key = { it.id }) { g ->
                    Text(g.name, fontSize = 15.sp, color = if (current == g.id) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onDone(g.id, true) }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            TextButton({ naming = true }, Modifier.padding(horizontal = 8.dp)) { Text(tr("＋ 新建分组", "＋ New group")) }
        }
    }
    if (naming) TextEditDialog(tr("新建分组", "New group"), "", singleLine = true) { name ->
        naming = false
        if (name != null) ClipStore.addGroup(name)?.let { onDone(it, true) }
    }
}

/** 管理分组：改名、排序、删除、新建 */
@Composable
private fun GroupManagerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val groups by ClipStore.groups.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp).widthIn(min = 280.dp)) {
            Text(tr("管理分组", "Groups"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            if (groups.isEmpty()) Text(tr("还没有分组", "No groups yet"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp, 12.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(groups, key = { it.id }) { g ->
                    val i = groups.indexOf(g)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, Modifier.weight(1f).padding(start = 4.dp), fontSize = 15.sp)
                        if (i > 0) SmallIcon(R.drawable.ic_gen_up, tr("上移", "Up"), MaterialTheme.colorScheme.onSurfaceVariant) { ClipStore.moveGroup(g.id, -1) }
                        if (i < groups.lastIndex) SmallIcon(R.drawable.ic_gen_down, tr("下移", "Down"), MaterialTheme.colorScheme.onSurfaceVariant) { ClipStore.moveGroup(g.id, 1) }
                        SmallIcon(R.drawable.ic_gen_edit, tr("改名", "Rename"), MaterialTheme.colorScheme.onSurfaceVariant) { renaming = g.id }
                        SmallIcon(R.drawable.ic_gen_trash, tr("删除", "Delete"), MaterialTheme.colorScheme.onSurfaceVariant) { deleting = g.id }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ adding = true }) { Text(tr("＋ 新建分组", "＋ New group")) }
                TextButton(onDismiss) { Text(tr("完成", "Done")) }
            }
        }
    }
    renaming?.let { id ->
        TextEditDialog(tr("改名", "Rename"), groups.firstOrNull { it.id == id }?.name ?: "", singleLine = true) { n ->
            renaming = null
            if (n != null && !ClipStore.renameGroup(id, n)) ctx.toast(tr("名字不能为空，也不能和别的分组重名", "Name can't be empty or duplicate"))
        }
    }
    if (adding) TextEditDialog(tr("新建分组", "New group"), "", singleLine = true) { n ->
        adding = false
        if (n != null && ClipStore.addGroup(n) == null) ctx.toast(tr("名字不能为空，也不能和别的分组重名", "Name can't be empty or duplicate"))
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("删除分组？", "Delete group?")) },
            text = { Text(tr("组里的内容不会删除，只是回到“不分组”。", "Items in it are kept; they just leave the group.")) },
            confirmButton = { TextButton({ ClipStore.deleteGroup(id); deleting = null }) { Text(tr("删除", "Delete"), color = Danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}
