package com.mistpeaklab.clipdock

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * @param phrase 常用语：手动维护，可编辑、可排序，不参与条数上限和“清空”
 * @param time   历史记录为复制时间；常用语用它作排序键（越大越靠前）
 */
data class ClipItem(
    val id: Long,
    val text: String,
    val time: Long,
    val pinned: Boolean = false,
    val phrase: Boolean = false,
)

/**
 * 剪贴板历史 + 常用语：只存文字，保存在应用私有目录的 JSON 文件里，不联网。
 */
object ClipStore {
    private const val FILE = "history.json"
    private var file: File? = null
    private var lastId = 0L
    private var lastPicked: String? = null

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    /** 全部条目（历史 + 常用语） */
    val items: StateFlow<List<ClipItem>> = _items

    /** 当前系统剪贴板里的文字（就我们所知） */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized
    fun init(context: Context) {
 if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
 file = f
        if (!f.exists()) return
        runCatching {
     val arr = JSONArray(f.readText())
     val list = (0 until arr.length()).map {
  val o = arr.getJSONObject(it)
         ClipItem(o.getLong("id"), o.getString("text"), o.getLong("time"), o.optBoolean("pinned"), o.optBoolean("phrase"))
     }
     lastId = list.maxOfOrNull { it.id } ?: 0L
     _items.value = sort(list)
        }
    }

    /** 历史记录：置顶在前，其余按时间从新到旧 */
    fun history(): List<ClipItem> = _items.value.filter { !it.phrase }

    /** 常用语：按用户排序 */
    fun phrases(): List<ClipItem> = _items.value.filter { it.phrase }

    /** 记录新复制的内容。已存在则移到最前；本应用刚写回剪贴板的内容不重复记录。 */
    fun add(text: String, maxItems: Int): Boolean {
        if (text.isBlank()) return false
        synchronized(this) {
     _current.value = text
     if (text == lastPicked) return false
     lastPicked = null
     val list = _items.value
     val existing = list.firstOrNull { !it.phrase && it.text == text }
     if (existing != null) {
         val newest = list.filter { !it.phrase && !it.pinned }.maxByOrNull { it.time }
  if (!existing.pinned && newest?.id == existing.id) return false
         commit(list.map { if (it.id == existing.id) it.copy(time = now()) else it }, maxItems)
            } else {
         commit(list + ClipItem(nextId(), text, now()), maxItems)
     }
 }
        return true
    }

    /** 本应用把内容写回剪贴板时调用 */
    fun markPicked(text: String) {
 lastPicked = text
        _current.value = text
    }

    fun togglePin(id: Long) = edit { l -> l.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }
    fun delete(id: Long) = edit { l -> l.filter { it.id != id } }

    /** 清空历史：保留置顶和常用语 */
    fun clearUnpinned() = edit { l -> l.filter { it.pinned || it.phrase } }

    /** 清空全部历史（含置顶），常用语保留 */
    fun clearAll() = edit { l -> l.filter { it.phrase } }

    fun applyLimit(maxItems: Int) = synchronized(this) { commit(_items.value, maxItems) }

    // ---------------- 常用语 ----------------

    fun addPhrase(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
 synchronized(this) {
     if (_items.value.any { it.phrase && it.text == t }) return false
     val top = (_items.value.filter { it.phrase }.maxOfOrNull { it.time } ?: 0L) + 1
     commit(_items.value + ClipItem(nextId(), t, maxOf(top, now()), phrase = true), Int.MAX_VALUE)
 }
        return true
    }

    fun updateText(id: Long, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        edit { l -> l.map { if (it.id == id) it.copy(text = t) else it } }
    }

    /** 常用语上移(-1) / 下移(+1)：与相邻项交换排序键 */
    fun movePhrase(id: Long, delta: Int) = synchronized(this) {
        val ps = phrases()
        val i = ps.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in ps.indices) return@synchronized
 val a = ps[i]; val b = ps[j]
        commit(_items.value.map {
     when (it.id) {
         a.id -> it.copy(time = b.time)
         b.id -> it.copy(time = a.time)
         else -> it
            }
        }, Int.MAX_VALUE)
    }

    // ---------------- 内部 ----------------

    private fun edit(block: (List<ClipItem>) -> List<ClipItem>) = synchronized(this) {
        commit(block(_items.value), Int.MAX_VALUE)
    }

    private fun commit(list: List<ClipItem>, maxItems: Int) {
        val unpinned = list.filter { !it.pinned && !it.phrase }.sortedByDescending { it.time }
        val drop = if (unpinned.size > maxItems) unpinned.drop(maxItems).map { it.id }.toSet() else emptySet()
        val sorted = sort(list.filter { it.id !in drop })
        _items.value = sorted
 save(sorted)
        listeners.toList().forEach { it() }
    }

    private fun sort(l: List<ClipItem>) =
 l.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })

    private fun now() = System.currentTimeMillis()
    private fun nextId(): Long { lastId = maxOf(now(), lastId + 1); return lastId }

    private fun save(list: List<ClipItem>) {
        val f = file ?: return
 val arr = JSONArray()
        list.forEach {
     arr.put(
  JSONObject().put("id", it.id).put("text", it.text).put("time", it.time)
      .put("pinned", it.pinned).put("phrase", it.phrase),
     )
        }
        runCatching {
     val tmp = File(f.parentFile, "$FILE.tmp")
     tmp.writeText(arr.toString())
     tmp.renameTo(f)
 }
    }
}

/** 粘贴前的文字处理 */
object TextTools {
    private val zeroWidth = Regex("[\u200B\u200C\u200D\u2060\uFEFF]")
    private val trailingSpaces = Regex("[ \t\u3000]+(?=\n)")
    private val manyBlankLines = Regex("\n{3,}")

    /** 清理格式：去零宽字符、不间断空格→普通空格、去行尾空白、连续空行压成一行、去首尾空白 */
    fun clean(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
        .replace(zeroWidth, "").replace('\u00A0', ' ')
        .replace(trailingSpaces, "").replace(manyBlankLines, "\n\n").trim()

    /** 合并多条：0 换行 1 空一行 2 空格 3 直接连接 */
    fun join(texts: List<String>, mode: Int): String = texts.joinToString(
 when (mode) { 1 -> "\n\n"; 2 -> " "; 3 -> ""; else -> "\n" },
    )

    fun prepare(prefs: Prefs, text: String) = if (prefs.cleanText) clean(text) else text
}
