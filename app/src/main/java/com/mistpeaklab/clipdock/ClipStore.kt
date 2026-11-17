package com.mistpeaklab.clipdock

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.mistpeaklab.clipdock.data.AppRule
import com.mistpeaklab.clipdock.data.Backup
import com.mistpeaklab.clipdock.data.Db
import com.mistpeaklab.clipdock.data.DbThread
import com.mistpeaklab.clipdock.data.GroupRow
import com.mistpeaklab.clipdock.data.ImportStats
import com.mistpeaklab.clipdock.data.ItemKind
import com.mistpeaklab.clipdock.data.ItemRepo
import com.mistpeaklab.clipdock.data.ItemRow
import com.mistpeaklab.clipdock.data.ItemText
import com.mistpeaklab.clipdock.data.LegacyMigrator
import com.mistpeaklab.clipdock.data.NewItem
import com.mistpeaklab.clipdock.data.RestoreResult
import com.mistpeaklab.clipdock.data.TrashRow
import com.mistpeaklab.clipdock.data.Uuid7
import com.mistpeaklab.clipdock.data.Vault
import com.mistpeaklab.clipdock.util.ContentKind
import com.mistpeaklab.clipdock.util.ContentRules
import com.mistpeaklab.clipdock.util.Search
import com.mistpeaklab.clipdock.util.Sensitivity
import com.mistpeaklab.clipdock.util.TextTransforms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * @param id        UUID v7（v0.6 起；以前是数字）
 * @param text      正文。隐藏的条目这里是空串，内容在 [cipher] 里，用 [ClipStore.plain] 取
 * @param phrase    常用语：手动维护，可编辑、可排序，不参与条数上限和“清空”
 * @param time      历史记录为最近一次复制的时间；常用语用它作排序键（越大越靠前）
 * @param search    搜索串（小写原文 + 拼音），见 [Search]。隐藏的条目只含备注
 * @param hasLink   文字里有没有网址（“链接”分类用，记下来免得每次都重新找）
 * @param hash      原文的 SHA-256。只有隐藏的条目用得到（用来判断“又复制了一次同样的内容”）
 * @param hiddenLen 隐藏条目的原文字数
 * @param expireAt  到这个时间（毫秒）自动清除，用于验证码
 */
data class ClipItem(
    val id: String,
    val text: String,
    val time: Long,
    val pinned: Boolean = false,
    val phrase: Boolean = false,
    val sourcePkg: String? = null,
    val groupId: String? = null,
    val hidden: Boolean = false,
    val note: String? = null,
    val search: String = "",
    val hasLink: Boolean = false,
    val hash: String = "",
    val hiddenLen: Int = 0,
    val expireAt: Long? = null,
    val cipher: ByteArray? = null,
) {
    /** 字数 */
    val chars: Int get() = if (hidden) hiddenLen else text.length

    /** 列表里显示用：去掉开头空白后的前 [PREVIEW_CHARS] 个字。长文不要整篇交给界面去排版，那样滚动会卡 */
    val preview: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var s = 0
        while (s < text.length && text[s].isWhitespace()) s++
        var e = minOf(text.length, s + PREVIEW_CHARS)
        // 别把一个代理对（比如表情符号）从中间切开
        if (e < text.length && e > s && Character.isHighSurrogate(text[e - 1])) e--
        text.substring(s, e).trimEnd()
    }

    /** 整条内容是什么（网址、电话…）。隐藏的不判断 */
    val kind: ContentKind by lazy(LazyThreadSafetyMode.PUBLICATION) { if (hidden) ContentKind.TEXT else ContentRules.kindOf(text) }

    /** terms 来自 [Search.terms]；pinyin 为 false 时只匹配原文和备注，不匹配拼音 */
    fun matches(terms: List<String>, pinyin: Boolean): Boolean {
        if (terms.isEmpty()) return true
        if (pinyin) return Search.matches(search, terms)
        // 搜索串的第一段是原文（和备注），到第一个分隔符为止。关键词里不会有分隔符，所以只看起点
        val end = search.indexOf(Search.SEP).let { if (it < 0) search.length else it }
        return terms.all { t -> search.indexOf(t).let { it in 0 until end } }
    }

    companion object {
        const val PREVIEW_CHARS = 600
    }
}

/**
 * 剪贴板历史 + 常用语 + 分组 + 应用规则。v0.6 起存在 SQLite 里（[ItemRepo]），不联网。
 *
 * 界面像以前一样同步地读写这里的内存列表：每次修改先改内存、立刻通知界面，
 * 再把对应的数据库写入排到数据库线程上，按操作顺序依次执行。
 * 所有修改都发生在主线程（无障碍服务、Activity、面板都在主线程）。
 */
object ClipStore {
    /** 超过这么多字算“长文” */
    const val LARGE_CHARS = 5_000

    /** 列表里的长文合计最多这么多字（收藏的不算），更旧的长文会被淘汰进回收站。记录整篇放在内存里，长文太多会把内存占满 */
    const val LARGE_BUDGET = 10_000_000L

    /** 全部未收藏记录合计的字数上限。正常使用碰不到，只是防止内存被占满的最后一道保险 */
    const val TOTAL_BUDGET = 20_000_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var lastPicked: String? = null

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    /** 全部条目（历史 + 常用语），已排好序：收藏的在前，其余从新到旧 */
    val items: StateFlow<List<ClipItem>> = _items

    private val _groups = MutableStateFlow<List<GroupRow>>(emptyList())
    /** 分组，按用户排的顺序 */
    val groups: StateFlow<List<GroupRow>> = _groups

    private val _rules = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** 应用规则：包名 → [AppRule] */
    val rules: StateFlow<Map<String, Int>> = _rules

    /** 当前系统剪贴板里的文字（就我们所知） */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    private val _loaded = MutableStateFlow(false)
    /** 数据库（含首次的旧记录迁移）是否已读完。没读完时界面不要显示“还没有记录” */
    val loaded: StateFlow<Boolean> = _loaded

    private val _notice = MutableStateFlow<String?>(null)
    /** 需要让用户知道的存储问题（数据库打不开、旧记录没能导入、有改动没存上）；正常为 null */
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    /** 连续收集的状态。[id] 为 null 表示已经开始、还没收到第一段 */
    class Collect(val id: String?, val parts: Int)
    private val _collect = MutableStateFlow<Collect?>(null)
    /** 正在连续收集时非空 */
    val collect: StateFlow<Collect?> = _collect

    /** 数据库读完之前到达的新内容：此刻还不知道它是不是已有的，读完后按顺序补记 */
    private class Pending(val text: String, val sourcePkg: String?)
    private val pending = ArrayList<Pending>()

    /** 数据库线程读完的结果，等主线程来取（见 [finishLoad]） */
    private class LoadResult(val list: List<ClipItem>, val groups: List<GroupRow>, val rules: Map<String, Int>, val notice: String?)
    @Volatile private var loadResult: LoadResult? = null

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun repo() = ItemRepo.get(app!!)
    private fun prefs() = Prefs(app!!)
    private fun limit() = prefs().maxItems
    private fun on(f: Feature) = Features.isOn(app!!, f)

    /** 排一个数据库写入，按调用顺序执行。出错会记进诊断并提示用户（见 [onDbError]） */
    private fun persist(block: (ItemRepo) -> Unit) = DbThread.post { block(repo()) }

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext ?: context
        app = ctx
        Diag.init(ctx)
        L.init(ctx)
        // “全新安装还是从旧版升级”要看旧的记录文件在不在（见 Prefs），得赶在迁移把文件改名之前判断
        Prefs(ctx)
        DbThread.onError = { e ->
            Diag.log("DB", null, Diag.ERROR, describe(e))
            main.post { onDbError(e) }
        }
        DbThread.post {
            loadResult = loadAll(ctx)
            main.post { finishLoad() }
        }
    }

    private fun loadAll(ctx: Context): LoadResult {
        var notice: String? = null
        return try {
            val repo = repo()
            try {
                when (val r = LegacyMigrator.run(ctx, repo)) {
                    is LegacyMigrator.Result.Imported -> Diag.log("MIGRATE", null, Diag.OK, tr("从旧版本导入 %d 条", "Imported %d items from the old version", r.added))
                    LegacyMigrator.Result.Unreadable -> {
                        Diag.log("MIGRATE", null, Diag.ERROR, "unreadable history.json")
                        notice = tr(
                            "旧版本的记录文件已经损坏，里面的内容没法导入。文件留了一份备份，没有删除。",
                            "The old history file is damaged and could not be imported. A copy was kept.",
                        )
                    }
                    LegacyMigrator.Result.Nothing -> {}
                }
            } catch (e: Throwable) {
                // 旧文件原样留着，下次启动会再试；先把已有的显示出来
                Diag.log("MIGRATE", null, Diag.ERROR, describe(e))
                notice = tr(
                    "旧版本的记录没能导入（%s）。原来的文件还在，没有丢，下次打开随贴会再试一次。",
                    "Could not import the old history (%s). The file is still there; ClipDock will try again next time.", describe(e),
                )
            }
            runCatching { repo.cleanExpired(now()) }.onFailure { Diag.log("DB", null, Diag.ERROR, describe(it)) }
            Vault.load(repo.getMeta(Vault.META_KEY))
            if (Vault.state == Vault.STATE_BROKEN && notice == null) notice = tr(
                "隐藏的内容需要的密钥不见了（系统清除了应用的密钥），这些内容已经无法显示。可以在 我的 › 隐私 里清理掉它们。",
                "The key for hidden items is gone (the system cleared it), so they can no longer be shown. You can clear them in Me › Privacy.",
            )
            val list = repo.loadLive().map(::toItem)
            if (Db.lastCorrupt != null && notice == null) notice = tr(
                "记录文件损坏，已经换了一个新的；坏掉的文件留了一份备份。升级前的记录如果还有备份，已经自动补回。",
                "The history file was damaged and has been replaced; the damaged file was kept. Records from before the upgrade were restored from backup where possible.",
            )
            LoadResult(list, repo.loadGroups(), repo.loadRules(), notice)
        } catch (e: Throwable) {
            Diag.log("DB", null, Diag.ERROR, describe(e))
            LoadResult(
                emptyList(), emptyList(), emptyMap(),
                tr(
                    "记录读不出来，新复制的内容暂时也存不下（%s）。可以到 我的 › 收录诊断 复制诊断信息反馈。",
                    "History could not be read and new items cannot be saved (%s). Please send the report from Me › Diagnostics.", describe(e),
                ),
            )
        }
    }

    /**
     * 把读库的结果接进来（主线程）。可能被调两次：[init] 里排的那次，和 [awaitLoaded] 等到后直接调的那次；
     * 第二次进来时结果已被取走，什么都不做。
     */
    private fun finishLoad() {
        val late: List<Pending>
        synchronized(this) {
            val r = loadResult ?: return
            loadResult = null
            if (r.notice != null) _notice.value = r.notice
            _groups.value = r.groups
            _rules.value = r.rules
            _loaded.value = true
            commit(r.list, limit())
            late = pending.toList()
            pending.clear()
        }
        // “当前剪贴板”和“自己写回的不记”在它们到达时已经处理过了，这里只管记下来
        late.forEach { add(it.text, limit(), fromClipboard = false, sourcePkg = it.sourcePkg) }
    }

    /**
     * 等数据库读完，最多等 timeoutMs 毫秒；读完了返回 true。只能在主线程调用。
     * 会卡住界面，只用在必须马上知道结果、而且没有界面在显示的地方（“存到随贴”）。
     */
    fun awaitLoaded(timeoutMs: Long = 3000): Boolean {
        if (_loaded.value) return true
        if (!DbThread.await(timeoutMs)) return false
        finishLoad()
        return _loaded.value
    }

    /** 等排着队的写入都存好，最多等 timeoutMs 毫秒；都存好了返回 true */
    fun flush(timeoutMs: Long = 2000): Boolean = DbThread.await(timeoutMs)

    private fun onDbError(e: Throwable) = synchronized(this) {
        if (e is SQLiteDatabaseCorruptException) {
            // 坏文件已改名留底，接下来用的是一个新的空库（见 Db）。
            // 内存里的列表还是完整的，把它整个重新存一遍；已经在新库里的不会重复
            val all = _items.value.mapNotNull { i -> toNew(i)?.let { it to i.search } }
            val wrapped = Vault.wrapped
            val groups = _groups.value
            persist { r ->
                if (wrapped != null) r.putMeta(Vault.META_KEY, wrapped)
                for (g in groups) r.insertGroup(g.id, g.name, g.sortKey, g.scope)
                // 此刻内存里的列表就是全部内容，同时记下“旧版本的记录已经导入过”，免得下次启动又导一遍
                r.insertAll(all, markMigrated = true)
            }
            _notice.value = tr(
                "记录文件损坏，已经换了一个新的，并把当前列表重新存了进去。坏掉的文件留了一份备份；回收站里的内容没能保住。",
                "The history file was damaged. A new one was created and the current list saved into it; the damaged file was kept. Items in the trash could not be saved.",
            )
        } else {
            _notice.value = tr(
                "有一次改动没能保存（%s）。重新打开随贴后，以已经保存的内容为准。",
                "A change could not be saved (%s). After reopening ClipDock, what was saved is what you will see.", describe(e),
            )
        }
    }

    private fun describe(e: Throwable) = (e.message ?: e.javaClass.simpleName).take(160)

    private fun toItem(r: ItemRow): ClipItem {
        val phrase = r.kind == ItemKind.PHRASE
        return ClipItem(
            id = r.id, text = r.text, time = if (phrase) (r.sortKey ?: 0.0).toLong() else r.usedAt, pinned = r.pinned, phrase = phrase,
            sourcePkg = r.sourcePkg, groupId = r.groupId, hidden = r.hidden, note = r.note, search = r.search,
            hasLink = !r.hidden && hasLink(r.text), hash = r.hash, hiddenLen = if (r.hidden) r.chars else 0,
            expireAt = r.expireAt, cipher = r.cipher,
        )
    }

    /** 重新存一条（数据库损坏重建时用）。隐藏的条目要能解开才存得了 */
    private fun toNew(i: ClipItem): NewItem? {
        val text = plain(i) ?: return null
        return NewItem(
            id = i.id, kind = if (i.phrase) ItemKind.PHRASE else ItemKind.CLIP, text = text,
            nowMs = if (i.phrase) now() else i.time, sourcePkg = i.sourcePkg, groupId = i.groupId, pinned = i.pinned,
            expireAt = i.expireAt, sortKey = if (i.phrase) i.time.toDouble() else null, note = i.note, cipher = i.cipher,
        )
    }

    private val urlRegex = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private fun hasLink(text: String) = urlRegex.containsMatchIn(text)

    private fun searchOf(text: String, note: String?) = Search.build(Search.head(text, ItemRepo.SEARCH_CHARS), note)

    private fun find(id: String) = _items.value.firstOrNull { it.id == id }

    /** 历史记录：收藏的在前，其余按时间从新到旧 */
    fun history(): List<ClipItem> = _items.value.filter { !it.phrase }

    /** 常用语：按用户排序 */
    fun phrases(): List<ClipItem> = _items.value.filter { it.phrase }

    /** 收藏：常用语（按用户排序）在前，然后是收藏的记录 */
    fun favorites(): List<ClipItem> = _items.value.let { l -> l.filter { it.phrase } + l.filter { !it.phrase && it.pinned } }

    /** 条目的正文。隐藏的条目解不开时返回 null（是否已验证身份由界面负责，见 [Unlock]） */
    fun plain(item: ClipItem): String? = if (!item.hidden) item.text else Vault.open(item.cipher)

    // ---------------- 记录 ----------------

    /**
     * 记录新复制的内容。已存在则移到最前；本应用刚写回剪贴板的内容不重复记录。
     * @param fromClipboard 内容是否来自系统剪贴板；“存到随贴”、分享进来的文字不是，不能把它当成“当前剪贴板”
     * @param sourcePkg 复制时所在的应用（说不准就不传）
     * @return 列表是否因此有了变化。数据库还没读完时无法判断，一律返回 true
     */
    fun add(text: String, maxItems: Int, fromClipboard: Boolean = true, sourcePkg: String? = null): Boolean {
        if (text.isBlank()) return false
        val t = ItemText.limit(text)
        synchronized(this) {
            if (fromClipboard) {
                _current.value = t
                if (t == lastPicked) return false
                lastPicked = null
            }
            if (!_loaded.value) {
                pending += Pending(t, sourcePkg)
                return true
            }
            val rule = sourcePkg?.let { _rules.value[it] }
            if (rule == AppRule.SKIP) return false
            _collect.value?.let { if (fromClipboard) return collectInto(it, t, sourcePkg, maxItems) }

            val list = _items.value
            val h = ItemText.sha256(t)
            val existing = list.firstOrNull { !it.phrase && (if (it.hidden) it.hash == h else it.text == t) }
            val at = now()
            if (existing != null) {
                val newest = list.filter { !it.phrase && !it.pinned }.maxByOrNull { it.time }
                if (!existing.pinned && newest?.id == existing.id) return false
                persist { it.touch(existing.id, at, sourcePkg) }
                commit(list.map { if (it.id == existing.id) it.copy(time = at, sourcePkg = sourcePkg ?: it.sourcePkg) else it }, maxItems)
                return true
            }

            // 敏感内容：验证码过一会儿自动清除；验证码、卡号、指定应用里的内容自动隐藏
            val sens = if (on(Feature.SENSITIVE_DETECT)) ContentRules.sensitivity(t) else Sensitivity.NONE
            val otpMin = prefs().otpMinutes
            val expire = if (sens == Sensitivity.OTP && otpMin > 0) System.currentTimeMillis() + otpMin * 60_000L else null
            val wantHide = on(Feature.HIDE) && (rule == AppRule.HIDE || sens != Sensitivity.NONE)
            val cipher = if (wantHide && t.length <= ItemRepo.HIDE_MAX_CHARS) seal(t) else null
            val item = ClipItem(
                id = Uuid7.next(at), text = if (cipher != null) "" else t, time = at, sourcePkg = sourcePkg,
                hidden = cipher != null, search = searchOf(if (cipher != null) "" else t, null),
                hasLink = cipher == null && hasLink(t), hash = h, hiddenLen = if (cipher != null) t.length else 0,
                expireAt = expire, cipher = cipher,
            )
            persist { it.insert(NewItem(item.id, ItemKind.CLIP, t, at, sourcePkg, expireAt = expire, cipher = cipher), item.search) }
            commit(list + item, maxItems)
        }
        return true
    }

    /** 连续收集：第一段新建一条，之后的接在它后面 */
    private fun collectInto(c: Collect, t: String, sourcePkg: String?, maxItems: Int): Boolean {
        val list = _items.value
        val cur = c.id?.let { id -> list.firstOrNull { it.id == id && !it.hidden } }
        val at = now()
        if (cur == null) {
            val item = ClipItem(Uuid7.next(at), t, at, sourcePkg = sourcePkg, search = searchOf(t, null), hasLink = hasLink(t))
            persist { it.insert(NewItem(item.id, ItemKind.CLIP, t, at, sourcePkg), item.search) }
            _collect.value = Collect(item.id, 1)
            commit(list + item, maxItems)
        } else {
            val merged = ItemText.limit(cur.text + "\n" + t)
            val search = searchOf(merged, cur.note)
            persist { it.setText(cur.id, merged, search); it.touch(cur.id, at) }
            _collect.value = Collect(cur.id, c.parts + 1)
            commit(list.map { if (it.id == cur.id) it.copy(text = merged, time = at, search = search, hasLink = hasLink(merged)) else it }, maxItems)
        }
        return true
    }

    fun startCollect() { if (_collect.value == null) _collect.value = Collect(null, 0) }

    /** @return 收集到的那一条的 ID（一段都没收到时为 null） */
    fun stopCollect(): String? {
        val id = _collect.value?.id
        _collect.value = null
        return id
    }

    /** 本应用把内容写回剪贴板时调用 */
    fun markPicked(text: String) {
        lastPicked = text
        _current.value = text
    }

    /** 收藏（置顶）开关 */
    fun togglePin(id: String) = find(id)?.let { setPinned(listOf(id), !it.pinned) }

    fun setPinned(ids: Collection<String>, on: Boolean) = synchronized(this) {
        val change = _items.value.filter { it.id in ids && !it.phrase && it.pinned != on }.map { it.id }.toSet()
        if (change.isEmpty()) return@synchronized
        persist { r -> r.updateAll(change.map { it to ContentValues().apply { put("pinned", if (on) 1 else 0) } }) }
        // 这里不按条数上限淘汰：否则刚取消收藏的旧内容会当场从眼前消失。等下次有新内容进来时再一起算
        commit(_items.value.map { if (it.id in change) it.copy(pinned = on) else it }, Int.MAX_VALUE)
        if (on) com.mistpeaklab.clipdock.floatball.FloatBall.react(com.mistpeaklab.clipdock.floatball.Mascot.HAPPY)
    }

    /** 删除：进回收站（回收站功能关闭时直接清掉） */
    fun delete(id: String) = deleteAll(setOf(id))

    fun deleteAll(ids: Set<String>) = synchronized(this) {
        if (ids.isEmpty()) return@synchronized
        val keep = on(Feature.TRASH)
        persist { it.softDelete(ids); if (!keep) it.purge(ids) }
        if (_collect.value?.id in ids) _collect.value = null
        commit(_items.value.filter { it.id !in ids }, Int.MAX_VALUE)
    }

    /** 清空历史：保留收藏和常用语 */
    fun clearUnpinned() = deleteAll(_items.value.filter { !it.pinned && !it.phrase }.mapTo(HashSet()) { it.id })

    /** 清空全部历史（含收藏的记录），常用语保留 */
    fun clearAll() = deleteAll(_items.value.filter { !it.phrase }.mapTo(HashSet()) { it.id })

    fun applyLimit(maxItems: Int) = synchronized(this) { commit(_items.value, maxItems) }

    /** 备注：空白视为没有备注 */
    fun setNote(id: String, note: String?) = synchronized(this) {
        val item = find(id) ?: return@synchronized
        val n = note?.trim()?.takeIf { it.isNotEmpty() }
        if (n == item.note) return@synchronized
        val search = searchOf(if (item.hidden) "" else item.text, n)
        persist { it.update(id, ContentValues().apply { put("note", n); put("search", search) }) }
        commit(_items.value.map { if (it.id == id) it.copy(note = n, search = search) else it }, Int.MAX_VALUE)
    }

    /** 改正文（记录和常用语都可以）。隐藏的条目改完仍然隐藏 */
    fun updateText(id: String, text: String) = synchronized(this) {
        val t = ItemText.limit(text.trim())
        val item = find(id)
        if (t.isEmpty() || item == null || (!item.hidden && item.text == t)) return@synchronized
        if (item.hidden) {
            val cipher = Vault.seal(t) ?: return@synchronized
            val search = searchOf("", item.note)
            persist { it.setText(id, t, search, cipher) }
            commit(_items.value.map { if (it.id == id) it.copy(cipher = cipher, hash = ItemText.sha256(t), hiddenLen = t.length) else it }, Int.MAX_VALUE)
        } else {
            val search = searchOf(t, item.note)
            persist { it.setText(id, t, search) }
            commit(_items.value.map { if (it.id == id) it.copy(text = t, search = search, hasLink = hasLink(t)) else it }, Int.MAX_VALUE)
        }
    }

    // ---------------- 隐藏 ----------------

    /** 加密一段文字；还没有密钥就先建一把（密钥先存进数据库，再拿来用） */
    private fun seal(t: String): ByteArray? {
        if (!Vault.ready) {
            if (Vault.state == Vault.STATE_BROKEN) return null
            val (wrapped, raw) = Vault.generate() ?: return null
            persist { it.putMeta(Vault.META_KEY, wrapped) }
            Vault.adopt(wrapped, raw)
        }
        return Vault.seal(t)
    }

    /** 能不能隐藏这一条（太长的不行，这台设备没有可用的密钥库也不行） */
    fun canHide(item: ClipItem): Boolean = !item.hidden && item.text.length <= ItemRepo.HIDE_MAX_CHARS && Vault.state != Vault.STATE_BROKEN

    fun hide(id: String): Boolean = synchronized(this) {
        val item = find(id) ?: return false
        if (item.hidden) return true
        if (!canHide(item)) return false
        val cipher = seal(item.text) ?: return false
        val search = searchOf("", item.note)
        persist { it.setHidden(id, cipher, item.text, search) }
        if (_collect.value?.id == id) _collect.value = null
        commit(_items.value.map {
            if (it.id == id) it.copy(text = "", hidden = true, cipher = cipher, search = search, hasLink = false,
                hash = ItemText.sha256(item.text), hiddenLen = item.text.length) else it
        }, Int.MAX_VALUE)
        true
    }

    /** 取消隐藏。调用前界面要先验证过身份 */
    fun unhide(id: String): Boolean = synchronized(this) {
        val item = find(id) ?: return false
        if (!item.hidden) return true
        val t = Vault.open(item.cipher) ?: return false
        val search = searchOf(t, item.note)
        persist { it.setHidden(id, null, t, search) }
        commit(_items.value.map {
            if (it.id == id) it.copy(text = t, hidden = false, cipher = null, search = search, hasLink = hasLink(t), hiddenLen = 0) else it
        }, Int.MAX_VALUE)
        true
    }

    /** 密钥丢了、再也解不开的隐藏条目：全部彻底删除，并重新开始 */
    fun dropUnreadableHidden() = synchronized(this) {
        val ids = _items.value.filter { it.hidden }.mapTo(HashSet()) { it.id }
        Vault.forget()
        persist { r ->
            r.removeMeta(Vault.META_KEY)
            r.softDelete(ids)
            r.purge(ids)
        }
        _notice.value = null
        commit(_items.value.filter { it.id !in ids }, Int.MAX_VALUE)
    }

    // ---------------- 拆分 / 合并 ----------------

    /** 按行拆成多条，原来那条移进回收站。@return 拆出的条数；不满两行返回 0 */
    fun split(id: String): Int = synchronized(this) {
        val item = find(id) ?: return 0
        if (item.hidden || item.phrase) return 0
        val parts = TextTransforms.splitLines(item.text).distinct()
        if (parts.size < 2) return 0
        val stamps = List(parts.size) { now() }.reversed() // 第一行最新，排在最上面
        val keep = on(Feature.TRASH)
        val list = ArrayList(_items.value.filter { it.id != item.id })
        persist { it.softDelete(listOf(item.id)); if (!keep) it.purge(listOf(item.id)) }
        parts.forEachIndexed { i, p ->
            val at = stamps[i]
            val twin = list.indexOfFirst { !it.phrase && !it.hidden && it.text == p }
            if (twin >= 0) {
                val tw = list[twin]
                persist { it.touch(tw.id, at) }
                list[twin] = tw.copy(time = at)
            } else {
                val n = ClipItem(Uuid7.next(at), p, at, sourcePkg = item.sourcePkg, groupId = item.groupId, search = searchOf(p, null), hasLink = hasLink(p))
                persist { it.insert(NewItem(n.id, ItemKind.CLIP, p, at, n.sourcePkg, n.groupId), n.search) }
                list += n
            }
        }
        commit(list, limit())
        parts.size
    }

    /** 把几条按列表顺序合并成新的一条（原来的不动）。隐藏的不参与。@return 新条目的 ID */
    fun merge(ids: Collection<String>, separator: String): String? = synchronized(this) {
        val texts = _items.value.filter { it.id in ids && !it.hidden }.map { it.text }
        if (texts.size < 2) return null
        val joined = ItemText.limit(TextTransforms.joinItems(texts, separator))
        add(joined, limit(), fromClipboard = false)
        _items.value.firstOrNull { !it.phrase && !it.hidden && it.text == joined }?.id
    }

    // ---------------- 分组 ----------------

    /** @return 新分组的 ID；名字为空或重名返回 null */
    fun addGroup(name: String): String? = synchronized(this) {
        val n = name.trim().take(30)
        if (n.isEmpty() || _groups.value.any { it.name == n }) return null
        val at = now()
        val g = GroupRow(Uuid7.next(at), n, (_groups.value.maxOfOrNull { it.sortKey } ?: 0.0) + 1, 0, at, at, null)
        persist { it.insertGroup(g.id, g.name, g.sortKey) }
        _groups.value = _groups.value + g
        g.id
    }

    fun renameGroup(id: String, name: String): Boolean = synchronized(this) {
        val n = name.trim().take(30)
        if (n.isEmpty() || _groups.value.any { it.name == n && it.id != id }) return false
        persist { it.updateGroup(id, ContentValues().apply { put("name", n) }) }
        _groups.value = _groups.value.map { if (it.id == id) it.copy(name = n) else it }
        true
    }

    /** 删除分组：里面的条目不删，回到“未分组” */
    fun deleteGroup(id: String) = synchronized(this) {
        persist { it.deleteGroup(id) }
        _groups.value = _groups.value.filter { it.id != id }
        commit(_items.value.map { if (it.groupId == id) it.copy(groupId = null) else it }, Int.MAX_VALUE)
    }

    fun moveGroup(id: String, delta: Int) = synchronized(this) {
        val gs = _groups.value
        val i = gs.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in gs.indices) return@synchronized
        val a = gs[i]; val b = gs[j]
        persist {
            it.updateGroup(a.id, ContentValues().apply { put("sort_key", b.sortKey) })
            it.updateGroup(b.id, ContentValues().apply { put("sort_key", a.sortKey) })
        }
        _groups.value = gs.map {
            when (it.id) { a.id -> it.copy(sortKey = b.sortKey); b.id -> it.copy(sortKey = a.sortKey); else -> it }
        }.sortedBy { it.sortKey }
    }

    /** 把几条放进某个分组；groupId 为 null 表示移出分组 */
    fun setGroup(ids: Collection<String>, groupId: String?) = synchronized(this) {
        val change = _items.value.filter { it.id in ids && it.groupId != groupId }.map { it.id }.toSet()
        if (change.isEmpty()) return@synchronized
        persist { r -> r.updateAll(change.map { it to ContentValues().apply { put("group_id", groupId) } }) }
        commit(_items.value.map { if (it.id in change) it.copy(groupId = groupId) else it }, Int.MAX_VALUE)
    }

    // ---------------- 应用规则 ----------------

    /** rule 为 null 表示正常记录 */
    fun setRule(pkg: String, rule: Int?) = synchronized(this) {
        persist { it.setRule(pkg, rule) }
        _rules.value = if (rule == null) _rules.value - pkg else _rules.value + (pkg to rule)
    }

    // ---------------- 回收站 ----------------

    /** 回收站内容。要读库，结果在主线程回调；读不出来时回调空列表 */
    fun trash(cb: (List<TrashRow>) -> Unit) = DbThread.post {
        val l = runCatching { repo().trash() }.onFailure { Diag.log("DB", null, Diag.ERROR, describe(it)) }.getOrDefault(emptyList())
        main.post { cb(l) }
    }

    fun restore(id: String, cb: (RestoreResult) -> Unit = {}) = DbThread.post {
        val r = runCatching { repo().restore(id, now()) }.onFailure { Diag.log("DB", null, Diag.ERROR, describe(it)) }
            .getOrDefault(RestoreResult.Gone)
        main.post {
            if (r is RestoreResult.Ok) mergeIn(listOf(r.row))
            cb(r)
        }
    }

    /** 从回收站里彻底清掉，不可恢复 */
    fun purge(ids: Collection<String>, cb: () -> Unit = {}) = DbThread.post {
        runCatching { repo().purge(ids) }.onFailure { Diag.log("DB", null, Diag.ERROR, describe(it)) }
        main.post(cb)
    }

    /** 本机是否还留着旧版本（0.5 及以前）的记录文件 */
    fun hasLegacyBackup(): Boolean = app?.let { LegacyMigrator.hasBackup(it) } ?: false

    /**
     * 把旧版本的记录文件再导入一次，列表里已有的不会重复。
     * 回调参数是新增的条数；-1 表示没有旧文件或读不了。
     */
    fun reimportLegacy(cb: (Int) -> Unit) = DbThread.post {
        val rows = runCatching {
            val repo = repo()
            LegacyMigrator.reimport(app!!, repo)?.mapNotNull { repo.get(it) }
        }.onFailure { Diag.log("MIGRATE", null, Diag.ERROR, describe(it)) }.getOrNull()
        main.post {
            if (rows != null) {
                mergeIn(rows)
                Diag.log("MIGRATE", null, Diag.OK, "reimport +${rows.size}")
            }
            cb(rows?.size ?: -1)
        }
    }

    /** 把数据库里刚恢复 / 刚导入的行并进内存列表（主线程） */
    private fun mergeIn(rows: List<ItemRow>) = synchronized(this) {
        val list = ArrayList(_items.value)
        val have = list.mapTo(HashSet()) { it.id }
        val drop = ArrayList<String>()
        for (r in rows) {
            if (r.id in have) continue
            val item = toItem(r)
            // 这中间又记下了同样的内容：留着已有的，这条不要了
            val dup = list.any { it.phrase == item.phrase && if (item.hidden || it.hidden) it.hash == item.hash && it.hash.isNotEmpty() else it.text == item.text }
            if (dup) { drop += item.id; continue }
            list += item
            have += item.id
        }
        if (drop.isNotEmpty()) persist { it.softDelete(drop); it.purge(drop) }
        commit(list, limit())
    }

    // ---------------- 导入导出 ----------------

    /** 导出到用户选的文件。回调在主线程，成功时是导出的条数 */
    fun export(uri: Uri, opts: Backup.Options, cb: (Result<Int>) -> Unit) = DbThread.post {
        val r = runCatching {
            app!!.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.export(repo(), it, opts) }
        }
        main.post { cb(r) }
    }

    fun exportText(uri: Uri, cb: (Result<Int>) -> Unit) = DbThread.post {
        val r = runCatching { app!!.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.exportText(repo(), it) } }
        main.post { cb(r) }
    }

    /** 看看要导入的文件是什么 */
    fun peek(uri: Uri, cb: (Result<Backup.Info>) -> Unit) = DbThread.post {
        val r = runCatching { app!!.contentResolver.openInputStream(uri)!!.use { Backup.peek(it) } }
        main.post { cb(r) }
    }

    /**
     * 导入。整个文件要么都进来、要么都不进来。导入完重新读一遍列表。
     * @param replace true = 先把现有的记录和常用语移进回收站
     */
    fun import(uri: Uri, password: CharArray?, replace: Boolean, cb: (Result<ImportStats>) -> Unit) = DbThread.post {
        val r = runCatching {
            val repo = repo()
            // 文件里可能有隐藏的内容：先把密钥准备好（单独存好），别放在导入的事务里
            if (!Vault.ready && Vault.state != Vault.STATE_BROKEN) Vault.generate()?.let { (w, raw) -> repo.putMeta(Vault.META_KEY, w); Vault.adopt(w, raw) }
            val stats = app!!.contentResolver.openInputStream(uri)!!.use { Backup.import(repo, it, password, replace) { t -> Vault.seal(t) } }
            stats
        }
        val reload = if (r.isSuccess) runCatching { repo().loadLive().map(::toItem) to repo().loadGroups() }.getOrNull() else null
        main.post {
            if (reload != null) synchronized(this) {
                _groups.value = reload.second
                if (_collect.value?.id?.let { id -> reload.first.none { it.id == id } } == true) _collect.value = null
                commit(reload.first, limit())
            }
            cb(r)
        }
    }

    // ---------------- 常用语 ----------------

    fun addPhrase(text: String, groupId: String? = null): Boolean {
        val t = ItemText.limit(text.trim())
        if (t.isEmpty()) return false
        synchronized(this) {
            if (_items.value.any { it.phrase && !it.hidden && it.text == t }) return false
            val at = now()
            val top = maxOf((_items.value.filter { it.phrase }.maxOfOrNull { it.time } ?: 0L) + 1, at)
            val item = ClipItem(Uuid7.next(at), t, top, phrase = true, groupId = groupId, search = searchOf(t, null), hasLink = hasLink(t))
            persist { it.insert(NewItem(item.id, ItemKind.PHRASE, t, at, groupId = groupId, sortKey = top.toDouble()), item.search) }
            commit(_items.value + item, Int.MAX_VALUE)
        }
        return true
    }

    /** 常用语上移(-1) / 下移(+1)：与相邻项交换排序键 */
    fun movePhrase(id: String, delta: Int) = synchronized(this) {
        val ps = phrases()
        val i = ps.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in ps.indices) return@synchronized
        val a = ps[i]; val b = ps[j]
        persist {
            it.updateAll(listOf(
                a.id to ContentValues().apply { put("sort_key", b.time.toDouble()) },
                b.id to ContentValues().apply { put("sort_key", a.time.toDouble()) },
            ))
        }
        commit(_items.value.map {
            when (it.id) {
                a.id -> it.copy(time = b.time)
                b.id -> it.copy(time = a.time)
                else -> it
            }
        }, Int.MAX_VALUE)
    }

    // ---------------- 内部 ----------------

    /**
     * 发布新列表。放不下的记录被“淘汰”：本机不再显示、进回收站，但不算删除（见 [ItemRepo]）。
     * 数据库还没读完时不淘汰：那时内存里的不是全部。
     * @param maxItems 未收藏记录的条数上限；[Int.MAX_VALUE] 表示这次不按条数淘汰
     */
    private fun commit(list: List<ClipItem>, maxItems: Int) {
        var out = sort(list)
        if (_loaded.value) {
            val drop = overflow(out, maxItems)
            if (drop.isNotEmpty()) {
                val at = now()
                val keep = on(Feature.TRASH)
                persist { it.evict(drop, at); if (!keep) it.purge(drop) }
                if (_collect.value?.id in drop) _collect.value = null
                out = out.filter { it.id !in drop }
            }
        }
        _items.value = out
        listeners.toList().forEach { it() }
        scheduleExpiry()
    }

    /**
     * 该淘汰哪些。sorted 必须已经排好序（未收藏的记录在里面是从新到旧的）。
     * 三条规则都是“从新到旧数，数到放不下为止，更旧的淘汰”：条数、长文的合计字数、全部记录的合计字数。
     * 最新的一条无论多长都留着。
     */
    internal fun overflow(sorted: List<ClipItem>, maxItems: Int): Set<String> {
        val drop = HashSet<String>()
        var count = 0
        var total = 0L
        var big = 0L
        var bigFull = false
        var totalFull = false
        for (it in sorted) {
            if (it.pinned || it.phrase) continue
            val n = it.chars
            val first = count == 0
            if (++count > maxItems) { drop += it.id; continue }
            if (!first && (totalFull || total + n > TOTAL_BUDGET)) { totalFull = true; drop += it.id; continue }
            if (n > LARGE_CHARS) {
                if (!first && (bigFull || big + n > LARGE_BUDGET)) { bigFull = true; drop += it.id; continue }
                big += n
            }
            total += n
        }
        return drop
    }

    private fun sort(l: List<ClipItem>) =
        l.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })

    // 验证码到点自动清除。清除的是敏感内容，所以不进回收站
    private val expireRun = Runnable { expireNow() }

    private fun scheduleExpiry() {
        main.removeCallbacks(expireRun)
        val next = _items.value.mapNotNull { it.expireAt }.minOrNull() ?: return
        main.postDelayed(expireRun, (next - System.currentTimeMillis()).coerceIn(1_000L, 6 * 3_600_000L))
    }

    private fun expireNow() = synchronized(this) {
        val t = System.currentTimeMillis()
        val gone = _items.value.filter { it.expireAt != null && it.expireAt <= t }.mapTo(HashSet()) { it.id }
        if (gone.isEmpty()) { scheduleExpiry(); return@synchronized }
        persist { it.softDelete(gone); it.purge(gone) }
        commit(_items.value.filter { it.id !in gone }, Int.MAX_VALUE)
    }

    /** 每次调用都比上一次大：同一毫秒里连着记下的几条，先后顺序也不会乱 */
    private fun now(): Long = synchronized(clock) {
        val t = maxOf(System.currentTimeMillis(), clock[0] + 1)
        clock[0] = t
        t
    }
    private val clock = LongArray(1)

    /** 单元测试用：回到“进程刚启动”的状态 */
    internal fun resetForTest() = synchronized(this) {
        app = null
        lastPicked = null
        loadResult = null
        pending.clear()
        listeners.clear()
        main.removeCallbacks(expireRun)
        _items.value = emptyList()
        _groups.value = emptyList()
        _rules.value = emptyMap()
        _current.value = null
        _loaded.value = false
        _notice.value = null
        _collect.value = null
    }
}

/** 粘贴前的文字处理 */
object TextTools {
    private val zeroWidth = Regex("[​‌‍⁠﻿]")
    private val trailingSpaces = Regex("[ \t　]+(?=\n)")
    private val manyBlankLines = Regex("\n{3,}")

    /** 清理格式：去零宽字符、不间断空格→普通空格、去行尾空白、连续空行压成一行、去首尾空白 */
    fun clean(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
        .replace(zeroWidth, "").replace(' ', ' ')
        .replace(trailingSpaces, "").replace(manyBlankLines, "\n\n").trim()

    /** 合并多条：0 换行 1 空一行 2 空格 3 直接连接 */
    fun separator(mode: Int) = when (mode) { 1 -> "\n\n"; 2 -> " "; 3 -> ""; else -> "\n" }

    fun join(texts: List<String>, mode: Int): String = texts.joinToString(separator(mode))

    fun prepare(prefs: Prefs, text: String) = if (prefs.cleanText) clean(text) else text
}
