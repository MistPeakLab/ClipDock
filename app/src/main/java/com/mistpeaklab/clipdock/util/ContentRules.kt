package com.mistpeaklab.clipdock.util

import kotlin.math.roundToInt

/** 一条内容“整体上是什么”。TEXT = 没有特别之处 */
enum class ContentKind { TEXT, URL, EMAIL, PHONE, TRACKING_NO, COLOR, NUMBER, ADDRESS }

/** 敏感程度。OTP = 验证码（自动隐藏并到期删除），BANK_CARD = 银行卡号（自动隐藏） */
enum class Sensitivity { NONE, OTP, BANK_CARD }

/**
 * 内容识别规则：纯 Kotlin、无安卓依赖，方便单元测试，也方便以后和 Windows 端对照。
 *
 * 总原则是“宁可漏认，不要错认”：
 * 认错类型会给卡片配上错误的图标和动作；认错敏感内容会把用户的条目藏起来甚至到期删掉，
 * 这两种都比“没认出来”更糟。所以下面的规则普遍偏严。
 *
 * 与“拆开挑选”（PiecePicker 里的 ENTITY）保持一致的地方：
 * 网址遇到空白和中文标点就结束；邮箱是 xxx@域名.后缀；电话允许 + 开头、中间夹空格或短横。
 */
object ContentRules {

    /** 在一段长文字里找到的网址 / 邮箱 / 电话。[start, end) 是它在原文里的位置，value 即原文这一段 */
    data class Found(val kind: ContentKind, val value: String, val start: Int, val end: Int)

    // ───────────────────────── 长度上限 ─────────────────────────

    /** 网址可以很长（带一串参数），单独给一个宽松的上限 */
    private const val MAX_URL_LEN = 2048
    /** 其他类型都是短内容，超过这个长度一律当普通文字 */
    private const val MAX_SHORT_LEN = 80
    /** 只有短信那样的短文字才判断验证码，长文章里出现“验证码”三个字不算 */
    private const val MAX_OTP_TEXT_LEN = 300
    /** 找银行卡号 / 提取链接时最多看这么多字，避免对超长文本做无谓的扫描 */
    private const val MAX_SCAN_LEN = 20000

    // ───────────────────────── 网址 ─────────────────────────

    /** 网址在这些中文标点处结束（和 PiecePicker 一致）；整条内容里出现它们就说明后面还跟着别的话 */
    private const val URL_STOPPERS = "，。、；！？）】》”“‘’「」《【（"

    /**
     * 一个完整的网址：http(s)://主机[:端口][/路径…] 或 www.xxx.yyy[…]。
     * 主机允许中文域名；不支持“用户名:密码@主机”这种写法（很少见，而且容易把邮箱认成网址）。
     */
    private val URL_WHOLE = Regex(
        """(?:https?://(?:[A-Za-z0-9_一-龥-]+(?:\.[A-Za-z0-9_一-龥-]+)*|\[[0-9a-f:.]+\])(?::[0-9]{1,5})?""" +
            """|www\.[A-Za-z0-9_一-龥-]+(?:\.[A-Za-z0-9_一-龥-]+)+(?::[0-9]{1,5})?)""" +
            """(?:[/?#]\S*)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 在长文字里找网址的起点和大致范围，结尾多带的标点由 trimUrlTail 去掉。
     * 这里遇到汉字就结束：“点击https://t.cn/abc查看”很常见，而网址里直接带汉字的很少见。
     */
    private val URL_FIND = Regex(
        """(?:https?://|(?<![A-Za-z0-9_@.\-/])www\.)[^\s<>\x22，。、；！？）】》”“‘’「」《【（一-龥]+""",
        RegexOption.IGNORE_CASE
    )

    private fun isUrl(t: String): Boolean =
        t.none { it in URL_STOPPERS } && URL_WHOLE.matches(t)

    /**
     * 去掉网址末尾多带的标点：句子结尾的句号逗号，以及不成对的右括号。
     * 例如“(见 https://a.com/x_(y))”里最后一个括号属于句子，不属于网址。
     */
    private fun trimUrlTail(s: String): String {
        var e = s.length
        while (e > 0) {
            val c = s[e - 1]
            val drop = when (c) {
                '.', ',', ';', ':', '!', '?', '\'', '*', '~' -> true
                ')' -> countChar(s, e, ')') > countChar(s, e, '(')
                ']' -> countChar(s, e, ']') > countChar(s, e, '[')
                else -> false
            }
            if (!drop) break
            e--
        }
        return s.substring(0, e)
    }

    private fun countChar(s: String, end: Int, c: Char): Int {
        var n = 0
        for (i in 0 until end) if (s[i] == c) n++
        return n
    }

    // ───────────────────────── 邮箱 ─────────────────────────

    /** 比 PiecePicker 略严：后缀必须是至少两个字母，避免把 “a@b.1” 之类当成邮箱 */
    private const val EMAIL_CORE =
        """[A-Za-z0-9][A-Za-z0-9_.+-]*@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"""

    private val EMAIL_WHOLE = Regex(EMAIL_CORE)
    private val EMAIL_FIND = Regex("""(?<![A-Za-z0-9_.+\-])""" + EMAIL_CORE + """(?![A-Za-z0-9_-])""")

    // ───────────────────────── 电话 ─────────────────────────

    /** 国内手机：1[3-9] 开头 11 位，可带 +86 / 0086，中间可按 3-4-4 夹空格或短横 */
    private const val MOBILE_CORE =
        """(?:(?:\+|00)86[- ]?)?1[3-9][0-9][- ]?[0-9]{4}[- ]?[0-9]{4}"""

    /**
     * 国内座机：区号 + 7~8 位本地号码，可带分机。
     * 区号只认真实存在的形态（010、02x、03xx~09xx），本地号码不以 0/1 开头，
     * 这样“0 开头的一串数字”不会随便被当成电话。
     */
    private const val LANDLINE_CORE =
        """(?:\((?:010|02[0-9]|0[3-9][0-9]{2})\)|010|02[0-9]|0[3-9][0-9]{2})[- ]?[2-9][0-9]{6,7}(?:(?:-|转)[0-9]{1,6})?"""

    /** 400 / 800 客服电话，共 10 位 */
    private const val SERVICE_CORE = """[48]00[- ]?[0-9]{3}[- ]?[0-9]{4}"""

    /** 国际号码：必须以 + 开头，数字总数另外检查（8~15 位） */
    private const val INTL_CORE = """\+[1-9][0-9]{0,3}(?:[- ]?(?:\([0-9]{1,5}\)|[0-9]{1,5})){1,6}"""

    private val MOBILE_WHOLE = Regex(MOBILE_CORE)
    private val LANDLINE_WHOLE = Regex(LANDLINE_CORE)
    private val SERVICE_WHOLE = Regex(SERVICE_CORE)
    private val INTL_WHOLE = Regex(INTL_CORE)

    /** 长文字里的电话：前后不能紧贴着字母、数字、小数点，否则多半是订单号、金额的一部分 */
    private const val PHONE_BEFORE = """(?<![A-Za-z0-9_.+\-])"""
    private const val PHONE_AFTER = """(?![0-9])(?![A-Za-z_])(?![-.][0-9])"""

    /** 顺序即优先级：先认最确定的手机号，最后才是宽松的国际号码 */
    private val PHONE_FINDERS = listOf(
        Regex(PHONE_BEFORE + MOBILE_CORE + PHONE_AFTER),
        Regex(PHONE_BEFORE + LANDLINE_CORE + PHONE_AFTER),
        Regex(PHONE_BEFORE + SERVICE_CORE + PHONE_AFTER),
        Regex(PHONE_BEFORE + INTL_CORE + PHONE_AFTER)
    )

    private fun intlDigitsOk(s: String): Boolean = s.count { it in '0'..'9' } in 8..15

    private fun isPhone(t: String): Boolean =
        MOBILE_WHOLE.matches(t) || LANDLINE_WHOLE.matches(t) || SERVICE_WHOLE.matches(t) ||
            (INTL_WHOLE.matches(t) && intlDigitsOk(t))

    // ───────────────────────── 快递单号 ─────────────────────────

    /**
     * 只认带已知前缀的单号：顺丰 SF、京东 JD、极兔 JT、圆通 YT、德邦 DPK，
     * 以及国际邮政统一格式（两个字母 + 9 位数字 + 两个字母国家码，如 EA123456789CN）。
     * 12~15 位纯数字既可能是单号也可能是别的编号，分不清，归到 NUMBER。
     */
    private val TRACKING = Regex(
        """SF[0-9]{12,15}|JD[A-Z]{0,2}[0-9]{11,15}|JT[0-9]{13,15}|YT[0-9]{13,15}|DPK[0-9]{12,15}|[A-Z]{2}[0-9]{9}[A-Z]{2}""",
        RegexOption.IGNORE_CASE
    )

    // ───────────────────────── 数字 / 金额 ─────────────────────────

    /** 纯数字、带千分位、带货币符号、带 % 或“元 / 万”等单位。只认半角数字 */
    private val NUMBER = Regex(
        "[+-]?(?:[¥￥\\$€£]\\s?)?[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?\\s?(?:%|‰|元|万元|亿元|万|亿|块)?"
    )

    // ───────────────────────── 颜色 ─────────────────────────

    private val HEX_COLOR = Regex("""#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})""")
    private val RGB_COLOR = Regex(
        """rgba?\(\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*(?:,\s*([0-9]{1,3}%|[0-9]?\.[0-9]{1,4}|[01])\s*)?\)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 把颜色文字解析成安卓用的 ARGB 整数；不是颜色返回 null。
     * 支持 #RGB、#RRGGBB、#RRGGBBAA（网页写法，透明度在最后）、rgb(r,g,b)、rgba(r,g,b,a)。
     * 故意不支持 #RGBA 四位写法：“#1234”更可能是编号。
     */
    fun parseColor(text: String): Int? {
        val t = text.trim()
        if (t.length > 40) return null
        if (HEX_COLOR.matches(t)) {
            val hex = t.substring(1)
            return when (hex.length) {
                3 -> {
                    val full = buildString { for (c in hex) { append(c); append(c) } }
                    (0xFF000000L or full.toLong(16)).toInt()
                }
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                else -> {
                    // 网页写法是 RRGGBBAA，安卓要 AARRGGBB，把透明度挪到最前
                    val v = hex.toLong(16)
                    (((v and 0xFF) shl 24) or ((v shr 8) and 0xFFFFFF)).toInt()
                }
            }
        }
        val m = RGB_COLOR.matchEntire(t) ?: return null
        val r = m.groupValues[1].toInt()
        val g = m.groupValues[2].toInt()
        val b = m.groupValues[3].toInt()
        if (r > 255 || g > 255 || b > 255) return null
        val aText = m.groupValues[4]
        val a = when {
            aText.isEmpty() -> 255
            aText.endsWith("%") -> {
                val p = aText.dropLast(1).toInt()
                if (p > 100) return null
                (p * 255 / 100.0).roundToInt()
            }
            else -> {
                val f = aText.toDoubleOrNull() ?: return null
                if (f < 0.0 || f > 1.0) return null
                (f * 255).roundToInt()
            }
        }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ───────────────────────── 地址 ─────────────────────────

    /**
     * 出现这些就不是“一条地址”而是一句话：句末标点、网址邮箱里的符号、常见的虚词代词。
     * 这会漏掉“我住在××路 3 号”这类句子，但能挡住大量“今天去了××市××区”的误判。
     */
    private val ADDR_REJECT = Regex(
        """[。！？!?；;…@/\\=<>{}\[\]|的了是我你他她吗呢啊请把被很这哪没不]|今天|明天|昨天"""
    )

    private val ADDR_PROVINCE = Regex("""[一-龥]{2}(?:省|自治区)""")
    /** “超市、城市、门市、夜市…”里的“市”不是行政区 */
    private val ADDR_CITY = Regex("""[一-龥]{2}(?:(?<![超城门夜菜集黑股楼闹早])市|自治州)""")
    /** “地区、郊区、市区、景区…”里的“区”不是行政区 */
    private val ADDR_DISTRICT = Regex("""[一-龥]{2}(?:(?<![地郊市景片灾禁时专特学])区|县)""")
    private val ADDR_TOWN = Regex("""[一-龥]{2}(?:镇|乡|街道)""")
    /** 前面必须是两个汉字（“10路公交”不算）；“公路、铁路、马路、思路…”里的“路”不是路名 */
    private val ADDR_ROAD = Regex("""[一-龥]{2}(?:(?<![公铁马思网线套出])路|大道|巷|弄|胡同|街(?!道))""")
    private val ADDR_NUMBER = Regex("""[0-9]+\s?(?:号|栋|幢|座|室|单元|楼|层|弄)""")

    /**
     * 中文地址的粗略判断：省 → 市 → 区县 → 乡镇 → 路 → 门牌号，按这个先后顺序在文字里找，
     * 找到三级以上，或者同时有“路”和“门牌号”，才算地址。
     * 只有“北京市海淀区”两级的不算——太容易出现在普通句子里。
     */
    private fun looksLikeAddress(t: String): Boolean {
        if (t.length !in 5..MAX_SHORT_LEN) return false
        if (t.count { it in '一'..'龥' } < 4) return false
        if (ADDR_REJECT.containsMatchIn(t)) return false
        var pos = 0
        var levels = 0
        fun step(re: Regex): Boolean {
            if (pos >= t.length) return false
            val m = re.find(t, pos) ?: return false
            pos = m.range.last + 1
            levels++
            return true
        }
        step(ADDR_PROVINCE)
        step(ADDR_CITY)
        step(ADDR_DISTRICT)
        step(ADDR_TOWN)
        val road = step(ADDR_ROAD)
        val number = step(ADDR_NUMBER)
        return levels >= 3 || (road && number)
    }

    // ───────────────────────── kindOf ─────────────────────────

    /**
     * 判断“整条内容”是什么（先去掉首尾空白）。
     * 只有整条内容恰好就是一个网址 / 邮箱 / 电话…才算；一句话里夹着网址仍是 TEXT，
     * 想把它找出来请用 [extract]。多行或很长的内容一律是 TEXT。
     */
    fun kindOf(text: String): ContentKind {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_URL_LEN) return ContentKind.TEXT
        if (t.any { it == '\n' || it == '\r' }) return ContentKind.TEXT
        if (isUrl(t)) return ContentKind.URL
        if (t.length > MAX_SHORT_LEN) return ContentKind.TEXT
        if (EMAIL_WHOLE.matches(t)) return ContentKind.EMAIL
        if (parseColor(t) != null) return ContentKind.COLOR
        // 电话要排在数字前面：13812345678 既是数字也是手机号，后者更有用
        if (isPhone(t)) return ContentKind.PHONE
        if (TRACKING.matches(t)) return ContentKind.TRACKING_NO
        if (NUMBER.matches(t)) return ContentKind.NUMBER
        if (looksLikeAddress(t)) return ContentKind.ADDRESS
        return ContentKind.TEXT
    }

    // ───────────────────────── extract ─────────────────────────

    /**
     * 在一段文字里找出所有网址、邮箱、电话，按出现位置排序，互不重叠。
     * 网址优先于邮箱，邮箱优先于电话：网址里的一串数字、邮箱前缀里的手机号都不会被重复找出来。
     * 超长文本只看开头 [MAX_SCAN_LEN] 个字。
     */
    fun extract(text: String): List<Found> {
        val t = if (text.length > MAX_SCAN_LEN) text.substring(0, MAX_SCAN_LEN) else text
        val out = ArrayList<Found>()
        fun free(s: Int, e: Int) = out.none { s < it.end && e > it.start }

        for (m in URL_FIND.findAll(t)) {
            val v = trimUrlTail(m.value)
            if (!URL_WHOLE.matches(v)) continue
            val s = m.range.first
            if (free(s, s + v.length)) out.add(Found(ContentKind.URL, v, s, s + v.length))
        }
        for (m in EMAIL_FIND.findAll(t)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (free(s, e)) out.add(Found(ContentKind.EMAIL, m.value, s, e))
        }
        for ((i, re) in PHONE_FINDERS.withIndex()) {
            val isIntl = i == PHONE_FINDERS.lastIndex
            for (m in re.findAll(t)) {
                if (isIntl && !intlDigitsOk(m.value)) continue
                val s = m.range.first
                val e = m.range.last + 1
                if (free(s, e)) out.add(Found(ContentKind.PHONE, m.value, s, e))
            }
        }
        out.sortBy { it.start }
        return out
    }

    // ───────────────────────── 敏感内容 ─────────────────────────

    private val OTP_KEYWORDS_CN = listOf("验证码", "校验码", "动态码", "动态密码")

    /** 英文关键词必须是独立的单词：postcode、decode 里的 code 不算 */
    private val OTP_KEYWORD_EN = Regex("""(?<![A-Za-z])(?:code|otp|verification)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** “error code 4040”“zip code 100000”这些 code 和验证码无关 */
    private val CODE_NOT_OTP = listOf(
        "error", "status", "exit", "zip", "postal", "area", "source", "qr", "bar", "promo", "coupon",
        "invite", "invitation", "discount", "country", "referral", "response", "http", "product"
    )

    private val DIGIT_RUN = Regex("""[0-9]+""")
    private val ALNUM_RUN = Regex("""[A-Za-z0-9_]+""")

    /** 字母数字混合的验证码必须紧跟在关键词后面这么多字以内，否则太容易把型号、单词认成验证码 */
    private const val MIXED_CODE_MAX_GAP = 12

    /** 数字后面跟着这些字，说明它是年份、金额、时长之类，不是验证码 */
    private const val OTP_UNITS = "年月日天时分秒元块个位次条人%‰号楼室折万亿"

    /**
     * 本文件的正则一律写 [0-9]、[A-Za-z0-9_]，不用“反斜杠 d / 反斜杠 w”简写：
     * 安卓的正则引擎会让简写匹配全角数字和汉字，和电脑上跑单元测试时不一样，写明确两边结果才相同。
     */
    private fun isAsciiDigit(c: Char) = c in '0'..'9'
    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** 所有有效关键词的结束位置；空表示没有关键词 */
    private fun otpKeywordEnds(t: String): List<Int> {
        val ends = ArrayList<Int>()
        for (k in OTP_KEYWORDS_CN) {
            var i = t.indexOf(k)
            while (i >= 0) {
                ends.add(i + k.length)
                i = t.indexOf(k, i + k.length)
            }
        }
        for (m in OTP_KEYWORD_EN.findAll(t)) {
            if (m.value.equals("code", ignoreCase = true)) {
                val before = t.substring(0, m.range.first).trimEnd().lowercase()
                if (CODE_NOT_OTP.any { before.endsWith(it) }) continue
            }
            ends.add(m.range.last + 1)
        }
        ends.sort()
        return ends
    }

    /** 这串数字的前面是否“干净”：不是金额、小数、日期、电话、卡号尾号的一部分 */
    private fun cleanBefore(t: String, i: Int): Boolean {
        if (i == 0) return true
        val p = t[i - 1]
        if (isAsciiLetter(p) || p in "_.,/#@¥￥$+*=") return false
        // “G-384920”这种带字母前缀的验证码可以；“2024-0115”这种日期不行
        if (p == '-') return i >= 2 && isAsciiLetter(t[i - 2])
        // “138 1234 5678”：前面隔一个空格又是数字，说明是分段写的长号码
        if (p == ' ' && i >= 2 && isAsciiDigit(t[i - 2])) return false
        val head = t.substring(0, i).trimEnd()
        if (head.endsWith("尾号") || head.endsWith("尾数") || head.endsWith("末四位") || head.endsWith("后四位")) return false
        return true
    }

    /** 这串数字的后面是否“干净”：后面不跟单位（年、元、分钟…），也不是小数、日期、时间的前半截 */
    private fun cleanAfter(t: String, j: Int): Boolean {
        if (j >= t.length) return true
        val n = t[j]
        if (isAsciiLetter(n) || n in "_@*") return false
        if (n in ".-/:," && j + 1 < t.length && isAsciiDigit(t[j + 1])) return false
        // “2024 年”“5 分钟”：数字和单位之间隔了一个空格也一样
        if (n == ' ' && j + 1 < t.length && t[j + 1] in OTP_UNITS) return false
        if (n in OTP_UNITS) return false
        return true
    }

    /**
     * 从带有“验证码”等关键词的短文字里取出验证码本身；取不到返回 null。
     * 优先取关键词后面的那一个（“尾号 1234 的账户，验证码 5678”取 5678）。
     */
    fun otpCode(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_OTP_TEXT_LEN) return null
        val kwEnds = otpKeywordEnds(t)
        if (kwEnds.isEmpty()) return null

        val digits = DIGIT_RUN.findAll(t).filter {
            it.value.length in 4..8 && cleanBefore(t, it.range.first) && cleanAfter(t, it.range.last + 1)
        }.toList()
        if (digits.isNotEmpty()) {
            val firstKw = kwEnds.first()
            return (digits.firstOrNull { it.range.first >= firstKw } ?: digits.first()).value
        }

        // 字母数字混合的验证码（如 A3F9K2）：必须同时有字母和数字，并且紧跟在关键词后面
        for (m in ALNUM_RUN.findAll(t)) {
            val v = m.value
            if (v.length !in 4..8 || v.contains('_')) continue
            if (v.none { isAsciiDigit(it) } || v.none { isAsciiLetter(it) }) continue
            val s = m.range.first
            val e = m.range.last + 1
            if (s > 0 && t[s - 1] in "-./@:#") continue
            if (e < t.length && t[e] in "-/@:#") continue
            if (kwEnds.any { s - it in 0..MIXED_CODE_MAX_GAP }) return v
        }
        return null
    }

    /**
     * 整条内容只是 4 位或 6 位纯数字（没有任何关键词）。
     * 这种内容可能是验证码，也可能只是用户复制的一个数字，[sensitivity] 不替调用方做决定：
     * 调用方可以结合来源应用、是否刚收到短信等再判断要不要当验证码处理。
     */
    fun looksLikeBareCode(text: String): Boolean {
        val t = text.trim()
        return (t.length == 4 || t.length == 6) && t.all { isAsciiDigit(it) }
    }

    /** 候选卡号：16~19 位数字，中间可夹空格或短横；前后不能紧贴字母数字（那是单号、流水号的一部分） */
    private val CARD_CANDIDATE = Regex(
        """(?<![A-Za-z0-9.])(?<![0-9][ -])[0-9](?:[ -]?[0-9]){15,18}(?![ -]?[0-9])(?![A-Za-z])"""
    )

    /** Luhn 校验（银行卡号的校验位算法）。只接受纯数字串 */
    fun luhnValid(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { isAsciiDigit(it) }) return false
        var sum = 0
        var dbl = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (dbl) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    /**
     * 18 位纯数字、第 7~14 位是一个合理的出生日期：按身份证号看待，不当银行卡。
     * 身份证号有一成概率碰巧通过 Luhn 校验，而 18 位的银行卡极少见。
     */
    private fun looksLikeIdNumber(d: String): Boolean {
        if (d.length != 18) return false
        val year = d.substring(6, 10).toInt()
        val month = d.substring(10, 12).toInt()
        val day = d.substring(12, 14).toInt()
        return year in 1900..2099 && month in 1..12 && day in 1..31
    }

    private fun hasBankCard(t: String): Boolean {
        for (m in CARD_CANDIDATE.findAll(t)) {
            val d = m.value.filter { isAsciiDigit(it) }
            if (d.length !in 16..19) continue
            // 银行卡号不以 0、1 开头；这一条顺便挡掉纳秒时间戳（19 位、以 1 开头，有一成概率碰巧通过校验）
            if (d[0] == '0' || d[0] == '1') continue
            if (d.all { it == d[0] }) continue
            if (looksLikeIdNumber(d)) continue
            if (luhnValid(d)) return true
        }
        return false
    }

    /**
     * 这条内容是否敏感。
     * - BANK_CARD：文字里有 16~19 位、通过 Luhn 校验的卡号。
     * - OTP：短文字里有“验证码 / 校验码 / 动态码 / 动态密码 / code / OTP / verification”关键词，
     *   并且能找到一个 4~8 位的验证码。
     * - 整条内容只是一串数字、没有关键词时返回 NONE，由调用方用 [looksLikeBareCode] 自己决定。
     *
     * 先判银行卡再判验证码：验证码会到期自动删除，而一条同时写着卡号的内容多半是用户自己记的资料，
     * 只隐藏、不删除更稳妥。
     */
    fun sensitivity(text: String): Sensitivity {
        val t = text.trim()
        if (t.isEmpty()) return Sensitivity.NONE
        val scan = if (t.length > MAX_SCAN_LEN) t.substring(0, MAX_SCAN_LEN) else t
        if (hasBankCard(scan)) return Sensitivity.BANK_CARD
        if (otpCode(t) != null) return Sensitivity.OTP
        return Sensitivity.NONE
    }
}
