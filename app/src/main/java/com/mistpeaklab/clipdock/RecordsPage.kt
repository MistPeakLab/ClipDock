package com.mistpeaklab.clipdock

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.util.ContentKind
import com.mistpeaklab.clipdock.util.Search
import com.mistpeaklab.clipdock.util.TextTransforms
import com.mistpeaklab.clipdock.util.Transform

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
fun RecordsPage(tick: Int) {
    val ctx = LocalContext.current
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
    var selected by remember { mutableStateOf(emptySet<String>()) }

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

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("记录", "History"), query, { query = it }) {
            HeaderIcon(R.drawable.ic_download, tr("读取当前剪贴板", "Read clipboard")) {
                ctx.toast(if (ClipReader.capture(ctx, "MANUAL")) tr("已读取当前剪贴板", "Clipboard saved") else tr("剪贴板里没有文字", "No text in the clipboard"))
            }
            HeaderIcon(R.drawable.ic_open, tr("打开悬浮面板", "Open floating panel")) {
                if (!PanelHost.show(ctx)) ctx.toast(tr("需要先允许“显示在其他应用上层”", "Allow “Display over other apps” first"))
                else (ctx as? Activity)?.moveTaskToBack(true)
            }
        }
        ChipRow(chips, if (chips.any { it.key == filter }) filter else F_ALL) { filter = it }
        if (collectOn) CollectBar(collect)
        ItemList(
            shown, loaded, selected, { selected = it },
            empty = when {
                query.isNotBlank() -> tr("没有找到包含“%s”的内容", "Nothing matches “%s”", query)
                else -> tr("还没有记录，复制一些文字试试", "Nothing yet. Copy some text and it shows up here.")
            },
        )
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
            HeaderIcon(R.drawable.ic_add, tr("新增常用语", "New phrase"), Accent) { adding = true }
            if (groupOn) HeaderIcon(R.drawable.ic_tune, tr("管理分组", "Manage groups")) { managing = true }
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
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val kindOn = remember(fv) { Features.isOn(ctx, Feature.CONTENT_KIND) }
    val sourceOn = remember(fv) { Features.isOn(ctx, Feature.SOURCE_APP) }
    val noteOn = remember(fv) { Features.isOn(ctx, Feature.NOTE) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (picked) Accent.copy(alpha = 0.12f) else cs.surface)
            .border(if (isCurrent || picked) 1.5.dp else 1.dp, if (isCurrent || picked) Accent else cs.outlineVariant, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        if (item.hidden) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
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
            if (picked) Text("✓ ", color = Accent, fontWeight = FontWeight.Bold)
            if (isCurrent) {
                Text(tr("当前", "Current"), fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
            }
            val meta = buildList {
                if (item.phrase) add(tr("常用语", "Phrase")) else add(timeLabel(item.time))
                add(tr("%d 字", "%d chars", item.chars))
                if (kindOn && item.kind != ContentKind.TEXT) add(Kinds.label(item.kind))
                if (sourceOn && item.sourcePkg != null) add(AppInfo.label(ctx, item.sourcePkg))
                if (item.expireAt != null) add(tr("将自动清除", "auto-removes"))
            }.joinToString(" · ")
            Text(meta, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!selecting) {
                if (reorder != null) {
                    if (reorder.first) SmallIcon(R.drawable.ic_up, tr("上移", "Move up"), cs.onSurfaceVariant) { ClipStore.movePhrase(item.id, -1) }
                    if (reorder.second) SmallIcon(R.drawable.ic_down, tr("下移", "Move down"), cs.onSurfaceVariant) { ClipStore.movePhrase(item.id, 1) }
                }
                if (!item.phrase) SmallIcon(
                    if (item.pinned) R.drawable.ic_star_filled else R.drawable.ic_star,
                    if (item.pinned) tr("取消收藏", "Unstar") else tr("收藏", "Star"), if (item.pinned) Accent else cs.onSurfaceVariant,
                ) { ClipStore.togglePin(item.id) }
                if (kindOn && !item.hidden && Kinds.actionLabel(item.kind) != null) {
                    SmallIcon(R.drawable.ic_open, Kinds.actionLabel(item.kind)!!, cs.onSurfaceVariant) { Kinds.run(ctx, item.kind, item.text) }
                }
                SmallIcon(R.drawable.ic_more, tr("更多", "More"), cs.onSurfaceVariant, onMenu)
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

private sealed interface MenuState {
    data object Menu : MenuState
    data class Viewer(val text: String) : MenuState
    data class Edit(val text: String) : MenuState
    data object Note : MenuState
    data object Group : MenuState
    data class Transform(val text: String) : MenuState
}

/** 条目的 ⋮ 菜单：按功能开关列出能做的事 */
@Composable
private fun ItemMenu(item: ClipItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<MenuState>(MenuState.Menu) }
    fun on(f: Feature) = Features.isOn(ctx, f)
    when (val s = state) {
        MenuState.Menu -> {
            val actions = buildList<Pair<String, () -> Unit>> {
                add(tr("查看全文 / 选择一部分", "View / select part") to { Act.withText(ctx, item) { state = MenuState.Viewer(it) } })
                add(tr("复制", "Copy") to { Act.copy(ctx, item); onDismiss() })
                if (on(Feature.TRANSFORM)) add(tr("变换后复制…", "Transform and copy…") to { Act.withText(ctx, item) { state = MenuState.Transform(it) } })
                add(tr("分享", "Share") to { Act.withText(ctx, item) { Act.share(ctx, it) }; onDismiss() })
                add(tr("编辑", "Edit") to { Act.withText(ctx, item) { state = MenuState.Edit(it) } })
                if (!item.phrase && !item.hidden) add(tr("加入常用语", "Add to phrases") to {
                    ctx.toast(if (ClipStore.addPhrase(item.text)) tr("已加入常用语", "Added to phrases") else tr("常用语里已有这条", "Already a phrase"))
                    onDismiss()
                })
                if (on(Feature.NOTE)) add((if (item.note == null) tr("加备注", "Add note") else tr("改备注", "Edit note")) to { state = MenuState.Note })
                if (on(Feature.GROUP)) add(tr("移到分组…", "Move to group…") to { state = MenuState.Group })
                if (on(Feature.SPLIT_MERGE) && !item.phrase && !item.hidden && item.text.contains('\n')) add(tr("按行拆分成多条", "Split into lines") to {
                    val n = ClipStore.split(item.id)
                    ctx.toast(if (n > 0) tr("拆成了 %d 条", "Split into %d items", n) else tr("不到两行，不用拆", "Fewer than two lines"))
                    onDismiss()
                })
                if (on(Feature.HIDE)) {
                    if (item.hidden) add(tr("取消隐藏", "Unhide") to { Unlock.run(ctx) { if (!ClipStore.unhide(item.id)) ctx.toast(tr("这条内容解不开了", "Can't decrypt this item")) }; onDismiss() })
                    else add(tr("隐藏", "Hide") to {
                        ctx.toast(if (ClipStore.hide(item.id)) tr("已隐藏", "Hidden") else tr("这条不能隐藏（太长，或这台设备不支持加密）", "Can't hide this (too long, or encryption unavailable)"))
                        onDismiss()
                    })
                }
                add(tr("删除", "Delete") to { ClipStore.delete(item.id); Undo.offer(ctx, listOf(item.id)); onDismiss() })
            }
            Dialog(onDismissRequest = onDismiss) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp).widthIn(min = 260.dp)) {
                    Text(
                        if (item.hidden) tr("已隐藏的内容", "Hidden item") else item.preview.take(60),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    actions.forEach { (label, act) ->
                        Text(
                            label, fontSize = 15.sp, color = if (label == tr("删除", "Delete")) Danger else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = act).padding(horizontal = 20.dp, vertical = 13.dp),
                        )
                    }
                }
            }
        }
        is MenuState.Viewer -> TextViewerDialog(s.text, onDismiss)
        is MenuState.Edit -> TextEditDialog(tr("编辑", "Edit"), s.text) { t -> if (t != null) ClipStore.updateText(item.id, t); onDismiss() }
        MenuState.Note -> TextEditDialog(tr("备注", "Note"), item.note ?: "", singleLine = true, allowEmpty = true) { t -> if (t != null) ClipStore.setNote(item.id, t); onDismiss() }
        MenuState.Group -> GroupPickerDialog(item.groupId) { gid, chosen -> if (chosen) ClipStore.setGroup(listOf(item.id), gid); onDismiss() }
        is MenuState.Transform -> TransformDialog(s.text, onDismiss)
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
                        if (i > 0) SmallIcon(R.drawable.ic_up, tr("上移", "Up"), MaterialTheme.colorScheme.onSurfaceVariant) { ClipStore.moveGroup(g.id, -1) }
                        if (i < groups.lastIndex) SmallIcon(R.drawable.ic_down, tr("下移", "Down"), MaterialTheme.colorScheme.onSurfaceVariant) { ClipStore.moveGroup(g.id, 1) }
                        SmallIcon(R.drawable.ic_edit, tr("改名", "Rename"), MaterialTheme.colorScheme.onSurfaceVariant) { renaming = g.id }
                        SmallIcon(R.drawable.ic_delete, tr("删除", "Delete"), MaterialTheme.colorScheme.onSurfaceVariant) { deleting = g.id }
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

/** 粘贴前变换：选一种，预览结果，复制 */
@Composable
private fun TransformDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf<Transform?>(null) }
    val result = remember(chosen) { chosen?.let { runCatching { TextTransforms.apply(it, text) }.getOrDefault(text) } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp).heightIn(max = 600.dp)) {
            Text(tr("变换后复制", "Transform and copy"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            if (result == null) {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(Transform.entries.toList()) { t ->
                        Text(t.label, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { chosen = t }.padding(horizontal = 8.dp, vertical = 11.dp))
                    }
                }
            } else {
                Text(chosen!!.label, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
                Box(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState()).padding(12.dp)) {
                    SelectionContainer { Text(result.ifEmpty { tr("（结果是空的）", "(empty result)") }.take(20_000), fontSize = 14.sp, lineHeight = 21.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton({ chosen = null }) { Text(tr("换一种", "Back")) }
                    TextButton({ ClipStore.add(result, Prefs(ctx).maxItems, fromClipboard = false); ctx.toast(tr("已存为新的一条", "Saved as a new item")); onDismiss() },
                        enabled = result.isNotBlank()) { Text(tr("存为一条", "Save")) }
                    Button({ ClipReader.write(ctx, result); ctx.toast(tr("已复制", "Copied")); onDismiss() }, enabled = result.isNotEmpty()) { Text(tr("复制", "Copy")) }
                }
            }
        }
    }
}
