package com.mistpeaklab.clipdock.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Search 的单元测试。android.icu 在 JVM 里不可用，所以这里换上一个只认识几个字的假拼音转换器，
 * 行为与 Pinyin.of 的约定一致：汉字转拼音、非汉字原样保留、没有认识的汉字时返回两个空串。
 */
class SearchTest {

    private val dict = mapOf(
        '快' to "kuai", '递' to "di", '通' to "tong", '知' to "zhi",
        '群' to "qun", '备' to "bei", '注' to "zhu",
    )

    private val fake: (String) -> Pair<String, String> = { s ->
        if (s.none { it in dict }) {
            "" to ""
        } else {
            val full = StringBuilder()
            val initials = StringBuilder()
            for (c in s) {
                val py = dict[c]
                if (py == null) { full.append(c); initials.append(c) }
                else { full.append(py); initials.append(py[0]) }
            }
            full.toString() to initials.toString()
        }
    }

    private lateinit var saved: (String) -> Pair<String, String>

    @Before fun setUp() { saved = Search.pinyin; Search.pinyin = fake }
    @After fun tearDown() { Search.pinyin = saved }

    private fun hit(text: String, query: String, note: String? = null) =
        Search.matches(Search.build(text, note), Search.terms(query))

    // ---------- terms ----------

    @Test fun terms_empty_and_blank() {
        assertEquals(emptyList<String>(), Search.terms(""))
        assertEquals(emptyList<String>(), Search.terms("   "))
        assertEquals(emptyList<String>(), Search.terms(" \t\n\u3000\u00A0 "))
    }

    @Test fun terms_single() {
        assertEquals(listOf("abc"), Search.terms("abc"))
        assertEquals(listOf("abc"), Search.terms("  abc  "))
    }

    @Test fun terms_lowercased() {
        assertEquals(listOf("hello", "world"), Search.terms("Hello WORLD"))
    }

    @Test fun terms_split_on_any_whitespace() {
        assertEquals(listOf("a", "b", "c", "d"), Search.terms("a  b\tc\nd"))
    }

    @Test fun terms_split_on_fullwidth_space() {
        assertEquals(listOf("快递", "通知"), Search.terms("快递\u3000通知"))
    }

    @Test fun terms_split_on_nbsp_and_zero_width() {
        assertEquals(listOf("a", "b", "c", "d"), Search.terms("a\u00A0b\u200Bc\uFEFFd"))
    }

    @Test fun terms_fullwidth_to_halfwidth() {
        assertEquals(listOf("abc", "123"), Search.terms("ＡＢＣ　１２３"))
    }

    @Test fun terms_never_contain_separator() {
        assertEquals(listOf("a", "b"), Search.terms("a\u0001b"))
        assertEquals(emptyList<String>(), Search.terms("\u0001"))
    }

    @Test fun terms_keep_duplicates_and_order() {
        assertEquals(listOf("b", "a", "b"), Search.terms("b a b"))
    }

    // ---------- normalize ----------

    @Test fun normalize_fullwidth_letters_digits_space() {
        assertEquals("abc 123", Search.normalize("ＡＢＣ　１２３"))
    }

    @Test fun normalize_fullwidth_punctuation_range_edges() {
        // U+FF01 ！ 与 U+FF5E ～ 是全角 ASCII 区的两端
        assertEquals("!~", Search.normalize("\uFF01\uFF5E"))
        assertEquals("a@b.com", Search.normalize("ａ＠ｂ．ｃｏｍ"))
    }

    @Test fun normalize_leaves_chinese_punctuation_alone() {
        // 。、「」不在全角 ASCII 区，没有对应的半角字符，保持不变
        assertEquals("你好。「a」、", Search.normalize("你好。「Ａ」、"))
        // 紧挨着区间外侧的字符也不动
        assertEquals("\uFF00\uFF5F", Search.normalize("\uFF00\uFF5F"))
    }

    @Test fun normalize_lowercase_and_passthrough() {
        assertEquals("hello 世界", Search.normalize("HeLLo 世界"))
        assertEquals("", Search.normalize(""))
        assertEquals("plain", Search.normalize("plain"))
    }

    @Test fun normalize_replaces_separator_with_space() {
        assertEquals("a b", Search.normalize("a\u0001b"))
    }

    @Test fun normalize_keeps_newlines_and_emoji() {
        assertEquals("a\nb", Search.normalize("A\nB"))
        assertEquals("ok\uD83D\uDE00", Search.normalize("OK\uD83D\uDE00"))
    }

    // ---------- build ----------

    @Test fun build_format_with_pinyin() {
        assertEquals("快递通知\u0001kuaiditongzhi\u0001kdtz", Search.build("快递通知"))
    }

    @Test fun build_format_without_han_has_empty_pinyin_segments() {
        assertEquals("hello world\u0001\u0001", Search.build("Hello World"))
    }

    @Test fun build_empty_text() {
        assertEquals("\u0001\u0001", Search.build(""))
    }

    @Test fun build_always_has_exactly_two_separators() {
        for (s in listOf("", "abc", "快递", "a\u0001b\u0001c", "快\u0001递")) {
            assertEquals(s, 2, Search.build(s).count { it == Search.SEP })
            assertEquals(s, 2, Search.build(s, "备\u0001注").count { it == Search.SEP })
        }
    }

    @Test fun build_mixed_text_keeps_non_han_in_pinyin() {
        assertEquals("wx群\u0001wxqun\u0001wxq", Search.build("WX群"))
    }

    @Test fun build_with_note() {
        assertEquals(
            "快递\n备注 x\u0001kuaidi beizhu x\u0001kd bz x",
            Search.build("快递", "备注 X"),
        )
    }

    @Test fun build_note_only_has_han() {
        assertEquals("abc\n备注\u0001beizhu\u0001bz", Search.build("abc", "备注"))
    }

    @Test fun build_blank_or_null_note_ignored() {
        val plain = Search.build("快递")
        assertEquals(plain, Search.build("快递", null))
        assertEquals(plain, Search.build("快递", ""))
        assertEquals(plain, Search.build("快递", "  \n"))
    }

    @Test fun build_normalises_fullwidth_text() {
        assertEquals("abc 123\u0001\u0001", Search.build("ＡＢＣ　１２３"))
    }

    @Test fun build_converter_gets_normalised_text() {
        var seen = ""
        Search.pinyin = { seen = it; "" to "" }
        Search.build("ＡBc快")
        assertEquals("abc快", seen)
    }

    @Test fun build_converter_only_gets_first_2000_chars() {
        val lens = ArrayList<Int>()
        Search.pinyin = { lens.add(it.length); "" to "" }
        val long = "快".repeat(Pinyin.MAX_CHARS + 500)
        val s = Search.build(long, long)
        assertEquals(listOf(Pinyin.MAX_CHARS, Pinyin.MAX_CHARS), lens)
        // 原文不截断
        assertTrue(s.startsWith(long + "\n" + long + "\u0001"))
    }

    @Test fun build_does_not_split_surrogate_pair_at_limit() {
        var seen = ""
        Search.pinyin = { seen = it; "" to "" }
        // 第 2000 个 char 正好是代理对的前一半
        Search.build("a".repeat(Pinyin.MAX_CHARS - 1) + "\uD83D\uDE00" + "b")
        assertEquals(Pinyin.MAX_CHARS - 1, seen.length)
        assertFalse(Character.isHighSurrogate(seen.last()))
    }

    @Test fun build_survives_converter_failure() {
        Search.pinyin = { throw IllegalStateException("boom") }
        assertEquals("快递\u0001\u0001", Search.build("快递"))
    }

    @Test fun build_strips_separator_from_converter_output() {
        Search.pinyin = { "a\u0001b" to "c\u0001d" }
        assertEquals("x\u0001a b\u0001c d", Search.build("x"))
    }

    // ---------- matches ----------

    @Test fun matches_empty_terms_matches_everything() {
        assertTrue(Search.matches(Search.build("anything"), emptyList()))
        assertTrue(hit("anything", "   "))
    }

    @Test fun matches_original_text() {
        assertTrue(hit("快递通知", "快递"))
        assertTrue(hit("快递通知", "通知"))
        assertTrue(hit("快递通知", "递通"))
        assertFalse(hit("快递通知", "知快"))
    }

    @Test fun matches_full_pinyin() {
        assertTrue(hit("快递通知", "kuaidi"))
        assertTrue(hit("快递通知", "kuaiditongzhi"))
        assertTrue(hit("快递通知", "tongzhi"))
        assertTrue(hit("快递通知", "KuaiDi"))
        assertFalse(hit("快递通知", "kuaidx"))
    }

    @Test fun matches_initials() {
        assertTrue(hit("快递通知", "kdtz"))
        assertTrue(hit("快递通知", "kd"))
        assertTrue(hit("快递通知", "tz"))
        assertTrue(hit("快递通知", "KDTZ"))
        assertFalse(hit("快递通知", "kdzt"))
    }

    @Test fun matches_mixed_latin_and_han() {
        assertTrue(hit("WX群", "wxq"))
        assertTrue(hit("WX群", "wxqun"))
        assertTrue(hit("WX群", "wx群"))
    }

    @Test fun matches_case_insensitive() {
        assertTrue(hit("Hello World", "hello"))
        assertTrue(hit("hello world", "WORLD"))
    }

    @Test fun matches_multiple_terms_are_and() {
        assertTrue(hit("快递通知 已签收", "快递 签收"))
        assertTrue(hit("快递通知 已签收", "签收 快递"))
        assertFalse(hit("快递通知 已签收", "快递 拒收"))
    }

    @Test fun matches_terms_may_hit_different_segments() {
        // 一个命中原文，一个命中首字母，一个命中全拼
        assertTrue(hit("快递通知", "快递 tz tong"))
    }

    @Test fun matches_note() {
        assertTrue(hit("abc", "备注", note = "备注"))
        assertTrue(hit("abc", "bz", note = "备注"))
        assertTrue(hit("abc", "beizhu", note = "备注"))
        assertTrue(hit("abc", "abc bz", note = "备注"))
        assertFalse(hit("abc", "bz"))
    }

    @Test fun matches_fullwidth_query_against_halfwidth_text() {
        assertTrue(hit("abc 123", "ＡＢＣ"))
        assertTrue(hit("abc 123", "１２３"))
        assertTrue(hit("abc 123", "ＡＢＣ　１２３"))
    }

    @Test fun matches_halfwidth_query_against_fullwidth_text() {
        assertTrue(hit("ＡＢＣ　１２３", "abc"))
        assertTrue(hit("ＡＢＣ　１２３", "123"))
        assertTrue(hit("ＡＢＣ　１２３", "abc 123"))
        assertTrue(hit("订单号：ＡＢ１２", "ab12"))
        assertTrue(hit("订单号：ＡＢ１２", "号:ab"))
    }

    // ---------- 不能跨段命中 ----------

    @Test fun no_match_across_text_and_full_pinyin() {
        // 搜索串是 "ab快\u0001abkuai\u0001abk"；"快ab" 只有把原文末尾和全拼开头连起来才会出现
        assertFalse(hit("ab快", "快ab"))
        assertFalse(hit("ab快", "快abkuai"))
    }

    @Test fun no_match_across_full_pinyin_and_initials() {
        // "快递" -> "...\u0001kuaidi\u0001kd"；"dikd" 跨了全拼和首字母
        assertFalse(hit("快递", "dikd"))
        assertFalse(hit("快递", "ik"))
    }

    @Test fun separator_in_query_cannot_bridge_segments() {
        // 用户（或粘贴进来的内容）带了 \u0001 也不行：它在查询里被当成空格
        assertFalse(hit("ab快", "快\u0001xyz"))
        assertEquals(listOf("快", "ab"), Search.terms("快\u0001ab"))
        // 拆成两个词后各自命中是正常的“并且”，不是跨段
        assertTrue(hit("ab快", "快\u0001ab"))
    }

    @Test fun separator_in_text_cannot_fake_a_segment() {
        val s = Search.build("x\u0001kdtz")
        assertEquals("x kdtz\u0001\u0001", s)
        assertFalse(Search.matches(s, Search.terms("xkdtz")))
    }

    @Test fun no_match_across_text_and_note() {
        assertFalse(hit("abc", "abcdef", note = "def"))
        assertFalse(hit("快递", "kuaidibeizhu", note = "备注"))
        assertFalse(hit("快递", "kdbz", note = "备注"))
        assertTrue(hit("快递", "kd bz", note = "备注"))
    }

    // ---------- likePattern ----------

    @Test fun likePattern_wraps_and_escapes() {
        assertEquals("%abc%", Search.likePattern("abc"))
        assertEquals("%100\\%%", Search.likePattern("100%"))
        assertEquals("%a\\_b%", Search.likePattern("a_b"))
        assertEquals("%c:\\\\x%", Search.likePattern("c:\\x"))
    }

    // ---------- highlight（面板里标出命中的字） ----------

    private val fakeSeg: (String) -> List<String>? = { s ->
        if (s.none { it in dict }) null else s.map { dict[it] ?: it.toString() }
    }

    private fun hl(text: String, q: String, pinyin: Boolean = true): List<String> {
        val saved = Search.segments
        Search.segments = fakeSeg
        try { return Search.highlight(text, Search.terms(q), pinyin).map { text.substring(it.first, it.last + 1) } }
        finally { Search.segments = saved }
    }

    @Test fun highlightDirectIgnoresCase() = assertEquals(listOf("Review", "review"), hl("Review: please review", "REVIEW"))

    @Test fun highlightFullWidth() = assertEquals(listOf("ＡＢ"), hl("xＡＢy", "ab"))

    @Test fun highlightPinyinFull() = assertEquals(listOf("快递"), hl("【快递通知】您好", "kuaidi"))

    @Test fun highlightPinyinPartialSyllable() = assertEquals(listOf("快递"), hl("快递通知", "kuaid"))

    @Test fun highlightInitials() = assertEquals(listOf("通知"), hl("快递通知", "tz"))

    @Test fun highlightPinyinOffWhenDisabled() = assertEquals(emptyList<String>(), hl("快递通知", "kuaidi", pinyin = false))

    @Test fun highlightMergesAdjacent() = assertEquals(listOf("快递通知"), hl("快递通知", "快递 通知"))

    @Test fun highlightMixedAsciiAndHan() = assertEquals(listOf("wx群"), hl("加wx群", "wxqun"))

    @Test fun highlightNothing() = assertEquals(emptyList<String>(), hl("快递通知", "zzz"))}
