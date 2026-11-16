package com.mistpeaklab.clipdock.util

import android.icu.text.Transliterator

/**
 * 汉字转拼音（全拼 + 首字母），给搜索用。
 *
 * 用系统自带的 ICU（`Han-Latin; Latin-ASCII`），不引入第三方词库。
 * 多音字不保证准确（“重庆”会得到 zhongqing），搜索同时匹配原文，影响可接受。
 */
object Pinyin {

    /**
     * 只给前这么多个字符算拼音，后面的部分只做原文匹配。
     * 一个汉字的全拼加首字母要占四五个字符，每条记录的搜索串又都放在内存里；
     * 几千条记录时不设这个上限，拼音会比正文还占地方。用拼音找的多半是开头几个字，500 字足够。
     */
    const val MAX_CHARS = 500

    /** 单字缓存的上限。常用汉字不过几千个，设上限只是防止极端文本把内存撑大。 */
    private const val CACHE_LIMIT = 8192

    private val lock = Any()

    // 懒创建：第一次真正搜索/写入时才加载 ICU 规则（首次创建较慢）。
    // 创建失败（个别 ROM 裁剪了 ICU 数据，或在 JVM 单元测试里）就是 null，之后一律不出拼音。
    private val tr: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    }

    // 码点 -> 该字的拼音（小写、无声调；转不出来的存空串）。只在 lock 内读写。
    private val cache = HashMap<Int, String>()

    /**
     * 返回 (全拼, 首字母)。
     *
     * - 逐个汉字转换，这样才知道音节边界，能取到每个字的首字母。
     * - 非汉字原样保留在两个结果里，于是“wx群”可以用 `wxqun` / `wxq` 搜到。
     * - 文本里没有汉字，或者拼音不可用时，返回两个空串——纯英文内容没必要在搜索串里重复存三遍。
     * - 只处理前 [MAX_CHARS] 个字符。
     * - 不做大小写转换，调用方（[Search]）传进来的已经是小写。
     *
     * 可以在任意线程调用。
     */
    fun of(text: String): Pair<String, String> {
        if (text.isEmpty()) return EMPTY
        val src = Search.head(text, MAX_CHARS)
        if (!hasHan(src)) return EMPTY

        val full = StringBuilder(src.length * 3)
        val initials = StringBuilder(src.length)
        // ICU 的 Transliterator 没有明确承诺线程安全，缓存也是普通 HashMap，统一加锁。
        // 整段在锁内完成：单次最多 2000 字，且有单字缓存，持锁时间很短。
        val ok = synchronized(lock) {
            val t = tr ?: return@synchronized false
            var i = 0
            while (i < src.length) {
                val cp = src.codePointAt(i)
                i += Character.charCount(cp)
                val py = if (isHan(cp)) syllable(t, cp) else ""
                if (py.isEmpty()) {
                    // 非汉字，或这个字转不出拼音：原样保留
                    full.appendCodePoint(cp)
                    initials.appendCodePoint(cp)
                } else {
                    full.append(py)
                    initials.append(py[0])
                }
            }
            true
        }
        return if (ok) full.toString() to initials.toString() else EMPTY
    }

    /**
     * 逐字拼音，给“搜索命中高亮”用：返回和 [text] 每个 char 对齐的列表——
     * 汉字是它的拼音（代理对的两个 char 都放同一个音节），其他字符是它自己；
     * 一个汉字都没有、或拼音不可用时返回 null。只看前 [MAX_CHARS] 个字符。
     */
    fun segments(text: String): List<String>? {
        val src = Search.head(text, MAX_CHARS)
        if (src.isEmpty() || !hasHan(src)) return null
        return synchronized(lock) {
            val t = tr ?: return@synchronized null
            val out = ArrayList<String>(src.length)
            var i = 0
            while (i < src.length) {
                val cp = src.codePointAt(i)
                val n = Character.charCount(cp)
                val py = if (isHan(cp)) syllable(t, cp) else ""
                val seg = py.ifEmpty { src.substring(i, i + n) }
                repeat(n) { out.add(seg) }
                i += n
            }
            out
        }
    }

    /** 单个汉字的拼音；必须在持有 lock 时调用。 */
    private fun syllable(t: Transliterator, cp: Int): String {
        cache[cp]?.let { return it }
        val raw = runCatching { t.transliterate(String(Character.toChars(cp))) }.getOrNull().orEmpty()
        // 只留 a-z：去掉 ICU 可能带出的空格、撇号等，保证全拼里没有分隔符
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            val l = c.lowercaseChar()
            if (l in 'a'..'z') sb.append(l)
        }
        val py = sb.toString()
        if (cache.size < CACHE_LIMIT) cache[cp] = py
        return py
    }

    private fun hasHan(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isHan(cp: Int): Boolean =
        // 先用范围快速排除 ASCII 等常见字符，再问 Unicode 脚本属性（含扩展区汉字）
        cp >= 0x2E80 && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private val EMPTY = "" to ""
}
