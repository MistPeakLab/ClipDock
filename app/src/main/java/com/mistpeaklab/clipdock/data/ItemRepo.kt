package com.mistpeaklab.clipdock.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mistpeaklab.clipdock.util.Search

/** 回收站里的一条：只带预览，不带全文 */
data class TrashRow(
    val id: String,
    val kind: Int,
    val preview: String,
    val chars: Int,
    /** 进回收站的毫秒时间 */
    val whenMs: Long,
    /** true = 超出条数上限被本机淘汰；false = 用户删除 */
    val evicted: Boolean,
    /** 隐藏的内容（没有预览） */
    val hidden: Boolean,
)

/** [ItemRepo.restore] 的结果 */
sealed interface RestoreResult {
    /** 已恢复，[row] 是恢复后的那一行 */
    data class Ok(val row: ItemRow) : RestoreResult

    /** 列表里已经有内容相同的一条，回收站这条已作废 */
    data object Duplicate : RestoreResult

    /** 已经不在回收站里（被清空了，或者早就恢复了） */
    data object Gone : RestoreResult
}

/**
 * items / groups / app_rules 表的读写。**所有方法都只能在 [DbThread] 上调用。**
 *
 * 一行有三种“不在列表里”的状态，别混：
 * - 删除（deleted_at）：用户的决定，以后会同步给其他设备；
 * - 淘汰（evicted_at）：超出条数上限，只是本机放不下，**不算删除、不同步**；
 * - 正文已清空（wiped）：删除满 [TRASH_DAYS] 天或被“彻底删除”，只留一行空壳（同步时用来告诉别的设备“这条删了”）。
 *
 * 改了内容（正文、置顶、备注、排序、删除、恢复…）要更新 updated_at（HLC）和 seq，由 [stamp] 统一做；
 * 只是“用了一下”（used_at）、淘汰、清空正文都不算内容变化。
 * 理由见 docs/dev/v0.6.0.md 3.3、v0.9.0-sync.md 4.5。
 */
class ItemRepo private constructor(private val helper: Db) {

    private val db: SQLiteDatabase get() = helper.writableDatabase
    private lateinit var hlc: Hlc
    private var seq = 0L
    private lateinit var deviceId: String

    private fun open() {
        hlc = Hlc(getMeta(Db.META_HLC_LAST)?.toLongOrNull() ?: 0L)
        seq = getMeta(Db.META_SEQ_LAST)?.toLongOrNull() ?: 0L
        deviceId = getMeta(Db.META_DEVICE_ID)
            ?: java.util.UUID.randomUUID().toString().also { putMeta(Db.META_DEVICE_ID, it) }
    }

    val device: String get() = deviceId

    // ---------------- meta ----------------

    fun getMeta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putMeta(key: String, value: String) {
        val cv = ContentValues(2)
        cv.put("key", key)
        cv.put("value", value)
        db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeMeta(key: String) {
        db.delete("meta", "key=?", arrayOf(key))
    }

    /** 一个事务：要么全做完，要么都没发生。顺带把时钟和序号存下来。可以嵌套 */
    private inline fun <T> tx(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            putMeta(Db.META_HLC_LAST, hlc.last.toString())
            putMeta(Db.META_SEQ_LAST, seq.toString())
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    /** 把一串操作包成一个事务（导入时用：整个文件要么都进来，要么都不进来） */
    fun <T> transaction(block: () -> T): T = tx(block)

    /** 标记“内容变了” */
    private fun stamp(cv: ContentValues): ContentValues {
        cv.put("updated_at", hlc.next())
        cv.put("seq", ++seq)
        cv.put("device_id", deviceId)
        return cv
    }

    // ---------------- 读 ----------------

    /** 还在列表里的全部记录和常用语（文库篇章另外读，见 [loadNotes]：篇章很长，不整篇放进内存） */
    fun loadLive(): List<ItemRow> =
        db.rawQuery("SELECT $COLS FROM items WHERE $LIVE AND kind<>${ItemKind.LIBRARY}", null).use { c ->
            val out = ArrayList<ItemRow>(c.count)
            while (c.moveToNext()) out += read(c)
            out
        }

    fun get(id: String): ItemRow? =
        db.rawQuery("SELECT $COLS FROM items WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }

    private fun read(c: Cursor): ItemRow {
        val id = c.getString(0)
        return ItemRow(
            id = id,
            kind = c.getInt(1),
            text = if (c.isNull(2)) longText(id, c.getInt(19)) else c.getString(2),
            search = c.getString(3),
            chars = c.getInt(4),
            sourcePkg = c.getString(5),
            groupId = c.getString(6),
            pinned = c.getInt(7) != 0,
            hidden = c.getInt(8) != 0,
            expireAt = if (c.isNull(9)) null else c.getLong(9),
            sortKey = if (c.isNull(10)) null else c.getDouble(10),
            createdAt = c.getLong(11),
            updatedAt = c.getLong(12),
            usedAt = c.getLong(13),
            useCount = c.getInt(14),
            evictedAt = if (c.isNull(15)) null else c.getLong(15),
            deletedAt = if (c.isNull(16)) null else c.getLong(16),
            note = c.getString(17),
            wiped = c.getInt(18) != 0,
            cipher = if (c.isNull(20)) null else c.getBlob(20),
            deviceId = c.getString(21),
            hash = c.getString(22),
            title = c.getString(23),
            sourceTitle = c.getString(24),
            sourceAuthor = c.getString(25),
            sourceUrl = c.getString(26),
        )
    }

    /** 文库里的全部篇章：只读标题、出处、预览这些，不读正文 */
    fun loadNotes(): List<NoteRow> = db.rawQuery(
        "SELECT id, title, group_id, source_title, source_author, source_url, chars, preview, created_at, updated_at, search " +
            "FROM items WHERE kind=${ItemKind.LIBRARY} AND $LIVE",
        null,
    ).use { c ->
        val out = ArrayList<NoteRow>(c.count)
        while (c.moveToNext()) out += NoteRow(
            c.getString(0), c.getString(1) ?: "", c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            c.getInt(6), c.getString(7), Hlc.millis(c.getLong(8)), Hlc.millis(c.getLong(9)), c.getString(10),
        )
        out
    }

    /**
     * 很长的正文分段读。系统一次查询的结果窗口只有 2MB 左右，一行超过就会抛异常，
     * 所以 [COLS] 里超长的正文先返回 NULL，再在这里一段一段取回来。
     * @param len SQLite 的 length(text)，按码点计；和 Kotlin 的 length（按 UTF-16 单元计）不是一回事
     */
    private fun longText(id: String, len: Int): String {
        val sb = StringBuilder(len)
        var pos = 1
        while (pos <= len) {
            db.rawQuery("SELECT substr(text, $pos, $CHUNK) FROM items WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) sb.append(it.getString(0))
            }
            pos += CHUNK
        }
        return sb.toString()
    }

    // ---------------- 写 ----------------

    fun insert(n: NewItem, search: String) = tx { put(n, search) }

    /**
     * 一次写入一批，已经有的（同一个 ID）不动。数据库重建后用它把内存里的列表重新存一遍。
     * @param markMigrated 同一个事务里打上“旧版本的记录已导入”标记
     */
    fun insertAll(items: List<Pair<NewItem, String>>, markMigrated: Boolean = false) = tx {
        for ((n, s) in items) put(n, s)
        if (markMigrated) putMeta(Db.META_MIGRATED_FROM_JSON, System.currentTimeMillis().toString())
    }

    private fun put(n: NewItem, search: String) {
        val now = hlc.next()
        val cv = ContentValues()
        cv.put("id", n.id)
        cv.put("kind", n.kind)
        cv.put("hash", ItemText.sha256(n.text))
        cv.put("chars", n.text.length)
        body(cv, n.text, search, n.cipher, n.kind)
        cv.put("source_pkg", n.sourcePkg)
        cv.put("group_id", n.groupId)
        cv.put("pinned", if (n.pinned) 1 else 0)
        n.expireAt?.let { cv.put("expire_at", it) }
        n.sortKey?.let { cv.put("sort_key", it) }
        cv.put("note", n.note)
        cv.put("title", n.title)
        cv.put("source_title", n.sourceTitle)
        cv.put("source_author", n.sourceAuthor)
        cv.put("source_url", n.sourceUrl)
        cv.put("created_at", now)
        cv.put("updated_at", now)
        cv.put("used_at", n.nowMs)
        cv.put("device_id", deviceId)
        cv.put("seq", ++seq)
        // 同一个 ID 不会插两次；万一发生，保留已有的那行
        db.insertWithOnConflict("items", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 正文相关的几列。隐藏的内容（cipher 非空）库里只存密文，不存原文和预览；
     * 这时 search 由调用方给，里面只应该有备注（备注是用户写给自己看的标签，不加密）
     */
    private fun body(cv: ContentValues, text: String, search: String, cipher: ByteArray?, kind: Int = ItemKind.CLIP) {
        if (cipher != null) {
            cv.put("hidden", 1)
            cv.put("cipher", cipher)
            cv.put("text", "")
            cv.put("search", search)
            cv.put("preview", "")
        } else {
            cv.put("hidden", 0)
            cv.putNull("cipher")
            cv.put("text", text)
            cv.put("search", search)
            cv.put("preview", ItemText.preview(text, kind))
        }
    }

    /** 又复制 / 又用了一次：只动本机字段 */
    fun touch(id: String, usedAt: Long, sourcePkg: String? = null) {
        if (sourcePkg == null) {
            db.execSQL("UPDATE items SET used_at=?, use_count=use_count+1 WHERE id=?", arrayOf<Any>(usedAt, id))
        } else {
            db.execSQL(
                "UPDATE items SET used_at=?, use_count=use_count+1, source_pkg=? WHERE id=?",
                arrayOf<Any>(usedAt, sourcePkg, id),
            )
        }
    }

    /** 改内容类的字段（置顶、备注、分组、排序键…） */
    fun update(id: String, values: ContentValues) = tx { db.update("items", stamp(values), "id=?", arrayOf(id)) }

    /** 同时改几行，要么都改要么都不改（比如交换两条常用语的顺序） */
    fun updateAll(changes: List<Pair<String, ContentValues>>) = tx {
        for ((id, values) in changes) db.update("items", stamp(values), "id=?", arrayOf(id))
    }

    /** 改正文。cipher 非空表示这条是隐藏的 */
    fun setText(id: String, text: String, search: String, cipher: ByteArray? = null, kind: Int = ItemKind.CLIP) {
        val cv = ContentValues()
        cv.put("hash", ItemText.sha256(text))
        cv.put("chars", text.length)
        body(cv, text, search, cipher, kind)
        update(id, cv)
    }

    /** 隐藏（cipher 非空）或取消隐藏（cipher 为空，text 是原文） */
    fun setHidden(id: String, cipher: ByteArray?, text: String, search: String) {
        val cv = ContentValues()
        body(cv, text, search, cipher)
        update(id, cv)
    }

    /** 用户删除：进回收站，以后会随同步传给其他设备 */
    fun softDelete(ids: Collection<String>) = tx {
        for (id in ids) {
            val cv = ContentValues()
            cv.put("deleted_at", hlc.next())
            db.update("items", stamp(cv), "id=? AND deleted_at IS NULL", arrayOf(id))
        }
    }

    /** 超出条数上限：只是本机不再显示，不是删除 */
    fun evict(ids: Collection<String>, nowMs: Long) = tx {
        val cv = ContentValues()
        cv.put("evicted_at", nowMs)
        for (id in ids) db.update("items", cv, "id=? AND evicted_at IS NULL", arrayOf(id))
    }

    private fun liveTwin(hash: String, kind: Int, notId: String): Pair<String, Boolean>? = db.rawQuery(
        "SELECT id, pinned FROM items WHERE hash=? AND kind=? AND id<>? AND $LIVE LIMIT 1",
        arrayOf(hash, kind.toString(), notId),
    ).use { if (it.moveToFirst()) it.getString(0) to (it.getInt(1) != 0) else null }

    /** 从回收站恢复 */
    fun restore(id: String, nowMs: Long): RestoreResult = tx {
        val row = get(id) ?: return@tx RestoreResult.Gone
        if (row.wiped || row.live) return@tx RestoreResult.Gone
        // 列表里已经有同样的内容（删除或淘汰之后又复制过）：留着列表里的那条，这条作废
        if (liveTwin(row.hash, row.kind, id) != null) {
            discard(row)
            return@tx RestoreResult.Duplicate
        }
        val cv = ContentValues()
        cv.putNull("evicted_at")
        cv.putNull("expire_at")
        cv.put("used_at", nowMs)   // 排到最前面，免得刚恢复就又因为超出上限被淘汰
        if (row.deletedAt != null) {
            cv.putNull("deleted_at")
            stamp(cv)
        }
        db.update("items", cv, "id=?", arrayOf(id))
        get(id)?.let { RestoreResult.Ok(it) } ?: RestoreResult.Gone
    }

    /** 从回收站里彻底清掉（用户点了“彻底删除”或“清空回收站”）。还在列表里的不动 */
    fun purge(ids: Collection<String>) = tx {
        for (id in ids) {
            val row = get(id) ?: continue
            if (!row.live) discard(row)
        }
    }

    /**
     * 让回收站里的一行消失。
     * - 用户删除过的：清空正文，留一行空壳，以后同步时别的设备才知道它被删了；
     * - 只是被淘汰的：整行去掉。**不能**把它标成删除——淘汰只是本机放不下，
     *   标成删除的话，同步后会把别的设备上好好的记录也删掉。
     */
    private fun discard(row: ItemRow) {
        if (row.deletedAt == null) db.delete("items", "id=?", arrayOf(row.id))
        else wipe("id=?", arrayOf(row.id))
    }

    /** 回收站到期清理：超过 [TRASH_DAYS] 天的不再保留正文；空壳再留 [TOMBSTONE_DAYS] 天后整行去掉 */
    fun cleanExpired(nowMs: Long) = tx {
        val trashLimit = nowMs - TRASH_DAYS * DAY
        // deleted_at 是 HLC，换成同一种单位再比
        wipe("deleted_at IS NOT NULL AND deleted_at < ?", arrayOf(Hlc.pack(trashLimit).toString()))
        db.delete("items", "deleted_at IS NULL AND evicted_at IS NOT NULL AND evicted_at < ?", arrayOf(trashLimit.toString()))
        db.delete(
            "items", "wiped=1 AND deleted_at IS NOT NULL AND deleted_at < ?",
            arrayOf(Hlc.pack(nowMs - (TRASH_DAYS + TOMBSTONE_DAYS) * DAY).toString()),
        )
    }

    private fun wipe(where: String, args: Array<String>) {
        val cv = ContentValues()
        cv.put("text", "")
        cv.put("preview", "")
        cv.put("search", "")
        cv.putNull("cipher")
        cv.putNull("note")
        cv.put("wiped", 1)
        db.update("items", cv, "wiped=0 AND ($where)", args)
    }

    fun trash(): List<TrashRow> = db.rawQuery(
        "SELECT id, kind, preview, chars, deleted_at, evicted_at, hidden FROM items " +
            "WHERE wiped=0 AND (deleted_at IS NOT NULL OR evicted_at IS NOT NULL)",
        null,
    ).use { c ->
        val out = ArrayList<TrashRow>(c.count)
        while (c.moveToNext()) {
            val deleted = !c.isNull(4)
            out += TrashRow(
                c.getString(0), c.getInt(1), c.getString(2), c.getInt(3),
                if (deleted) Hlc.millis(c.getLong(4)) else c.getLong(5), !deleted, c.getInt(6) != 0,
            )
        }
        out.sortedByDescending { it.whenMs }
    }

    // ---------------- 分组 ----------------

    fun loadGroups(): List<GroupRow> = db.rawQuery(
        "SELECT id, name, sort_key, scope, created_at, updated_at, deleted_at, color FROM groups WHERE deleted_at IS NULL ORDER BY sort_key", null,
    ).use { c ->
        val out = ArrayList<GroupRow>(c.count)
        while (c.moveToNext()) out += readGroup(c)
        out
    }

    private fun readGroup(c: Cursor) = GroupRow(
        c.getString(0), c.getString(1), c.getDouble(2), c.getInt(3), c.getLong(4), c.getLong(5),
        if (c.isNull(6)) null else c.getLong(6), if (c.isNull(7)) null else c.getInt(7),
    )

    fun insertGroup(id: String, name: String, sortKey: Double, scope: Int = 0) = tx {
        val now = hlc.next()
        val cv = ContentValues()
        cv.put("id", id)
        cv.put("name", name)
        cv.put("sort_key", sortKey)
        cv.put("scope", scope)
        cv.put("created_at", now)
        cv.put("updated_at", now)
        cv.put("device_id", deviceId)
        cv.put("seq", ++seq)
        db.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateGroup(id: String, values: ContentValues) = tx { db.update("groups", stamp(values), "id=?", arrayOf(id)) }

    /** 删除分组：里面的条目不删，只是回到“未分组” */
    fun deleteGroup(id: String) = tx {
        db.update("groups", stamp(ContentValues().apply { put("deleted_at", hlc.next()) }), "id=? AND deleted_at IS NULL", arrayOf(id))
        db.rawQuery("SELECT id FROM items WHERE group_id=?", arrayOf(id)).use { c ->
            val ids = ArrayList<String>(c.count)
            while (c.moveToNext()) ids += c.getString(0)
            ids
        }.forEach { db.update("items", stamp(ContentValues().apply { putNull("group_id") }), "id=?", arrayOf(it)) }
    }

    /** 条目指向的分组已经不存在了（导入的文件不完整时会这样）：让它们回到“未分组” */
    fun dropOrphanGroupRefs() {
        db.execSQL(
            "UPDATE items SET group_id=NULL WHERE group_id IS NOT NULL AND group_id NOT IN (SELECT id FROM groups WHERE deleted_at IS NULL)",
        )
    }

    // ---------------- 应用规则（本机的，不同步） ----------------

    fun loadRules(): Map<String, Int> = db.rawQuery("SELECT pkg, rule FROM app_rules", null).use { c ->
        val out = HashMap<String, Int>()
        while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        out
    }

    /** rule 为空 = 去掉这个应用的规则 */
    fun setRule(pkg: String, rule: Int?) {
        if (rule == null) db.delete("app_rules", "pkg=?", arrayOf(pkg))
        else db.insertWithOnConflict(
            "app_rules", null, ContentValues().apply { put("pkg", pkg); put("rule", rule) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---------------- 导出 / 导入 ----------------

    /** 要导出的行的 ID，按创建时间排。正文已清空的空壳不导出 */
    fun exportIds(includeTrash: Boolean): List<String> = db.rawQuery(
        "SELECT id FROM items WHERE wiped=0" + (if (includeTrash) "" else " AND deleted_at IS NULL AND evicted_at IS NULL") +
            " ORDER BY created_at, id",
        null,
    ).use { c ->
        val out = ArrayList<String>(c.count)
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    fun exportGroups(): List<GroupRow> = db.rawQuery(
        "SELECT id, name, sort_key, scope, created_at, updated_at, deleted_at, color FROM groups ORDER BY sort_key", null,
    ).use { c ->
        val out = ArrayList<GroupRow>(c.count)
        while (c.moveToNext()) out += readGroup(c)
        out
    }

    /** 导入分组：没有的加进来，删掉的恢复，名字以新的为准 */
    fun importGroup(g: GroupRow): Boolean = tx {
        if (g.deletedAt != null) return@tx false
        val local = db.rawQuery("SELECT updated_at, deleted_at FROM groups WHERE id=?", arrayOf(g.id)).use {
            if (it.moveToFirst()) it.getLong(0) to !it.isNull(1) else null
        }
        hlc.observe(g.updatedAt)
        if (local == null) {
            val cv = ContentValues()
            cv.put("id", g.id)
            cv.put("name", g.name)
            cv.put("sort_key", g.sortKey)
            cv.put("scope", g.scope)
            g.color?.let { cv.put("color", it) }
            cv.put("created_at", g.createdAt)
            cv.put("updated_at", g.updatedAt)
            cv.put("device_id", deviceId)
            cv.put("seq", ++seq)
            db.insert("groups", null, cv)
            return@tx true
        }
        val (updatedAt, deleted) = local
        if (deleted) {
            db.update("groups", stamp(ContentValues().apply { putNull("deleted_at"); put("name", g.name) }), "id=?", arrayOf(g.id))
            return@tx true
        }
        if (g.updatedAt > updatedAt) {
            val cv = ContentValues()
            cv.put("name", g.name)
            cv.put("updated_at", g.updatedAt)
            cv.put("seq", ++seq)
            db.update("groups", cv, "id=?", arrayOf(g.id))
        }
        false
    }

    /**
     * 导入一条。规则是“把文件里的内容加回来，已有的不重复”：
     * - 文件里它在回收站：本机没有这个 ID 才加（也放进回收站），否则不动；
     * - 本机有同一个 ID 且在列表里：文件里的版本更新才用它的内容；
     * - 本机有同一个 ID 但已删除 / 被淘汰：恢复，并用文件里的内容；
     * - 本机没有这个 ID：列表里已有同样内容的就跳过，否则加进来。
     * @param seal 把要隐藏的内容加密；返回 null 表示这台设备加不了密，那就按不隐藏存
     * @return 0 跳过，1 新增，2 更新 / 恢复
     */
    fun importItem(r: PortItem, seal: (String) -> ByteArray?): Int = tx {
        val local = get(r.id)
        val inTrash = r.deletedAt != null || r.evicted
        if (inTrash) {
            if (local != null) return@tx 0
            insertPort(r, seal)
            return@tx 1
        }
        if (local != null && local.live) {
            if (r.updatedAt <= local.updatedAt) return@tx 0
            val cv = portContent(r, seal)
            cv.put("updated_at", r.updatedAt)
            cv.put("seq", ++seq)
            hlc.observe(r.updatedAt)
            db.update("items", cv, "id=?", arrayOf(r.id))
            return@tx 2
        }
        val twin = liveTwin(ItemText.sha256(r.text), r.kind, r.id)
        if (twin != null) {
            if (r.pinned && !twin.second) db.update("items", stamp(ContentValues().apply { put("pinned", 1) }), "id=?", arrayOf(twin.first))
            return@tx 0
        }
        if (local != null) {
            val cv = portContent(r, seal)
            cv.putNull("deleted_at")
            cv.putNull("evicted_at")
            cv.put("wiped", 0)
            cv.put("used_at", r.usedAt ?: Hlc.millis(r.updatedAt))
            db.update("items", stamp(cv), "id=?", arrayOf(r.id))
            return@tx 2
        }
        insertPort(r, seal)
        1
    }

    private fun portContent(r: PortItem, seal: (String) -> ByteArray?): ContentValues {
        val cv = ContentValues()
        val cipher = if (r.hidden && r.text.length <= HIDE_MAX_CHARS) seal(r.text) else null
        cv.put("hash", ItemText.sha256(r.text))
        cv.put("chars", r.text.length)
        body(cv, r.text, Search.build(if (cipher != null) "" else Search.head(searchSource(r.kind, r.text, r.title, r.sourceTitle, r.sourceAuthor), SEARCH_CHARS), r.note), cipher, r.kind)
        cv.put("title", r.title)
        cv.put("source_title", r.sourceTitle)
        cv.put("source_author", r.sourceAuthor)
        cv.put("source_url", r.sourceUrl)
        cv.put("source_pkg", r.sourcePkg)
        cv.put("group_id", r.groupId)
        cv.put("pinned", if (r.pinned) 1 else 0)
        cv.put("note", r.note)
        if (r.sortKey != null) cv.put("sort_key", r.sortKey) else cv.putNull("sort_key")
        if (r.expireAt != null) cv.put("expire_at", r.expireAt) else cv.putNull("expire_at")
        return cv
    }

    private fun insertPort(r: PortItem, seal: (String) -> ByteArray?) {
        val cv = portContent(r, seal)
        cv.put("id", r.id)
        cv.put("kind", r.kind)
        cv.put("created_at", r.createdAt)
        cv.put("updated_at", r.updatedAt)
        cv.put("used_at", r.usedAt ?: Hlc.millis(r.updatedAt))
        r.deletedAt?.let { cv.put("deleted_at", it) }
        if (r.evicted && r.deletedAt == null) cv.put("evicted_at", System.currentTimeMillis())
        cv.put("device_id", r.deviceId ?: deviceId)
        cv.put("seq", ++seq)
        hlc.observe(r.updatedAt)
        db.insertWithOnConflict("items", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---------------- 旧数据迁移 ----------------

    /**
     * 写入旧版记录，全部在一个事务里：要么都成功，要么都没发生。返回新增那些行的 ID。
     * 列表里已经有内容相同的就不再加（只把“置顶”带过来），所以重复执行不会产生重复记录。
     * @param markMigrated 同一个事务里打上“已迁移”标记
     */
    fun importLegacy(items: List<RawItem>, markMigrated: Boolean): List<String> = tx {
        val added = ArrayList<String>()
        for (r in items) {
            val hash = ItemText.sha256(r.text)
            val twin = liveTwin(hash, r.kind, r.id)
            if (twin != null) {
                if (r.pinned && !twin.second) {
                    db.update("items", stamp(ContentValues().apply { put("pinned", 1) }), "id=?", arrayOf(twin.first))
                }
                continue
            }
            val cv = ContentValues()
            cv.put("id", r.id)
            cv.put("kind", r.kind)
            cv.put("text", r.text)
            cv.put("hash", hash)
            cv.put("search", Search.build(Search.head(r.text, SEARCH_CHARS)))
            cv.put("preview", ItemText.preview(r.text))
            cv.put("chars", r.text.length)
            cv.put("pinned", if (r.pinned) 1 else 0)
            r.sortKey?.let { cv.put("sort_key", it) }
            cv.put("created_at", r.createdAt)
            cv.put("updated_at", r.updatedAt)
            cv.put("used_at", r.usedAt)
            cv.put("device_id", deviceId)
            cv.put("seq", ++seq)
            hlc.observe(r.updatedAt)
            if (db.insertWithOnConflict("items", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added += r.id
        }
        if (markMigrated) putMeta(Db.META_MIGRATED_FROM_JSON, System.currentTimeMillis().toString())
        added
    }

    companion object {
        /** 回收站保留天数 */
        const val TRASH_DAYS = 30

        /** 正文清空以后，空壳再保留的天数 */
        const val TOMBSTONE_DAYS = 180
        private const val DAY = 86_400_000L

        /** 只有正文的前这么多字进搜索串，免得搜索串和正文一样大 */
        const val SEARCH_CHARS = 20_000

        /**
         * 超过这么多字的内容不能隐藏。密文是整块存在一个字段里的，
         * 系统一次查询能读回的一行不能超过 2MB 左右，太长的密文会读不回来。
         */
        const val HIDE_MAX_CHARS = 100_000

        /** 正文超过这么多字就分段读，每段也是这么多 */
        private const val CHUNK = 100_000

        /** 要放进搜索串的文字：文库篇章把标题、出处放在正文前面，搜标题、书名、作者都能找到 */
        fun searchSource(kind: Int, text: String, title: String?, sourceTitle: String?, sourceAuthor: String?): String =
            if (kind != ItemKind.LIBRARY) text
            else listOfNotNull(title, sourceTitle, sourceAuthor).joinToString(" ") + "\n" + text

        /** “还在列表里”的条件 */
        private const val LIVE = "deleted_at IS NULL AND evicted_at IS NULL AND wiped=0"

        // 顺序和 read() 里的下标一一对应。
        // 用 chars（UTF-16 单元数，不小于码点数）判断长短，这样短的行不必为了数字数去扫一遍正文
        private const val COLS =
            "id, kind, CASE WHEN chars > $CHUNK AND hidden=0 THEN NULL ELSE text END, search, chars, source_pkg, group_id, " +
                "pinned, hidden, expire_at, sort_key, created_at, updated_at, used_at, use_count, evicted_at, deleted_at, " +
                "note, wiped, CASE WHEN chars > $CHUNK AND hidden=0 THEN length(text) ELSE 0 END, cipher, device_id, hash, " +
                "title, source_title, source_author, source_url"

        @Volatile private var instance: ItemRepo? = null

        /** 只能在数据库线程上调用 */
        fun get(ctx: Context): ItemRepo {
            DbThread.check()
            return instance ?: ItemRepo(Db.get(ctx)).also { it.open(); instance = it }
        }

        /** 数据库换了文件（损坏后重建）时调用：下次 [get] 会从新文件里重新读时钟、序号和设备号 */
        internal fun reset() {
            instance = null
        }
    }
}
