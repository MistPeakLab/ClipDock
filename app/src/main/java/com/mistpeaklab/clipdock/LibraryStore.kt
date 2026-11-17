package com.mistpeaklab.clipdock

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.mistpeaklab.clipdock.data.DbThread
import com.mistpeaklab.clipdock.data.GroupRow
import com.mistpeaklab.clipdock.data.ItemKind
import com.mistpeaklab.clipdock.data.ItemRepo
import com.mistpeaklab.clipdock.data.ItemText
import com.mistpeaklab.clipdock.data.NewItem
import com.mistpeaklab.clipdock.data.NoteRow
import com.mistpeaklab.clipdock.data.Uuid7
import com.mistpeaklab.clipdock.util.Search
import com.mistpeaklab.clipdock.util.TextDecode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 文库：存“以后可能要贴其中一部分”的长文字（读书摘抄、素材…），按文集归类。
 *
 * 和 [ClipStore] 的区别：篇章可能很长（一本书几十万字），所以内存里只放标题、出处、预览这些，
 * 正文要用时再从数据库读（[text]）。篇章存在 items 表里（kind=2），文集是 groups 表里 scope=1 的分组。
 * 篇章不受记录条数上限约束；删除同样进回收站。
 *
 * 和 ClipStore 一样：先改内存、马上通知界面，数据库写入排到数据库线程上按顺序执行。修改都在主线程。
 */
object LibraryStore {
    /** 新篇章没写标题时，取正文开头这么多字 */
    const val TITLE_CHARS = 20

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null

    private val _notes = MutableStateFlow<List<NoteRow>>(emptyList())
    /** 全部篇章，最近改过的在前 */
    val notes: StateFlow<List<NoteRow>> = _notes

    private val _shelves = MutableStateFlow<List<GroupRow>>(emptyList())
    /** 文集，按用户排的顺序 */
    val shelves: StateFlow<List<GroupRow>> = _shelves

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    private fun repo() = ItemRepo.get(app!!)
    private fun persist(block: (ItemRepo) -> Unit) = DbThread.post { block(repo()) }

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext ?: ctx
        ClipStore.init(c)
        app = c
        reload()
    }

    /** 重新从数据库读一遍（导入、从回收站恢复之后）。还没初始化时什么也不做 */
    fun reload() {
        if (app == null) return
        DbThread.post {
            val r = runCatching { repo().loadNotes() to repo().loadGroups().filter { it.scope == 1 } }
                .onFailure { Diag.log("DB", null, Diag.ERROR, (it.message ?: it.javaClass.simpleName).take(160)) }
                .getOrNull()
            main.post {
                if (r != null) {
                    _notes.value = sort(r.first)
                    _shelves.value = r.second
                }
                _loaded.value = true
                changed()
            }
        }
    }

    /** 等第一次读完，最多等 timeoutMs 毫秒（只用在没有界面、马上要结果的地方） */
    fun awaitLoaded(timeoutMs: Long = 3000): Boolean {
        if (_loaded.value) return true
        DbThread.await(timeoutMs)
        return true
    }

    private fun sort(l: List<NoteRow>) = l.sortedByDescending { it.updatedMs }

    fun find(id: String): NoteRow? = _notes.value.firstOrNull { it.id == id }

    /** 篇章的正文。会读数据库，等排在前面的写入完成（很快，但不要在列表滚动时反复调） */
    fun text(id: String): String? = runCatching {
        DbThread.call { repo().get(id)?.takeIf { it.live && it.kind == ItemKind.LIBRARY }?.text }
    }.getOrNull()

    /** 在后台读正文，读完在主线程回调 */
    fun textAsync(id: String, cb: (String?) -> Unit) = DbThread.post {
        val t = runCatching { repo().get(id)?.takeIf { it.live && it.kind == ItemKind.LIBRARY }?.text }.getOrNull()
        main.post { cb(t) }
    }

    /** 没写标题时：正文第一行的前 [TITLE_CHARS] 个字 */
    fun defaultTitle(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return tr("无标题", "Untitled")
        return if (first.length <= TITLE_CHARS) first else first.take(TITLE_CHARS).let {
            if (it.isNotEmpty() && Character.isHighSurrogate(it.last())) it.dropLast(1) else it
        } + "…"
    }

    private fun searchOf(text: String, title: String?, srcTitle: String?, srcAuthor: String?) =
        Search.build(Search.head(ItemRepo.searchSource(ItemKind.LIBRARY, text, title, srcTitle, srcAuthor), ItemRepo.SEARCH_CHARS))

    private fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 新建一篇。@return 新篇章的 ID；正文是空的返回 null
     */
    fun add(
        text: String, title: String? = null, shelfId: String? = null,
        sourceTitle: String? = null, sourceAuthor: String? = null, sourceUrl: String? = null,
    ): String? {
        val t = ItemText.limitLib(TextDecode.normalize(text).trim('\n', ' ', '\t', '\u3000'))
        if (t.isBlank()) return null
        val ttl = clean(title)?.take(200) ?: defaultTitle(t)
        val st = clean(sourceTitle); val sa = clean(sourceAuthor); val su = clean(sourceUrl)
        val now = System.currentTimeMillis()
        val id = Uuid7.next(now)
        val search = searchOf(t, ttl, st, sa)
        persist {
            it.insert(NewItem(id, ItemKind.LIBRARY, t, now, groupId = shelfId, title = ttl, sourceTitle = st, sourceAuthor = sa, sourceUrl = su), search)
        }
        val row = NoteRow(id, ttl, shelfId, st, sa, su, t.length, ItemText.preview(t, ItemKind.LIBRARY), now, now, search)
        synchronized(this) { _notes.value = sort(_notes.value + row) }
        changed()
        return id
    }

    /** 改标题、出处，text 不为空时连正文一起改 */
    fun update(id: String, title: String, text: String?, sourceTitle: String?, sourceAuthor: String?, sourceUrl: String?) {
        val old = find(id) ?: return
        val t = text?.let { ItemText.limitLib(TextDecode.normalize(it).trim('\n', ' ', '\t', '\u3000')) }?.takeIf { it.isNotBlank() }
        val ttl = clean(title)?.take(200) ?: old.title
        val st = clean(sourceTitle); val sa = clean(sourceAuthor); val su = clean(sourceUrl)
        val now = System.currentTimeMillis()
        val body = t ?: text(id) ?: return
        val search = searchOf(body, ttl, st, sa)
        persist { r ->
            r.transaction {
                if (t != null) r.setText(id, t, search, kind = ItemKind.LIBRARY)
                r.update(id, ContentValues().apply {
                    put("title", ttl); put("source_title", st); put("source_author", sa); put("source_url", su); put("search", search)
                })
            }
        }
        val row = old.copy(
            title = ttl, sourceTitle = st, sourceAuthor = sa, sourceUrl = su, updatedMs = now, search = search,
            chars = t?.length ?: old.chars, preview = t?.let { ItemText.preview(it, ItemKind.LIBRARY) } ?: old.preview,
        )
        synchronized(this) { _notes.value = sort(_notes.value.map { if (it.id == id) row else it }) }
        changed()
    }

    fun move(ids: Collection<String>, shelfId: String?) = synchronized(this) {
        val change = _notes.value.filter { it.id in ids && it.shelfId != shelfId }.map { it.id }
        if (change.isEmpty()) return@synchronized
        persist { r -> r.updateAll(change.map { it to ContentValues().apply { put("group_id", shelfId) } }) }
        _notes.value = _notes.value.map { if (it.id in change) it.copy(shelfId = shelfId) else it }
        changed()
    }

    /** 删除：进回收站（回收站关着时直接清掉） */
    fun delete(ids: Collection<String>) = synchronized(this) {
        if (ids.isEmpty()) return@synchronized
        val keep = app?.let { Features.isOn(it, Feature.TRASH) } ?: true
        val list = ids.toList()
        persist { it.softDelete(list); if (!keep) it.purge(list) }
        _notes.value = _notes.value.filter { it.id !in ids }
        changed()
    }

    // ---------------- 文集 ----------------

    fun addShelf(name: String): String? = synchronized(this) {
        val n = name.trim().take(30)
        if (n.isEmpty() || _shelves.value.any { it.name == n }) return null
        val now = System.currentTimeMillis()
        val g = GroupRow(Uuid7.next(now), n, (_shelves.value.maxOfOrNull { it.sortKey } ?: 0.0) + 1, 1, now, now, null)
        persist { it.insertGroup(g.id, g.name, g.sortKey, scope = 1) }
        _shelves.value = _shelves.value + g
        g.id
    }

    fun renameShelf(id: String, name: String): Boolean = synchronized(this) {
        val n = name.trim().take(30)
        if (n.isEmpty() || _shelves.value.any { it.name == n && it.id != id }) return false
        persist { it.updateGroup(id, ContentValues().apply { put("name", n) }) }
        _shelves.value = _shelves.value.map { if (it.id == id) it.copy(name = n) else it }
        true
    }

    /** 删除文集：里面的篇章不删，回到“未归类” */
    fun deleteShelf(id: String) = synchronized(this) {
        persist { it.deleteGroup(id) }
        _shelves.value = _shelves.value.filter { it.id != id }
        _notes.value = _notes.value.map { if (it.shelfId == id) it.copy(shelfId = null) else it }
    }

    // ---------------- 全文搜索 ----------------

    /** 一处命中：[offset] 是在正文里的位置（-1 = 只是标题 / 出处命中），[snippet] 是前后各 30 字，[mark] 是命中在 snippet 里的位置 */
    data class Hit(val noteId: String, val offset: Int, val snippet: String, val mark: IntRange?)

    /**
     * 跨篇章全文搜索：所有关键词都出现的篇章才算；每篇最多给 3 处命中，总共最多 [limit] 处。
     * 在数据库线程上逐篇读正文，结果在主线程回调。
     */
    fun search(query: String, limit: Int = 100, cb: (List<Hit>) -> Unit) {
        val terms = Search.terms(query)
        val snapshot = _notes.value
        if (terms.isEmpty()) { cb(emptyList()); return }
        DbThread.post {
            val out = ArrayList<Hit>()
            for (n in snapshot) {
                if (out.size >= limit) break
                val text = runCatching { repo().get(n.id)?.text }.getOrNull() ?: continue
                out += findIn(n, text, terms, 3)
            }
            main.post { cb(out.take(limit)) }
        }
    }

    /** 在一篇里找命中（纯函数，单元测试用） */
    internal fun findIn(n: NoteRow, text: String, terms: List<String>, max: Int): List<Hit> {
        val norm = Search.normalize(text)
        val head = Search.normalize(listOfNotNull(n.title, n.sourceTitle, n.sourceAuthor).joinToString(" "))
        if (!terms.all { norm.contains(it) || head.contains(it) }) return emptyList()
        val first = terms.maxByOrNull { it.length } ?: return emptyList()
        val out = ArrayList<Hit>()
        var i = norm.indexOf(first)
        while (i >= 0 && out.size < max) {
            val s = (i - 30).coerceAtLeast(0)
            val e = (i + first.length + 30).coerceAtMost(text.length)
            if (s < e && i <= text.length) {
                val snip = text.substring(s, e).replace('\n', ' ')
                val m = (i - s) until (i - s + first.length).coerceAtMost(snip.length)
                out += Hit(n.id, i, (if (s > 0) "…" else "") + snip + (if (e < text.length) "…" else ""),
                    if (s > 0) (m.first + 1)..(m.last + 1) else m)
            }
            i = norm.indexOf(first, i + first.length)
        }
        if (out.isEmpty()) out += Hit(n.id, -1, n.preview.take(80), null)
        return out
    }

    // ---------------- 导入 txt / md ----------------

    /** 一个文件一篇，文件名（去掉扩展名）当标题。回调：(导入了几篇, 没导入的文件名和原因) */
    fun importFiles(ctx: Context, uris: List<Uri>, shelfId: String?, cb: (Int, List<String>) -> Unit) {
        val cr = ctx.contentResolver
        Thread {
            val ok = ArrayList<Pair<String, String>>()
            val bad = ArrayList<String>()
            for (u in uris) {
                val name = runCatching {
                    cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: u.lastPathSegment ?: "?"
                val r = runCatching {
                    val bytes = cr.openInputStream(u)!!.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val b = ByteArray(64 * 1024)
                        while (true) {
                            val k = input.read(b); if (k < 0) break
                            buf.write(b, 0, k)
                            if (buf.size() > MAX_FILE_BYTES) throw java.io.IOException(tr("文件太大（超过 20 MB）", "Too large (over 20 MB)"))
                        }
                        buf.toByteArray()
                    }
                    TextDecode.decode(bytes)
                }
                r.onSuccess { t -> if (t.isBlank()) bad += tr("%s：是空的", "%s: empty", name) else ok += name.substringBeforeLast('.').ifBlank { name } to t }
                    .onFailure { bad += "$name：" + (it.message ?: it.javaClass.simpleName).take(80) }
            }
            main.post {
                var n = 0
                for ((title, t) in ok) if (add(t, title, shelfId) != null) n++
                cb(n, bad)
            }
        }.start()
    }

    private const val MAX_FILE_BYTES = 20 * 1024 * 1024

    /** 单元测试用 */
    internal fun resetForTest() = synchronized(this) {
        app = null
        _notes.value = emptyList()
        _shelves.value = emptyList()
        _loaded.value = false
        listeners.clear()
    }
}
