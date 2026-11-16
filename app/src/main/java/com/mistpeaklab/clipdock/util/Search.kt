package com.mistpeaklab.clipdock.util

import java.util.Locale

/**
 * 搜索串的构造与匹配。
 *
 * 每条记录存一个派生的搜索串：
 *
 *     归一化后的原文(+备注)  \u0001  全拼  \u0001  首字母
 *
 * 搜索就是在这个串上做“包含”判断（数据库里是 `LIKE %词%`），多个关键词之间是“并且”。
 *
 * 关于分隔符，这里的取舍：
 * - 三段之间用 \u0001 隔开。原文和查询在归一化时都会把 \u0001 换成空格，而查询又按空白拆词，
 *   所以**任何一个关键词里都不可能含有 \u0001**，也就不可能跨段命中
 *   （比如原文结尾的“ab”加上全拼开头的“kuai”拼出一个“abkuai”）。
 * - 原文与备注之间用换行隔开，同理：关键词里不会有空白，所以不会跨着两者命中。
 * - 多个关键词可以分别落在不同的段里（“快递 tz”一个命中原文、一个命中首字母），这是有意的。
 * - 全拼段内部是连着写的（kuaiditongzhi），所以 `kuaidi` 能命中；代价是偶尔会跨音节命中
 *   （`ait` 也能命中“快递通知”）。输入越短越容易碰上，这是拼音搜索固有的，接受。
 *
 * 搜索串是本机派生字段，不导出、不同步；算法改了以后重建即可。
 */
object Search {

    /** 段分隔符。 */
    const val SEP = '\u0001'

    /**
     * 拼音转换器：输入归一化后的文本，返回 (全拼, 首字母)。
     * 做成可替换的，是因为 android.icu 在 JVM 单元测试里不可用，测试时换成假的。
     * 用 lambda 而不是 `Pinyin::of`：这样只有真正调用时才会去加载 Pinyin 类。
     */
    @Volatile
    var pinyin: (String) -> Pair<String, String> = { Pinyin.of(it) }

    /**
     * 构造一条记录的搜索串。
     * 拼音只算原文和备注各自的前 [Pinyin.MAX_CHARS] 个字符；没有拼音时后两段为空，但分隔符始终都在。
     */
    fun build(text: String, note: String? = null): String {
        val t = normalize(text)
        val n = note?.takeIf { it.isNotBlank() }?.let { normalize(it) }

        var (full, initials) = safePinyin(t)
        if (n != null) {
            val (nf, ni) = safePinyin(n)
            // 用空格连接：关键词里没有空白，不会跨着原文和备注的拼音命中
            if (nf.isNotEmpty()) full = if (full.isEmpty()) nf else "$full $nf"
            if (ni.isNotEmpty()) initials = if (initials.isEmpty()) ni else "$initials $ni"
        }

        val sb = StringBuilder(t.length + (n?.length ?: 0) + full.length + initials.length + 3)
        sb.append(t)
        if (n != null) sb.append('\n').append(n)
        sb.append(SEP).append(full).append(SEP).append(initials)
        return sb.toString()
    }

    /** 把用户输入拆成关键词：归一化（小写、全角转半角），按空白拆开，丢掉空的。 */
    fun terms(query: String): List<String> {
        val q = normalize(query)
        val out = ArrayList<String>()
        var start = -1
        for (i in q.indices) {
            if (isBlank(q[i])) {
                if (start >= 0) { out.add(q.substring(start, i)); start = -1 }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) out.add(q.substring(start))
        return out
    }

    /**
     * 每个关键词都出现在搜索串里才算命中。没有关键词（空查询）视为全部命中。
     * [search] 必须来自 [build]，[terms] 必须来自 [Search.terms]——两边的归一化要一致。
     */
    fun matches(search: String, terms: List<String>): Boolean {
        for (t in terms) if (!search.contains(t)) return false
        return true
    }

    /**
     * 把一个关键词变成 SQL 的 LIKE 参数（`%词%`），配合 `LIKE ? ESCAPE '\'` 使用。
     * 关键词里的 `%` `_` `\` 会被转义，否则搜“100%”会变成通配。
     */
    fun likePattern(term: String): String {
        val sb = StringBuilder(term.length + 4).append('%')
        for (c in term) {
            if (c == '%' || c == '_' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.append('%').toString()
    }

    /**
     * 归一化，原文和查询共用同一套规则，这样两边才能对上：
     * - 全角 ASCII（Ａ－Ｚ、０－９、标点，U+FF01..U+FF5E）转半角；全角空格 U+3000 转普通空格；
     * - 分隔符 \u0001 换成空格（见类注释）；
     * - 转小写（固定用 Locale.ROOT，避免土耳其语等区域设置下 I/i 的转换不一致）。
     *
     * 故意不做完整的 Unicode NFKC：那会把“㈱”“①”之类也拆开，改动太大且更慢，目前没有需要。
     */
    fun normalize(s: String): String {
        var sb: StringBuilder? = null   // 大多数文本不需要替换，需要时才分配
        for (i in s.indices) {
            val c = s[i]
            val r = when {
                c == '\u3000' || c == SEP -> ' '
                c in '\uFF01'..'\uFF5E' -> c - 0xFEE0
                else -> c
            }
            if (r != c && sb == null) sb = StringBuilder(s.length).append(s, 0, i)
            sb?.append(r)
        }
        return (sb?.toString() ?: s).lowercase(Locale.ROOT)
    }

    /** 逐字拼音（见 [Pinyin.segments]）；和 [pinyin] 一样做成可替换的，单元测试里换成假的 */
    @Volatile
    var segments: (String) -> List<String>? = { Pinyin.segments(it) }

    /**
     * 搜索命中高亮：返回 [text] 里应该标出来的区间（按位置排序、已合并）。
     * - 关键词直接出现在原文里（不分大小写、全角半角）就标原文那几个字；
     * - 否则（[pinyin] 打开时）按全拼或首字母找，标出拼音对应的那几个汉字，比如 kuaidi / kd 标“快递”。
     * 只是显示用：找不到就不标，不影响搜索结果本身。
     */
    fun highlight(text: String, terms: List<String>, pinyin: Boolean): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val t = normalize(text)
        val out = ArrayList<IntRange>()
        var segs: List<IntRange>? = null   // 每个字（码点）在 text 里的位置
        var full: String? = null; var fullAt: IntArray? = null
        var ini: String? = null
        for (term in terms) {
            if (term.isEmpty()) continue
            var found = false
            var i = t.indexOf(term)
            while (i >= 0) { out += i until i + term.length; found = true; i = t.indexOf(term, i + term.length) }
            if (found || !pinyin) continue
            if (segs == null) {
                val py = runCatching { segments(t) }.getOrNull() ?: continue
                val s = ArrayList<IntRange>(); val fb = StringBuilder(); val ib = StringBuilder(); val at = ArrayList<Int>()
                var k = 0
                while (k < py.size) {
                    var e = k + 1
                    while (e < py.size && e < t.length && Character.isLowSurrogate(t[e])) e++
                    val seg = py[k].lowercase(Locale.ROOT)
                    repeat(seg.length) { at += s.size }
                    fb.append(seg); ib.append(seg.firstOrNull() ?: ' ')
                    s += k until e
                    k = e
                }
                segs = s; full = fb.toString(); fullAt = at.toIntArray(); ini = ib.toString()
            }
            val f = full!!; val fa = fullAt!!; val sg = segs!!
            var j = f.indexOf(term)
            if (j >= 0) {
                while (j >= 0) {
                    out += sg[fa[j]].first..sg[fa[j + term.length - 1]].last
                    j = f.indexOf(term, j + term.length)
                }
                continue
            }
            j = ini!!.indexOf(term)
            while (j >= 0) {
                out += sg[j].first..sg[j + term.length - 1].last
                j = ini.indexOf(term, j + term.length)
            }
        }
        if (out.isEmpty()) return out
        out.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    private fun isBlank(c: Char): Boolean =
        // 不间断空格、零宽空格、BOM 从网页粘贴查询时常带着，也当作分隔
        c.isWhitespace() || c == '\u00A0' || c == '\u200B' || c == '\uFEFF'

    /** 取前 max 个 char，但不把一个代理对从中间切开。放在这里而不是 Pinyin 里：测试时本类不能碰 android.icu。 */
    internal fun head(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    /** 拼音出任何问题都不能影响写入：出错就当作没有拼音。结果里也不允许出现分隔符。 */
    private fun safePinyin(normalized: String): Pair<String, String> {
        val head = head(normalized, Pinyin.MAX_CHARS)
        val (full, initials) = runCatching { pinyin(head) }.getOrNull() ?: return "" to ""
        return full.replace(SEP, ' ') to initials.replace(SEP, ' ')
    }
}
