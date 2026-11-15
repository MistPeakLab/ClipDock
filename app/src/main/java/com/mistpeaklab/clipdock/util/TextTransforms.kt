package com.mistpeaklab.clipdock.util

import com.mistpeaklab.clipdock.tr

import java.text.Collator
import java.util.BitSet
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * “粘贴前变换”里的每一种变换。[label] 直接显示在菜单上（中英文各一份）。
 * 变换只作用于将要粘贴出去的文字，不改原记录（由调用方保证）。
 */
enum class Transform(private val zh: String, private val en: String) {
    JOIN_LINES("去掉换行", "Remove line breaks"),
    TRIM("去首尾空白", "Trim"),
    UPPERCASE("转大写", "UPPERCASE"),
    LOWERCASE("转小写", "lowercase"),
    CAPITALIZE("首字母大写", "Capitalise words"),
    TO_HALF_WIDTH("全角转半角", "Full-width to half-width"),
    TO_FULL_WIDTH_PUNCT("半角标点转全角", "Half-width punctuation to full-width"),
    ADD_CJK_SPACE("中英文之间加空格", "Space between CJK and Latin"),
    REMOVE_CJK_SPACE("去掉中英文之间的空格", "Remove spaces between CJK and Latin"),
    STRIP_TRACKING("去掉网址跟踪参数", "Strip link tracking parameters"),
    EXTRACT_NUMBERS("提取数字", "Extract numbers"),
    EXTRACT_URLS("提取链接", "Extract links"),
    EXTRACT_EMAILS("提取邮箱", "Extract e-mail addresses"),
    DEDUPE_LINES("去重复行", "Remove duplicate lines"),
    SORT_LINES("行排序", "Sort lines"),
    REMOVE_BLANK_LINES("去空行", "Remove blank lines"),
    TRIM_LINES("每行去首尾空白", "Trim each line"),
    QUOTE("加引用符号", "Add quote marks"),
    STRIP_MARKDOWN("去掉 Markdown 标记", "Remove Markdown"),

    ;

    val label: String get() = tr(zh, en)
}

/**
 * 粘贴前变换、按行拆分、带空位的常用语（模板）用到的纯函数。
 *
 * 不依赖 android.*，方便单元测试。
 *
 * 关于超长文本：剪贴板里可能有上百万字，所以这里**不用会回溯的正则**——
 * 唯一的正则只是“固定开头 + 一个字符集”，其余全是手写的单趟扫描，
 * 每个函数的耗时都和文本长度成正比（行排序是 n·log n）。为此做了几处取舍，见各函数的注释。
 *
 * 换行：和 TextTools.clean() 一样把 \r\n、\r 都当换行；凡是按行处理的变换，输出一律用 \n。
 */
object TextTransforms {

    fun apply(t: Transform, text: String): String = when (t) {
        Transform.JOIN_LINES -> joinLines(text)
        Transform.TRIM -> text.trim()
        // 用 Locale.ROOT：土耳其语等系统语言下 i 的大小写规则不同，会把英文转坏
        Transform.UPPERCASE -> text.uppercase(Locale.ROOT)
        Transform.LOWERCASE -> text.lowercase(Locale.ROOT)
        Transform.CAPITALIZE -> capitalizeWords(text)
        Transform.TO_HALF_WIDTH -> toHalfWidth(text)
        Transform.TO_FULL_WIDTH_PUNCT -> toFullWidthPunct(text)
        Transform.ADD_CJK_SPACE -> addCjkSpace(text)
        Transform.REMOVE_CJK_SPACE -> removeCjkSpace(text)
        Transform.STRIP_TRACKING -> stripTracking(text)
        Transform.EXTRACT_NUMBERS -> extractNumbers(text).joinToString("\n")
        Transform.EXTRACT_URLS -> extractUrls(text).joinToString("\n")
        Transform.EXTRACT_EMAILS -> extractEmails(text).joinToString("\n")
        Transform.DEDUPE_LINES -> dedupeLines(text)
        Transform.SORT_LINES -> sortLines(text)
        Transform.REMOVE_BLANK_LINES -> text.lineSequence().filter { it.isNotBlank() }.joinToString("\n")
        Transform.TRIM_LINES -> text.lineSequence().joinToString("\n") { it.trim() }
        Transform.QUOTE -> quote(text)
        Transform.STRIP_MARKDOWN -> stripMarkdown(text)
    }

    // ───────────────────────── 按行拆分 / 合并 ─────────────────────────

    /** 按行拆分：每行去首尾空白，丢掉空行。\r\n、\r、\n 都算换行 */
    fun splitLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** 拆分的反操作：用 [separator] 把多条连起来 */
    fun joinItems(items: List<String>, separator: String): String = items.joinToString(separator)

    // ───────────────────────── 字符分类 ─────────────────────────

    /** 汉字、假名、谚文这类“字”（不含标点） */
    private fun isCjkLetter(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF ||
            cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xAC00..0xD7AF ||
            cp in 0x20000..0x3FFFF || cp == 0x3005 || cp == 0x3007

    /** 中日韩的字，加上全角标点和全角字母数字：这些字符两边不需要空格 */
    private fun isCjkWide(cp: Int): Boolean =
        isCjkLetter(cp) || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF || cp in 0x2E80..0x2FDF

    private fun isAsciiAlnum(cp: Int): Boolean =
        cp in 0x30..0x39 || cp in 0x41..0x5A || cp in 0x61..0x7A

    private fun isAsciiPunct(c: Char): Boolean =
        c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    /** 行内的空白：空格、制表符、不间断空格、全角空格 */
    private fun isGap(c: Char): Boolean = c == ' ' || c == '\t' || c == ' ' || c == '　'

    // ───────────────────────── 换行、大小写 ─────────────────────────

    /**
     * 合并成一行：每行去首尾空白、丢掉空行；接缝两边都是西文时补一个空格，
     * 只要有一边是中文（或全角标点）就直接相连——中文断行处本来就没有空格。
     */
    private fun joinLines(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val prev = sb.codePointBefore(sb.length)
                val next = line.codePointAt(0)
                if (!isCjkWide(prev) && !isCjkWide(next)) sb.append(' ')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    /**
     * 每个单词的首字母大写，其余字母不动（iPhone → IPhone，但 USA 不会变成 Usa）。
     * 撇号算单词内部，所以 don't 是 Don't 而不是 Don'T。
     */
    private fun capitalizeWords(text: String): String {
        val a = text.toCharArray()
        var inWord = false
        for (i in a.indices) {
            val c = a[i]
            if (isCjkWide(c.code)) {
                // 汉字不算单词的一部分：“你好abc” 里的 abc 也要大写
                inWord = false
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord && Character.isLetter(c)) a[i] = Character.toTitleCase(c)
                inWord = true
            } else if (!(inWord && (c == '\'' || c == '’'))) {
                inWord = false
            }
        }
        return a.concatToString()
    }

    // ───────────────────────── 全角 / 半角 ─────────────────────────

    /**
     * 全角转半角：全角字母、数字、标点（U+FF01..U+FF5E）和全角空格。
     * “。”“、”“《》”“「」”这些没有半角对应字符的中文标点不动。
     */
    private fun toHalfWidth(text: String): String {
        val a = text.toCharArray()
        for (i in a.indices) {
            val c = a[i]
            if (c in '！'..'～') a[i] = (c.code - 0xFEE0).toChar()
            else if (c == '　') a[i] = ' '
        }
        return a.concatToString()
    }

    /**
     * 半角标点转全角，只处理 , ; : ! ? ( ) 和句号，而且只在“看起来是中文标点”的位置转：
     * 紧挨着中文（前一个字符是中文 / 全角标点，或后一个字符是中文）。
     * 这样 1,000、12:30、https://…、a.b、3.14 这些都不会被转坏。
     *  - 句号更保守：前面必须是中文，后面不能紧跟字母数字（文件.txt），也不处理省略号 "..."。
     *  - 右括号跟着配对的左括号走：苹果(Apple) → 苹果（Apple）。
     *  - 引号不处理：分不清左右。
     */
    private fun toFullWidthPunct(text: String): String {
        val n = text.length
        val sb = StringBuilder(n)
        val converted = BitSet() // 第 depth 层的左括号有没有转成全角
        var depth = 0
        for (i in 0 until n) {
            val c = text[i]
            if (c != ',' && c != ';' && c != ':' && c != '!' && c != '?' && c != '.' && c != '(' && c != ')') {
                sb.append(c)
                continue
            }
            val prevWide = sb.isNotEmpty() && isCjkWide(sb.codePointBefore(sb.length))
            val nextWide = i + 1 < n && isCjkWide(text.codePointAt(i + 1))
            val convert = when (c) {
                '.' -> prevWide &&
                    !(i + 1 < n && (text[i + 1] == '.' || isAsciiAlnum(text[i + 1].code))) &&
                    !(i > 0 && text[i - 1] == '.')
                '(' -> {
                    val v = prevWide || nextWide
                    converted.set(depth, v)
                    depth++
                    v
                }
                ')' -> if (depth > 0) {
                    depth--
                    converted.get(depth) || prevWide
                } else prevWide
                else -> prevWide || nextWide
            }
            sb.append(if (!convert) c else if (c == '.') '。' else (c.code + 0xFEE0).toChar())
        }
        return sb.toString()
    }

    // ───────────────────────── 中英文之间的空格 ─────────────────────────

    private fun needsGap(a: Int, b: Int): Boolean =
        (isCjkLetter(a) && isAsciiAlnum(b)) || (isAsciiAlnum(a) && isCjkLetter(b))

    /** 汉字和紧挨着的英文字母 / 数字之间补一个空格；标点两边不加 */
    private fun addCjkSpace(text: String): String {
        val n = text.length
        val sb = StringBuilder(n + n / 8)
        var prev = -1
        var i = 0
        while (i < n) {
            val cp = text.codePointAt(i)
            if (prev >= 0 && needsGap(prev, cp)) sb.append(' ')
            sb.appendCodePoint(cp)
            prev = cp
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** 上面的反操作：去掉夹在汉字和英文字母 / 数字之间的空格（英文单词之间的空格不动） */
    private fun removeCjkSpace(text: String): String {
        val n = text.length
        val sb = StringBuilder(n)
        var prev = -1
        var i = 0
        while (i < n) {
            if (isGap(text[i])) {
                var j = i
                while (j < n && isGap(text[j])) j++
                val next = if (j < n) text.codePointAt(j) else -1
                // 夹在中英文之间的这段空白直接丢掉，prev 保持不变
                if (!(prev >= 0 && next >= 0 && needsGap(prev, next))) {
                    sb.append(text, i, j)
                    prev = ' '.code
                }
                i = j
            } else {
                val cp = text.codePointAt(i)
                sb.appendCodePoint(cp)
                prev = cp
                i += Character.charCount(cp)
            }
        }
        return sb.toString()
    }

    // ───────────────────────── 网址 ─────────────────────────

    /**
     * 网址 = http(s):// 后面跟一串可见 ASCII 字符（除了 " < >）。
     * 取舍：遇到中文就算网址结束。中文聊天里网址后面常常直接跟着汉字
     * （“看这个https://a.com/x很不错”），不这样切会把后面的话也吞进去；
     * 代价是网址里未转义的中文（.../wiki/中国）会被截断。不识别没有 http 开头的 www.xxx。
     * 字符集写成 [!#-;=?-~] 而不是交集语法，是为了在各家正则实现上行为一致。
     */
    private val urlRegex = Regex("https?://[!#-;=?-~]+", RegexOption.IGNORE_CASE)

    /** 找出文字里所有网址的位置（start 含，end 不含），已去掉句末标点 */
    private fun findUrls(text: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (m in urlRegex.findAll(text)) {
            val start = m.range.first
            val end = trimUrlEnd(text, start, m.range.last + 1)
            val hostStart = text.indexOf("://", start) + 3
            if (end > hostStart) out.add(intArrayOf(start, end))
        }
        return out
    }

    /** 网址后面紧跟的句号、逗号、多出来的右括号不算网址的一部分 */
    private fun trimUrlEnd(text: String, start: Int, endExclusive: Int): Int {
        var end = endExclusive
        // 括号只数一遍，下面边删边减，避免 "))))…" 时反复统计
        var round = 0
        var square = 0
        for (i in start until end) {
            when (text[i]) {
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
            }
        }
        while (end > start) {
            val c = text[end - 1]
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '\'' || c == '*') {
                end--
            } else if (c == ')' && round < 0) {
                round++
                end--
            } else if (c == ']' && square < 0) {
                square++
                end--
            } else break
        }
        return end
    }

    /** 文字里出现的所有网址，按出现顺序，去重 */
    fun extractUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (r in findUrls(text)) out.add(text.substring(r[0], r[1]))
        return out.toList()
    }

    /** 参数名以这些开头的都是跟踪参数 */
    private val trackingPrefixes = listOf("utm_", "share_", "pk_", "mtm_", "hsa_")

    /** 参数名完全等于这些的是跟踪参数（全小写） */
    private val trackingNames = hashSetOf(
        "spm", "spm_id_from", "scm", "from", "from_source", "from_spmid", "vd_source", "unique_k",
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid",
        "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "si", "ref_src", "ref_url",
        "xhsshare", "sharefrom", "is_from_webapp", "sender_device",
    )

    private fun isTrackingParam(param: String): Boolean {
        var key = param.substringBefore('=').lowercase(Locale.ROOT)
        // 从网页源码里复制出来的网址，& 可能还是 &amp;
        if (key.startsWith("amp;")) key = key.substring(4)
        return key in trackingNames || trackingPrefixes.any { key.startsWith(it) }
    }

    /**
     * 去掉一个网址里的跟踪参数（utm_*、spm、from、share_*、fbclid、gclid、si、vd_source…）。
     * 其它参数和 # 后面的部分原样保留；没有可去的参数时原样返回，一个字符都不动。
     * 注意 from 在个别网站是有实际作用的参数，这里按“分享来源”一律去掉。
     */
    fun cleanUrl(url: String): String {
        val hash = url.indexOf('#')
        val main = if (hash >= 0) url.substring(0, hash) else url
        val q = main.indexOf('?')
        if (q < 0) return url
        val params = main.substring(q + 1).split('&')
        val kept = params.filter { !isTrackingParam(it) }
        if (kept.size == params.size) return url
        val sb = StringBuilder(url.length).append(main, 0, q)
        if (kept.isNotEmpty()) sb.append('?').append(kept.joinToString("&"))
        if (hash >= 0) sb.append(url, hash, url.length)
        return sb.toString()
    }

    /** 把文字里每一个网址的跟踪参数去掉，其它文字不动 */
    fun stripTracking(text: String): String {
        val urls = findUrls(text)
        if (urls.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var last = 0
        for (r in urls) {
            sb.append(text, last, r[0])
            sb.append(cleanUrl(text.substring(r[0], r[1])))
            last = r[1]
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    // ───────────────────────── 提取数字 / 邮箱 ─────────────────────────

    /**
     * 提取数字：连续的半角数字，可带小数（3.14）。
     * 负号只在前面不是字母、数字时才算（“-5”算负数，“2024-05-01”拆成 2024、05、01）。
     * 不处理千分位逗号（分不清 1,234 和“1,2,3”），不去重（同一个数出现两次可能都有用）。
     * 全角数字不认，需要的话先做一次“全角转半角”。
     */
    fun extractNumbers(text: String): List<String> {
        val out = ArrayList<String>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (text[i] !in '0'..'9') {
                i++
                continue
            }
            var start = i
            while (i < n && text[i] in '0'..'9') i++
            if (i + 1 < n && text[i] == '.' && text[i + 1] in '0'..'9') {
                i++
                while (i < n && text[i] in '0'..'9') i++
            }
            if (start > 0 && text[start - 1] == '-' &&
                (start < 2 || !Character.isLetterOrDigit(text[start - 2]))
            ) start--
            out.add(text.substring(start, i))
        }
        return out
    }

    private fun isEmailLocal(c: Char): Boolean =
        isAsciiAlnum(c.code) || c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

    private fun isEmailDomain(c: Char): Boolean = isAsciiAlnum(c.code) || c == '.' || c == '-'

    /**
     * 提取邮箱，按出现顺序，去重。
     * 不用正则：`[\w.]+@…` 这类写法遇到一长串没有 @ 的字母会反复回溯，一百万字能卡死。
     * 这里以每个 @ 为中心向两边看，@ 前最多 64 个字符、后最多 255 个字符（邮箱规范的上限）。
     */
    fun extractEmails(text: String): List<String> {
        val out = LinkedHashSet<String>()
        val n = text.length
        var at = text.indexOf('@')
        while (at >= 0) {
            var s = at
            val minS = maxOf(0, at - 64)
            while (s > minS && isEmailLocal(text[s - 1])) s--
            var e = at + 1
            val maxE = minOf(n, at + 1 + 255)
            while (e < maxE && isEmailDomain(text[e])) e++
            while (s < at && text[s] == '.') s++
            while (e > at + 1 && (text[e - 1] == '.' || text[e - 1] == '-')) e--
            if (s < at && isValidDomain(text, at + 1, e)) out.add(text.substring(s, e))
            at = text.indexOf('@', maxOf(e, at + 1))
        }
        return out.toList()
    }

    /** 域名至少两段，没有空段，最后一段是两个以上的字母 */
    private fun isValidDomain(text: String, start: Int, end: Int): Boolean {
        if (end <= start || text[start] == '.' || text[start] == '-') return false
        var lastDot = -1
        for (i in start until end) {
            if (text[i] == '.') {
                if (lastDot == i - 1) return false
                lastDot = i
            }
        }
        if (lastDot < 0 || end - lastDot - 1 < 2) return false
        for (i in lastDot + 1 until end) if (!Character.isLetter(text[i])) return false
        return true
    }

    // ───────────────────────── 按行处理 ─────────────────────────

    /** 去重复行：保留第一次出现的，顺序不变。空行不参与（否则段落之间的空行只剩一个，段落就粘在一起了） */
    private fun dedupeLines(text: String): String {
        val seen = HashSet<String>()
        return text.lineSequence().filter { it.isBlank() || seen.add(it) }.joinToString("\n")
    }

    /**
     * 行排序：按中文习惯排（英文不分大小写地按字母、中文按拼音，具体由系统的 Collator 决定）。
     * 空行会被丢掉——排完序后它们只会全堆在最前面。数字按字符比较，“10”排在“9”前面。
     */
    private fun sortLines(text: String): String {
        val collator = Collator.getInstance(Locale.CHINA) // Collator 不是线程安全的，每次新建
        return text.lineSequence().filter { it.isNotBlank() }
            .sortedWith(Comparator<String> { a, b -> collator.compare(a, b) })
            .joinToString("\n")
    }

    /** 每行前面加 “> ”。文字以换行结尾时，最后那个空行不加，免得多出一个孤零零的 > */
    private fun quote(text: String): String {
        val lines = text.lines()
        val last = lines.size - 1
        return lines.withIndex().joinToString("\n") { (i, line) ->
            if (line.isEmpty()) (if (i == last && i > 0) "" else ">") else "> $line"
        }
    }

    // ───────────────────────── Markdown ─────────────────────────

    /**
     * 去掉常见的 Markdown 标记（主要是为了把 AI 的回答贴到聊天框里）：
     *  - 标题的 #、引用的 >、分隔线（---、***、===）、表格的分隔行（|---|---|）
     *  - 无序列表的 - * + 换成 “• ”，任务列表的 [ ] / [x] 换成 ☐ / ☑；有序列表的 “1. ” 本来就是普通文字，不动
     *  - 加粗、斜体、删除线的 * _ ~~；行内代码的反引号；代码块的 ``` 围栏（代码本身原样保留）
     *  - [文字](网址) → 文字，![说明](网址) → 说明（没有文字时保留网址），<https://…> → 网址
     *  - 反斜杠转义：\* → *
     * 只是“基本”处理，不是完整的 Markdown 解析：不处理引用式链接、HTML 标签、缩进代码块，
     * 表格内容行的竖线也保留。强调符号只在同一行内配对。
     */
    private fun stripMarkdown(text: String): String {
        val out = StringBuilder(text.length)
        var fence: String? = null
        var first = true
        for (raw in text.lineSequence()) {
            val t = raw.trimStart()
            val f = fence
            val line: String? = if (f != null) {
                if (t.startsWith(f)) {
                    fence = null
                    null
                } else raw
            } else if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = t.substring(0, 3)
                null
            } else stripMarkdownLine(raw)
            if (line == null) continue
            if (!first) out.append('\n')
            first = false
            out.append(line)
        }
        return out.toString()
    }

    /** 处理一行；返回 null 表示这一行整行删掉 */
    private fun stripMarkdownLine(raw: String): String? {
        val n = raw.length
        var p = 0
        while (p < n && (raw[p] == ' ' || raw[p] == '\t')) p++
        if (p == n) return raw
        val indentEnd = p
        if (isRuleLine(raw, p) || isTableSeparator(raw, p)) return null

        while (p < n && raw[p] == '>') {
            p++
            if (p < n && raw[p] == ' ') p++
        }
        var prefix = ""
        var hashes = 0
        while (p + hashes < n && raw[p + hashes] == '#') hashes++
        if (hashes in 1..6 && (p + hashes == n || raw[p + hashes] == ' ')) {
            p += hashes
            while (p < n && raw[p] == ' ') p++
        } else if (p + 1 < n && (raw[p] == '-' || raw[p] == '*' || raw[p] == '+') && raw[p + 1] == ' ') {
            p += 2
            prefix = "• "
            if (raw.startsWith("[ ] ", p)) {
                p += 4
                prefix = "☐ "
            } else if (raw.startsWith("[x] ", p, ignoreCase = true)) {
                p += 4
                prefix = "☑ "
            }
        }
        return raw.substring(0, indentEnd) + prefix + stripInline(raw.substring(p))
    }

    /** 分隔线：整行只有三个以上同一种 - * _ =（之间可以有空格） */
    private fun isRuleLine(s: String, from: Int): Boolean {
        val c = s[from]
        if (c != '-' && c != '*' && c != '_' && c != '=') return false
        var count = 0
        for (i in from until s.length) {
            val ch = s[i]
            if (ch == c) count++
            else if (ch != ' ' && ch != '\t') return false
        }
        return count >= 3
    }

    /** 表格的分隔行：只由 | - : 和空格组成，并且 | 和 - 都出现过 */
    private fun isTableSeparator(s: String, from: Int): Boolean {
        var bar = false
        var dash = false
        for (i in from until s.length) {
            when (s[i]) {
                '|' -> bar = true
                '-' -> dash = true
                ':', ' ', '\t' -> {}
                else -> return false
            }
        }
        return bar && dash
    }

    /**
     * “从 i 开始下一个 ch 在哪”，带缓存。
     * 像 "[[[[[[…]" 这样的文字，每个 [ 都要找一次后面的 ]，不缓存就是平方级的耗时。
     */
    private class NextIndex(private val s: String, private val ch: Char) {
        private var from = 0
        private var pos = -2 // -2：还没查过
        fun at(i: Int): Int {
            if (pos == -2 || i < from || (pos in 0 until i)) {
                pos = s.indexOf(ch, i)
                from = i
            }
            return pos
        }
    }

    private val inlineChars = charArrayOf('*', '_', '~', '`', '[', '<', '\\')

    /** 自动链接 <https://…> 的长度上限；超过就不当链接看，同时也限制了检查空白的范围 */
    private const val MAX_AUTOLINK = 2048

    /** 去掉一行之内的行内标记。单趟扫描；要删掉的强调符号先记在 dead 里，最后一次性跳过 */
    private fun stripInline(s: String): String {
        if (s.indexOfAny(inlineChars) < 0) return s
        val n = s.length
        val sb = StringBuilder(n)
        val dead = BitSet()
        // 还没配上对的开头符号在 sb 里的位置，按“符号 + 个数”分开放，配对时只看同一种
        val openers = HashMap<Int, ArrayList<Int>>()
        val nextTick = NextIndex(s, '`')
        val nextBracket = NextIndex(s, ']')
        val nextParen = NextIndex(s, ')')
        val nextAngle = NextIndex(s, '>')
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n && isAsciiPunct(s[i + 1])) {
                sb.append(s[i + 1])
                i += 2
            } else if (c == '`') {
                var k = i
                while (k < n && s[k] == '`') k++
                val close = nextTick.at(k)
                if (close < 0) {
                    sb.append(s, i, k)
                    i = k
                } else {
                    // 行内代码的内容原样输出，里面的 * _ 不当强调符号
                    sb.append(s, k, close)
                    var e = close
                    while (e < n && s[e] == '`') e++
                    i = e
                }
            } else if (c == '[' || (c == '!' && i + 1 < n && s[i + 1] == '[')) {
                val open = if (c == '!') i + 1 else i
                val close = nextBracket.at(open + 1)
                val end = if (close >= 0 && close + 1 < n && s[close + 1] == '(') nextParen.at(close + 2) else -1
                if (end < 0) {
                    sb.append(c)
                    i++
                } else {
                    // 方括号里不会再有 ]，所以这里的递归最多一层
                    val label = stripInline(s.substring(open + 1, close))
                    if (label.isNotBlank()) sb.append(label)
                    else sb.append(s.substring(close + 2, end).trim().substringBefore(' '))
                    i = end + 1
                }
            } else if (c == '<' && s.startsWith("http", i + 1, ignoreCase = true)) {
                val close = nextAngle.at(i + 1)
                if (close > 0 && close - i <= MAX_AUTOLINK && noWhitespace(s, i + 1, close)) {
                    sb.append(s, i + 1, close)
                    i = close + 1
                } else {
                    sb.append(c)
                    i++
                }
            } else if (c == '*' || c == '_' || c == '~') {
                var k = i
                while (k < n && s[k] == c) k++
                val len = k - i
                val prev = if (i > 0) s[i - 1] else ' '
                val next = if (k < n) s[k] else ' '
                var usable = if (c == '~') len == 2 else len <= 3
                // 5*3 是乘号，不是斜体
                if (c == '*' && len == 1 && prev in '0'..'9' && next in '0'..'9') usable = false
                var canOpen = usable && !next.isWhitespace()
                var canClose = usable && !prev.isWhitespace()
                if (c == '_') {
                    // 下划线在单词中间不算强调：snake_case_name 要原样保留
                    if (Character.isLetterOrDigit(prev)) canOpen = false
                    if (Character.isLetterOrDigit(next)) canClose = false
                }
                val key = c.code * 4 + len
                val stack = openers[key]
                if (canClose && stack != null && stack.isNotEmpty()) {
                    val at = stack.removeAt(stack.size - 1)
                    dead.set(at, at + len)
                } else {
                    if (canOpen) openers.getOrPut(key) { ArrayList() }.add(sb.length)
                    sb.append(s, i, k)
                }
                i = k
            } else {
                sb.append(c)
                i++
            }
        }
        if (dead.isEmpty) return sb.toString()
        val res = StringBuilder(sb.length)
        var pos = 0
        while (pos < sb.length) {
            val d = dead.nextSetBit(pos)
            if (d < 0) {
                res.append(sb, pos, sb.length)
                break
            }
            res.append(sb, pos, d)
            pos = dead.nextClearBit(d)
        }
        return res.toString()
    }

    private fun noWhitespace(s: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (s[i].isWhitespace()) return false
        return true
    }

    // ───────────────────────── 带空位的常用语（模板） ─────────────────────────

    /** 内置变量：不用用户填，粘贴时自动取值 */
    val BUILTIN_PLACEHOLDERS: Set<String> = linkedSetOf("剪贴板", "日期", "时间", "星期")

    /** 空位名字的最大长度。超过就当普通文字，也保证了找 } 时不会一路扫到文末 */
    const val MAX_PLACEHOLDER_NAME = 40

    private val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /**
     * 空位名字里允许的字符：文字、数字、空格、下划线、连字符。
     * 故意不允许标点，这样常用语里的 {"a":1}、{ x; } 之类的代码片段不会被误认成空位。
     */
    private fun isNameChar(c: Char): Boolean =
        Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-'

    /** i 处是 {，返回配对的 } 的位置；不是合法的空位则返回 -1 */
    private fun placeholderEnd(t: String, i: Int): Int {
        val limit = minOf(t.length, i + 2 + MAX_PLACEHOLDER_NAME)
        var blank = true
        var j = i + 1
        while (j < limit) {
            val c = t[j]
            if (c == '}') return if (blank) -1 else j
            if (!isNameChar(c)) return -1
            if (c != ' ') blank = false
            j++
        }
        return -1
    }

    /**
     * 模板的唯一一份解析逻辑，[placeholders] 和 [fill] 共用，保证两边对“什么算空位”的理解一致。
     * literal(start, end) 是一段原样输出的文字；hole(name, start, end) 是一个空位，
     * name 已去首尾空格，start..end 是它在模板里的原文（含花括号）。
     * "{{" 和 "}}" 各输出一个花括号。
     */
    private inline fun parseTemplate(
        t: String,
        literal: (start: Int, end: Int) -> Unit,
        hole: (name: String, start: Int, end: Int) -> Unit,
    ) {
        val n = t.length
        var lit = 0
        var i = 0
        while (i < n) {
            val c = t[i]
            if ((c == '{' || c == '}') && i + 1 < n && t[i + 1] == c) {
                literal(lit, i + 1) // 两个花括号只输出前一个
                i += 2
                lit = i
            } else if (c == '{') {
                val end = placeholderEnd(t, i)
                if (end < 0) {
                    i++
                } else {
                    literal(lit, i)
                    hole(t.substring(i + 1, end).trim(), i, end + 1)
                    i = end + 1
                    lit = i
                }
            } else i++
        }
        literal(lit, n)
    }

    /**
     * 这条常用语是不是模板（含至少一个空位，内置变量也算）。
     * 只有是模板时才应该调用 [fill]：fill 会把 "{{"、"}}" 还原成单个花括号，
     * 对一条碰巧含有 "}}" 的普通常用语（比如一段代码）调用会改掉它的内容。
     */
    fun isTemplate(template: String): Boolean {
        var found = false
        parseTemplate(template, { _, _ -> }, { _, _, _ -> found = true })
        return found
    }

    /** 需要用户填写的空位名字：按出现顺序、不重复、不含内置变量 */
    fun placeholders(template: String): List<String> {
        val out = LinkedHashSet<String>()
        parseTemplate(template, { _, _ -> }, { name, _, _ -> if (name !in BUILTIN_PLACEHOLDERS) out.add(name) })
        return out.toList()
    }

    /**
     * 填空。
     *  - [values] 里有的名字用给的值（给了空字符串就填空字符串）；[values] 优先于内置变量。
     *  - 内置变量：{剪贴板} = [clipboard]，{日期} = 2026-01-05，{时间} = 09:07（24 小时制），{星期} = 星期一。
     *  - 没给值的空位、[clipboard] 为 null 时的 {剪贴板}，都按原样留在结果里。
     *  - "{{" 输出 "{"，"}}" 输出 "}"。
     * 填进去的值不会被再次解析，值里带花括号也没关系。
     * [zone] 只是为了测试能固定时区，平时用默认值。
     */
    fun fill(
        template: String,
        values: Map<String, String>,
        clipboard: String?,
        now: Date,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val sb = StringBuilder(template.length + 16)
        var cal: Calendar? = null
        parseTemplate(template, { start, end -> sb.append(template, start, end) }) { name, start, end ->
            var value: String? = values[name]
            if (value == null && name in BUILTIN_PLACEHOLDERS) {
                if (name == "剪贴板") {
                    value = clipboard
                } else {
                    // 明确用公历 + Locale.ROOT：泰语等系统语言下默认日历是佛历，年份会差 543
                    val c = cal ?: GregorianCalendar(zone, Locale.ROOT).also {
                        it.time = now
                        cal = it
                    }
                    value = when (name) {
                        "日期" -> String.format(
                            Locale.ROOT, "%04d-%02d-%02d",
                            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                        )
                        "时间" -> String.format(
                            Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                        )
                        else -> weekdays[c.get(Calendar.DAY_OF_WEEK) - 1]
                    }
                }
            }
            if (value != null) sb.append(value) else sb.append(template, start, end)
        }
        return sb.toString()
    }
}
