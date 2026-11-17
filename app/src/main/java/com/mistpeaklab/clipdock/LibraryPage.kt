package com.mistpeaklab.clipdock

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.data.NoteRow
import com.mistpeaklab.clipdock.util.Chunks
import com.mistpeaklab.clipdock.util.Search
import kotlinx.coroutines.delay

/** 文库页的筛选：全部 / 未归类 / 某个文集 */
private const val SHELF_ALL = "all"
private const val SHELF_NONE = "none"

/** 阅读页的一段最多这么多字：一整段几万字的文字交给一个 Text 去排版会卡 */
private const val PARA_MAX = 1500

/** 正文超过这么多字就不在手机上编辑正文了（输入框装不下这么长的文字，会很卡） */
private const val EDIT_MAX = 200_000

// ---------------- 文库主页 ----------------

/** 文库页内部的“页面”：列表 / 阅读 / 编辑（新建时 id 为 null） */
private sealed interface LibView {
    data object List : LibView
    data class Read(val id: String, val jump: Int = -1, val query: String = "") : LibView
    data class Edit(val id: String?) : LibView
}

@Composable
fun LibraryPage() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { LibraryStore.init(ctx) }
    var view by remember { mutableStateOf<LibView>(LibView.List) }
    BackHandler(view != LibView.List) {
        view = when (val v = view) {
            is LibView.Edit -> if (v.id != null) LibView.Read(v.id) else LibView.List
            else -> LibView.List
        }
    }
    when (val v = view) {
        LibView.List -> LibraryList(
            onOpen = { id, jump, q -> view = LibView.Read(id, jump, q) },
            onNew = { view = LibView.Edit(null) },
        )
        is LibView.Read -> NoteReader(v.id, v.jump, v.query, onBack = { view = LibView.List }, onEdit = { view = LibView.Edit(v.id) })
        is LibView.Edit -> NoteEditor(v.id) { saved ->
            view = when {
                saved != null -> LibView.Read(saved)
                v.id != null -> LibView.Read(v.id)
                else -> LibView.List
            }
        }
    }
}

@Composable
private fun LibraryList(onOpen: (String, Int, String) -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val notes by LibraryStore.notes.collectAsStateWithLifecycle()
    val shelves by LibraryStore.shelves.collectAsStateWithLifecycle()
    val loaded by LibraryStore.loaded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var shelf by rememberSaveable { mutableStateOf(SHELF_ALL) }
    var menuFor by remember { mutableStateOf<NoteRow?>(null) }
    var managing by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<LibraryStore.Hit>?>(null) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            ctx.toast(tr("正在导入 %d 个文件…", "Importing %d files…", uris.size))
            LibraryStore.importFiles(ctx, uris, shelf.takeIf { it != SHELF_ALL && it != SHELF_NONE }) { n, bad ->
                ctx.toast(
                    if (bad.isEmpty()) tr("导入了 %d 篇", "Imported %d", n)
                    else tr("导入了 %d 篇；没导入：%s", "Imported %d; skipped: %s", n, bad.joinToString("、").take(120)),
                )
            }
        }
    }

    // 全文搜索：停止输入 300ms 后在后台逐篇查
    LaunchedEffect(query, notes) {
        if (query.isBlank()) { hits = null; return@LaunchedEffect }
        delay(300)
        LibraryStore.search(query) { hits = it }
    }

    val cur = if (shelf == SHELF_ALL || shelf == SHELF_NONE || shelves.any { it.id == shelf }) shelf else SHELF_ALL
    val shown = remember(notes, cur) {
        when (cur) {
            SHELF_ALL -> notes
            SHELF_NONE -> notes.filter { it.shelfId == null || shelves.none { s -> s.id == it.shelfId } }
            else -> notes.filter { it.shelfId == cur }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("文库", "Library"), query, { query = it }) {
            HeaderIcon(R.drawable.ic_gen_add, tr("新建一篇", "New"), Accent) { onNew() }
            HeaderIcon(R.drawable.ic_gen_download, tr("导入 txt / md", "Import txt / md")) {
                runCatching { import.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")) }
            }
            HeaderIcon(R.drawable.ic_gen_folder, tr("管理文集", "Collections")) { managing = true }
            HeaderIcon(R.drawable.ic_gen_gear, tr("文库设置", "Library settings")) { settings = true }
        }
        if (query.isBlank()) {
            val chips = buildList {
                add(Triple(SHELF_ALL, tr("全部", "All"), notes.size))
                val none = notes.count { n -> n.shelfId == null || shelves.none { it.id == n.shelfId } }
                if (shelves.isNotEmpty() && none > 0) add(Triple(SHELF_NONE, tr("未归类", "Unfiled"), none))
                shelves.forEach { s -> add(Triple(s.id, s.name, notes.count { it.shelfId == s.id })) }
            }
            if (chips.size > 1) LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it.first }) { (k, label, n) ->
                    FilterChip(selected = k == cur, onClick = { shelf = k }, label = { Text("$label $n", fontSize = 13.sp) })
                }
            }
        }
        when {
            query.isNotBlank() -> SearchResults(hits, notes, query) { h -> onOpen(h.noteId, h.offset, query) }
            shown.isEmpty() -> LibraryEmpty(loaded, cur != SHELF_ALL)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { n ->
                    NoteCard(n, shelves.firstOrNull { it.id == n.shelfId }?.name.takeIf { cur == SHELF_ALL }, { onOpen(n.id, -1, "") }) { menuFor = n }
                }
            }
        }
    }
    menuFor?.let { n -> NoteMenu(n, onOpen = { onOpen(n.id, -1, "") }) { menuFor = null } }
    if (managing) ShelfManagerDialog { managing = false }
    if (settings) AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text(tr("文库设置", "Library settings")) },
        text = {
            Column {
                var cite by remember { mutableStateOf(prefs.libCite) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("粘贴时附带出处", "Add the source when pasting"), fontSize = 15.sp)
                        Text(tr("从面板里贴文库的内容时，在后面加一行“——《书名》作者”", "When pasting library text from the panel, append “— Title, Author”"),
                            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BrandSwitch(cite, { cite = it; prefs.libCite = it })
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    tr("存进文库的方法：在任意应用里选中文字 › 存进文库；分享文字给随贴时选“存进文库”；记录的 ⋮ 菜单 › 存进文库；或者导入 txt / md 文件。",
                        "Ways to add: select text in any app › Save to library; share text to ClipDock and pick Library; an item's ⋮ menu › Save to library; or import txt / md files."),
                    fontSize = 12.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ settings = false }) { Text(tr("好", "OK")) } },
    )
}

@Composable
private fun LibraryEmpty(loaded: Boolean, filtered: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ic_book), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                !loaded -> tr("正在读取…", "Loading…")
                filtered -> tr("这个文集还是空的", "This collection is empty")
                else -> tr(
                    "文库用来放读书摘抄、素材这类长文字，用的时候在面板里打开一篇，挑其中一段贴出去。\n\n在任意应用里选中文字 › 存进文库，或者点右上角 ＋ 新建、导入 txt / md 文件。",
                    "Keep long texts here — excerpts, reference material — and paste just the part you need from the panel.\n\nSelect text in any app › Save to library, or use ＋ / import txt / md above.",
                )
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/** 出处一行：《书名》· 作者 */
internal fun sourceLine(n: NoteRow): String? = sourceLine(n.sourceTitle, n.sourceAuthor)

internal fun sourceLine(title: String?, author: String?): String? = listOfNotNull(
    title?.let { if (it.startsWith("《")) it else "《$it》" }, author,
).joinToString(" · ").takeIf { it.isNotEmpty() }

/** 粘贴时附带的出处：——《书名》作者 */
internal fun citation(n: NoteRow): String? {
    val t = n.sourceTitle?.let { if (it.startsWith("《")) it else "《$it》" }
    val s = listOfNotNull(t, n.sourceAuthor).joinToString(" ")
    return if (s.isEmpty()) null else "——$s"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(n: NoteRow, shelfName: String?, onClick: () -> Unit, onMenu: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 1.dp).size(30.dp).clip(RoundedCornerShape(9.dp)).background(Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_book), null, Modifier.size(17.dp), tint = Accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(n.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                sourceLine(n)?.let { Text(it, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Text(n.preview, fontSize = 13.5.sp, lineHeight = 20.sp, color = cs.onSurface.copy(alpha = 0.82f), maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, end = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            val meta = listOfNotNull(shelfName, charsLabel(n.chars), timeLabel(n.updatedMs)).joinToString(" · ")
            Text(meta, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SmallIcon(R.drawable.ic_gen_more, tr("更多", "More"), cs.onSurfaceVariant, onMenu)
        }
    }
}

internal fun charsLabel(n: Int): String = when {
    n >= 10_000 -> tr("%.1f 万字", "%.1fk chars", if (isZh()) n / 10_000.0 else n / 1000.0)
    else -> tr("%d 字", "%d chars", n)
}

private fun isZh() = tr("zh", "en") == "zh"

@Composable
private fun SearchResults(hits: List<LibraryStore.Hit>?, notes: List<NoteRow>, query: String, onOpen: (LibraryStore.Hit) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (hits == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("正在搜索…", "Searching…"), color = cs.onSurfaceVariant) }
        return
    }
    if (hits.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(tr("文库里没有找到“%s”", "Nothing in the library matches “%s”", query), color = cs.onSurfaceVariant)
        }
        return
    }
    val byNote = remember(hits) { hits.groupBy { it.noteId }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item { Text(tr("%d 篇里找到 %d 处", "%2\$d matches in %1\$d texts", byNote.size, hits.count { it.offset >= 0 }), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
        byNote.forEach { (id, list) ->
            val n = notes.firstOrNull { it.id == id } ?: return@forEach
            item(key = id) {
                Column(
                    Modifier.padding(bottom = 10.dp).widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(vertical = 8.dp),
                ) {
                    Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    list.forEach { h ->
                        Text(
                            buildAnnotatedString {
                                append(h.snippet)
                                h.mark?.let { m ->
                                    if (m.first >= 0 && m.last < h.snippet.length) addStyle(SpanStyle(background = Color(0x66FFD54F), fontWeight = FontWeight.SemiBold), m.first, m.last + 1)
                                }
                            },
                            fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(h) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 篇章的菜单：打开、移到文集、删除 */
@Composable
private fun NoteMenu(n: NoteRow, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    when {
        moving -> ShelfPickerDialog(n.shelfId) { sid, chosen -> if (chosen) LibraryStore.move(listOf(n.id), sid); onDismiss() }
        deleting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("删除这篇？", "Delete this text?")) },
            text = { Text(tr("“%s”会移进回收站，%d 天内可以恢复。", "“%s” goes to the trash and can be restored for %d days.", n.title, com.mistpeaklab.clipdock.data.ItemRepo.TRASH_DAYS)) },
            confirmButton = { TextButton({ LibraryStore.delete(listOf(n.id)); onDismiss() }) { Text(tr("删除", "Delete"), color = Danger) } },
            dismissButton = { TextButton(onDismiss) { Text(tr("取消", "Cancel")) } },
        )
        else -> Dialog(onDismissRequest = onDismiss) {
            Column(Modifier.widthIn(min = 280.dp, max = 380.dp).clip(RoundedCornerShape(24.dp)).background(cs.surface).padding(vertical = 10.dp)) {
                Text(n.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LibMenuRow(R.drawable.ic_book, tr("打开", "Open")) { onDismiss(); onOpen() }
                LibMenuRow(R.drawable.ic_gen_copy, tr("复制全文", "Copy all")) {
                    LibraryStore.textAsync(n.id) { t -> if (t != null) { ClipReader.write(ctx, t); ctx.toast(tr("已复制全文", "Copied")) } }
                    onDismiss()
                }
                LibMenuRow(R.drawable.ic_gen_folder, tr("移到文集…", "Move to collection…")) { moving = true }
                LibMenuRow(R.drawable.ic_gen_trash, tr("删除", "Delete"), danger = true) { deleting = true }
            }
        }
    }
}

@Composable
private fun LibMenuRow(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (danger) Danger.copy(alpha = 0.1f) else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (danger) Danger else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = if (danger) Danger else cs.onSurface)
    }
}

/** 选一个文集。onDone(文集 ID 或 null=未归类, 是否做了选择) */
@Composable
internal fun ShelfPickerDialog(current: String?, onDone: (String?, Boolean) -> Unit) {
    val shelves by LibraryStore.shelves.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDone(null, false) }) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp).widthIn(min = 260.dp)) {
            Text(tr("移到文集", "Move to collection"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(tr("未归类", "Unfiled"), fontSize = 15.sp, color = if (current == null) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onDone(null, true) }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
                items(shelves, key = { it.id }) { g ->
                    Text(g.name, fontSize = 15.sp, color = if (current == g.id) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onDone(g.id, true) }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            TextButton({ naming = true }, Modifier.padding(horizontal = 8.dp)) { Text(tr("＋ 新建文集", "＋ New collection")) }
        }
    }
    if (naming) TextEditDialog(tr("新建文集", "New collection"), "", singleLine = true) { name ->
        naming = false
        if (name != null) LibraryStore.addShelf(name)?.let { onDone(it, true) }
    }
}

/** 管理文集：改名、删除、新建 */
@Composable
private fun ShelfManagerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shelves by LibraryStore.shelves.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp).widthIn(min = 280.dp)) {
            Text(tr("文集", "Collections"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            if (shelves.isEmpty()) Text(tr("还没有文集。可以按书、按主题建几个，把篇章归进去。", "No collections yet — make some by book or topic."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(4.dp, 12.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(shelves, key = { it.id }) { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, Modifier.weight(1f).padding(start = 4.dp), fontSize = 15.sp)
                        SmallIcon(R.drawable.ic_gen_edit, tr("改名", "Rename"), MaterialTheme.colorScheme.onSurfaceVariant) { renaming = g.id }
                        SmallIcon(R.drawable.ic_gen_trash, tr("删除", "Delete"), MaterialTheme.colorScheme.onSurfaceVariant) { deleting = g.id }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ adding = true }) { Text(tr("＋ 新建文集", "＋ New collection")) }
                TextButton(onDismiss) { Text(tr("完成", "Done")) }
            }
        }
    }
    renaming?.let { id ->
        TextEditDialog(tr("改名", "Rename"), shelves.firstOrNull { it.id == id }?.name ?: "", singleLine = true) { n ->
            renaming = null
            if (n != null && !LibraryStore.renameShelf(id, n)) ctx.toast(tr("名字不能为空，也不能和别的文集重名", "Name can't be empty or duplicate"))
        }
    }
    if (adding) TextEditDialog(tr("新建文集", "New collection"), "", singleLine = true) { n ->
        adding = false
        if (n != null && LibraryStore.addShelf(n) == null) ctx.toast(tr("名字不能为空，也不能和别的文集重名", "Name can't be empty or duplicate"))
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("删除文集？", "Delete collection?")) },
            text = { Text(tr("里面的篇章不会删除，只是回到“未归类”。", "Texts in it are kept; they become unfiled.")) },
            confirmButton = { TextButton({ LibraryStore.deleteShelf(id); deleting = null }) { Text(tr("删除", "Delete"), color = Danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

// ---------------- 阅读 ----------------

/** 把正文切成段（按换行；过长的一段再切开），返回每段在原文里的位置 */
internal fun paragraphs(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var s = 0
    while (s < text.length) {
        var e = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        if (e > s) {
            // 一段太长：再按 PARA_MAX 切
            for (r in Chunks.split(text.substring(s, e), PARA_MAX)) out += (s + r.first)..(s + r.last)
        }
        s = e + 1
    }
    return out
}

@Composable
private fun NoteReader(id: String, jump: Int, query: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val notes by LibraryStore.notes.collectAsStateWithLifecycle()
    val n = notes.firstOrNull { it.id == id }
    var text by remember(id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(id, n?.updatedMs) { LibraryStore.textAsync(id) { text = it ?: "" } }
    if (n == null) { LaunchedEffect(Unit) { onBack() }; return }

    val paras = remember(text) { text?.let(::paragraphs) ?: emptyList() }
    val list = rememberLazyListState()
    val terms = remember(query) { Search.terms(query) }
    LaunchedEffect(paras, jump) {
        if (jump >= 0 && paras.isNotEmpty()) list.scrollToItem(paras.indexOfFirst { jump <= it.last }.coerceAtLeast(0) + 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", fontSize = 18.sp) }
            Spacer(Modifier.weight(1f))
            HeaderIcon(R.drawable.ic_gen_edit, tr("编辑", "Edit")) { onEdit() }
            HeaderIcon(R.drawable.ic_gen_copy, tr("复制全文", "Copy all")) { text?.let { ClipReader.write(ctx, it); ctx.toast(tr("已复制全文", "Copied")) } }
            HeaderIcon(R.drawable.ic_gen_more, tr("更多", "More")) { menu = true }
        }
        val toolbar = rememberLibToolbar()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SelectionContainer {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 48.dp),
                    ) {
                        item(key = "head") {
                            Column(Modifier.padding(bottom = 14.dp)) {
                                Text(n.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                                val meta = listOfNotNull(sourceLine(n), charsLabel(n.chars)).joinToString(" · ")
                                Text(meta, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                n.sourceUrl?.let { Text(it, fontSize = 12.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        if (text == null) item { Text(tr("正在读取…", "Loading…"), color = cs.onSurfaceVariant) }
                        val t = text ?: ""
                        itemsIndexed(paras, key = { i, _ -> i }) { _, r ->
                            val s = t.substring(r.first, r.last + 1)
                            val marks = if (terms.isEmpty()) emptyList() else Search.highlight(s, terms, false)
                            Text(
                                if (marks.isEmpty()) AnnotatedString(s) else buildAnnotatedString {
                                    append(s)
                                    marks.forEach { m -> addStyle(SpanStyle(background = Color(0x66FFD54F)), m.first, m.last + 1) }
                                },
                                fontSize = 17.sp, lineHeight = 29.sp,
                                // 原文里换行处的段落之间留一点空，硬切开的长段之间不留
                                modifier = Modifier.padding(bottom = if (r.last + 1 >= t.length || t[r.last + 1] == '\n') 12.dp else 0.dp),
                            )
                        }
                    }
                }
            }
            toolbar.Render()
        }
    }
    if (menu) NoteMenu(n, onOpen = {}) { menu = false }
}

/**
 * 阅读页选中文字后弹出的小工具条：复制 / 存为记录 / 加入常用语。
 * 系统的选择菜单只有“复制”，这里换成自己的：先让 Compose 把选中的文字复制到剪贴板，再从剪贴板取回来用。
 */
private class LibToolbar(private val ctx: Context) : TextToolbar {
    var rect by mutableStateOf<Rect?>(null)
    private var copy: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null

    override val status: TextToolbarStatus get() = if (rect != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    override fun hide() { rect = null }
    override fun showMenu(
        rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?,
    ) {
        copy = onCopyRequested; selectAll = onSelectAllRequested
        this.rect = rect
    }

    /** 复制选中的文字，并返回它 */
    private fun grab(): String? {
        val c = copy ?: return null
        c()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        return runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun act(which: Int) {
        val t = grab()
        hide()
        if (t == null) return
        when (which) {
            0 -> ctx.toast(tr("已复制", "Copied"))
            1 -> {
                val added = ClipStore.add(t, Prefs(ctx).maxItems, fromClipboard = false)
                ctx.toast(if (added) tr("已存为记录", "Saved to history") else tr("记录里已经有这段了", "Already in history"))
            }
            2 -> {
                ClipStore.markPicked(t)
                ctx.toast(if (ClipStore.addPhrase(t)) tr("已加入常用语", "Added to phrases") else tr("常用语里已有这段", "Already a phrase"))
            }
        }
    }

    fun all() { selectAll?.invoke() }

    @Composable
    fun Render() {
        val r = rect ?: return
        val cs = MaterialTheme.colorScheme
        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = (r.center.x.toInt() - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    var y = r.top.toInt() - popupContentSize.height - gap
                    if (y < 0) y = r.bottom.toInt() + gap
                    return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
                }
            },
            properties = PopupProperties(focusable = false),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(cs.inverseSurface).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(tr("复制", "Copy"), tr("存为记录", "Save to history"), tr("加入常用语", "Add as phrase")).forEachIndexed { i, label ->
                    Text(label, color = cs.inverseOnSurface, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { act(i) }.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                if (selectAll != null) Text(tr("全选", "All"), color = cs.inverseOnSurface.copy(alpha = 0.75f), fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { all() }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun rememberLibToolbar(): LibToolbar {
    val ctx = LocalContext.current
    return remember { LibToolbar(ctx) }
}

// ---------------- 编辑 / 新建 ----------------

@Composable
private fun NoteEditor(id: String?, onDone: (saved: String?) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val old = id?.let { LibraryStore.find(it) }
    var title by rememberSaveable { mutableStateOf(old?.title ?: "") }
    var srcTitle by rememberSaveable { mutableStateOf(old?.sourceTitle ?: "") }
    var srcAuthor by rememberSaveable { mutableStateOf(old?.sourceAuthor ?: "") }
    var srcUrl by rememberSaveable { mutableStateOf(old?.sourceUrl ?: "") }
    var body by remember { mutableStateOf<String?>(if (id == null) "" else null) }
    var shelf by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val tooLong = (old?.chars ?: 0) > EDIT_MAX
    LaunchedEffect(id) { if (id != null && !tooLong) LibraryStore.textAsync(id) { body = it ?: "" } }
    val shelves by LibraryStore.shelves.collectAsStateWithLifecycle()

    fun save() {
        if (id == null) {
            val b = body ?: ""
            if (b.isBlank()) { ctx.toast(tr("正文是空的", "The text is empty")); return }
            onDone(LibraryStore.add(b, title, shelf, srcTitle, srcAuthor, srcUrl))
        } else {
            LibraryStore.update(id, title, if (tooLong) null else body, srcTitle, srcAuthor, srcUrl)
            onDone(id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (id == null) tr("新建", "New text") else tr("编辑", "Edit"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ onDone(null) }) { Text(tr("取消", "Cancel")) }
            Button({ save() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(tr("保存", "Save")) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text(tr("标题（不填取正文开头）", "Title (defaults to the first line)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(srcTitle, { srcTitle = it }, label = { Text(tr("书名 / 出处", "Book / source")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(srcAuthor, { srcAuthor = it }, label = { Text(tr("作者", "Author")) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(srcUrl, { srcUrl = it }, label = { Text(tr("链接（可不填）", "Link (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (id == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("文集：", "Collection: "), fontSize = 14.sp, color = cs.onSurfaceVariant)
                    Text(shelves.firstOrNull { it.id == shelf }?.name ?: tr("未归类", "Unfiled"), fontSize = 14.sp, color = Accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { picking = true }.padding(8.dp))
                }
            }
            when {
                tooLong -> Text(tr("这篇有 %s，太长了，手机上只能改标题和出处。要改正文，请改好文件后重新导入。", "This text is %s — too long to edit here; only the title and source can be changed.", charsLabel(old?.chars ?: 0)),
                    fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                body == null -> Text(tr("正在读取…", "Loading…"), color = cs.onSurfaceVariant)
                else -> OutlinedTextField(body!!, { body = it }, label = { Text(tr("正文", "Text")) }, minLines = 12, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (picking) ShelfPickerDialog(shelf) { sid, chosen -> if (chosen) shelf = sid; picking = false }
}

// ---------------- 存进文库（选中文字、分享、记录菜单共用的小弹窗） ----------------

/**
 * 把一段文字存进文库：可以改标题、选文集、填出处。onDone(true) = 存好了。
 * @param asDialog false 时只画内容（放在透明 Activity 里时外面已经有卡片了）
 */
@Composable
fun SaveToLibraryForm(text: String, onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { LibraryStore.init(ctx) }
    val shelves by LibraryStore.shelves.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf(LibraryStore.defaultTitle(text).removeSuffix("…")) }
    var srcTitle by rememberSaveable { mutableStateOf("") }
    var srcAuthor by rememberSaveable { mutableStateOf("") }
    var shelf by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_book), null, Modifier.size(22.dp), tint = Accent)
            Spacer(Modifier.width(8.dp))
            Text(tr("存进文库", "Save to library"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(charsLabel(text.length), fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Text(text.take(200), maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).padding(10.dp))
        OutlinedTextField(title, { title = it }, label = { Text(tr("标题", "Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(srcTitle, { srcTitle = it }, label = { Text(tr("书名 / 出处", "Source")) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(srcAuthor, { srcAuthor = it }, label = { Text(tr("作者", "Author")) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Text(tr("文集", "Collection"), fontSize = 13.sp, color = cs.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(shelf == null, { shelf = null }, label = { Text(tr("未归类", "Unfiled")) }) }
            items(shelves, key = { it.id }) { g -> FilterChip(shelf == g.id, { shelf = g.id }, label = { Text(g.name) }) }
            item { AssistChip({ naming = true }, label = { Text(tr("＋ 新文集", "＋ New")) }) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ onDone(false) }) { Text(tr("取消", "Cancel")) }
            Spacer(Modifier.width(8.dp))
            Button({
                val id = LibraryStore.add(text, title, shelf, srcTitle, srcAuthor)
                ctx.toast(if (id != null) tr("已存进文库", "Saved to library") else tr("没有可以保存的文字", "Nothing to save"))
                onDone(id != null)
            }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(tr("存进文库", "Save")) }
        }
    }
    if (naming) TextEditDialog(tr("新建文集", "New collection"), "", singleLine = true) { name ->
        naming = false
        if (name != null) LibraryStore.addShelf(name)?.let { shelf = it }
    }
}

/** 在主界面里弹出“存进文库” */
@Composable
fun SaveToLibraryDialog(text: String, onDone: (Boolean) -> Unit) {
    Dialog(onDismissRequest = { onDone(false) }) {
        Box(Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) {
            SaveToLibraryForm(text, onDone)
        }
    }
}
