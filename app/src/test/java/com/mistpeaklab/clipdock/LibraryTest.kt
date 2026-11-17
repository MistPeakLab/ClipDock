package com.mistpeaklab.clipdock

import com.mistpeaklab.clipdock.TestEnv.onDb
import com.mistpeaklab.clipdock.TestEnv.settle
import com.mistpeaklab.clipdock.TestEnv.tick
import com.mistpeaklab.clipdock.data.Backup
import com.mistpeaklab.clipdock.data.ItemKind
import com.mistpeaklab.clipdock.util.Chunks
import com.mistpeaklab.clipdock.util.TextDecode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 文库：和记录分开、重启后还在、长文、文集、搜索、回收站、导出导入、编码识别、分块 */
@RunWith(RobolectricTestRunner::class)
class LibraryTest {
    private val ctx get() = TestEnv.ctx()

    @Before fun setUp() { TestEnv.restart(); TestEnv.realisticClock() }
    @After fun tearDown() = TestEnv.restart()

    private fun start() { ClipStore.init(ctx); LibraryStore.init(ctx); settle() }
    private fun restart() { TestEnv.restart(); start() }

    @Test fun notesAreSeparateFromHistoryAndSurviveRestart() {
        start()
        ClipStore.add("一条普通记录", Prefs(ctx).maxItems); tick()
        val id = LibraryStore.add("第一行是标题\n正文第二行", sourceTitle = "红楼梦", sourceAuthor = "曹雪芹")
        assertNotNull(id)
        settle()
        restart()
        assertEquals(listOf("一条普通记录"), ClipStore.history().map { it.text })
        val n = LibraryStore.notes.value.single()
        assertEquals("第一行是标题", n.title)
        assertEquals("红楼梦", n.sourceTitle)
        assertEquals("曹雪芹", n.sourceAuthor)
        assertEquals("第一行是标题\n正文第二行", LibraryStore.text(n.id))
        // 搜索串里有书名
        assertTrue(n.search.contains("红楼梦"))
    }

    @Test fun longNoteIsStoredWholeAndReadBackInChunks() {
        start()
        val para = "这是一段很长很长的文字。".repeat(100) + "\n"
        val text = para.repeat(400).trimEnd()   // 约 48 万字
        val id = LibraryStore.add(text, "长篇")!!
        settle(); restart()
        assertEquals(text, LibraryStore.text(id))
        assertEquals(text.length, LibraryStore.notes.value.single().chars)
        // 记录列表里不会冒出来
        assertTrue(ClipStore.items.value.isEmpty())
    }

    @Test fun shelvesAreSeparateFromGroups() {
        start()
        val g = ClipStore.addGroup("工作")!!
        val s = LibraryStore.addShelf("读书笔记")!!
        val id = LibraryStore.add("摘抄", shelfId = s)!!
        settle(); restart()
        assertEquals(listOf(g), ClipStore.groups.value.map { it.id })
        assertEquals(listOf(s), LibraryStore.shelves.value.map { it.id })
        assertEquals(s, LibraryStore.find(id)!!.shelfId)
        LibraryStore.deleteShelf(s)
        settle(); restart()
        assertNull(LibraryStore.find(id)!!.shelfId)
        assertTrue(LibraryStore.shelves.value.isEmpty())
    }

    @Test fun updateEditsTitleSourceAndText() {
        start()
        val id = LibraryStore.add("旧正文", "旧标题")!!
        tick()
        LibraryStore.update(id, "新标题", "新正文\n第二段", "书", "作者", "https://example.com")
        settle(); restart()
        val n = LibraryStore.find(id)!!
        assertEquals("新标题", n.title)
        assertEquals("书", n.sourceTitle)
        assertEquals("https://example.com", n.sourceUrl)
        assertEquals("新正文\n第二段", LibraryStore.text(id))
    }

    @Test fun deleteGoesToTrashAndRestoreBringsItBack() {
        start()
        val id = LibraryStore.add("要删的一篇")!!
        settle()
        LibraryStore.delete(listOf(id))
        settle()
        assertTrue(LibraryStore.notes.value.isEmpty())
        val trash = onDb { it.trash() }
        assertEquals(ItemKind.LIBRARY, trash.single().kind)
        var done = false
        ClipStore.restore(id) { done = true }
        settle()
        assertTrue(done)
        assertEquals(listOf(id), LibraryStore.notes.value.map { it.id })
        // 恢复的篇章不会跑进记录里
        assertTrue(ClipStore.history().isEmpty())
    }

    @Test fun searchFindsTextWithSnippet() {
        start()
        LibraryStore.add("开头\n" + "无关的话。".repeat(50) + "春江潮水连海平，海上明月共潮生。" + "后面的话。".repeat(50), "春江花月夜")
        LibraryStore.add("另一篇，没有那句", "别的")
        settle()
        var hits: List<LibraryStore.Hit>? = null
        LibraryStore.search("明月") { hits = it }
        settle()
        val h = hits!!.single()
        assertTrue(h.offset > 0)
        assertTrue(h.snippet.contains("海上明月"))
        assertEquals("明月", h.snippet.substring(h.mark!!.first, h.mark!!.last + 1))
        // 标题命中也算
        hits = null
        LibraryStore.search("别的") { hits = it }
        settle()
        assertEquals(1, hits!!.size)
    }

    @Test fun exportAndImportKeepNotes() {
        start()
        val s = LibraryStore.addShelf("诗")!!
        val id = LibraryStore.add("床前明月光", "静夜思", s, "唐诗三百首", "李白")!!
        ClipStore.add("普通记录", Prefs(ctx).maxItems)
        settle()
        val out = ByteArrayOutputStream()
        onDb { Backup.export(it, out, Backup.Options()) }
        // 换一台“新设备”：清空数据库
        TestEnv.restart()
        ctx.deleteDatabase("clipdock.db")
        start()
        assertTrue(LibraryStore.notes.value.isEmpty())
        onDb { Backup.import(it, ByteArrayInputStream(out.toByteArray()), null, false) { null } }
        LibraryStore.reload(); settle()
        val n = LibraryStore.find(id)!!
        assertEquals("静夜思", n.title)
        assertEquals("李白", n.sourceAuthor)
        assertEquals(s, n.shelfId)
        assertEquals("床前明月光", LibraryStore.text(id))
        assertEquals(listOf(s), LibraryStore.shelves.value.map { it.id })
    }

    @Test fun decodeUtf8AndGbk() {
        val s = "中文内容，含标点。Hello"
        assertEquals(s, TextDecode.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TextDecode.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TextDecode.decode(s.toByteArray(charset("GBK"))))
        assertEquals(s, TextDecode.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun chunksCoverTheWholeTextAndPreferLineBreaks() {
        val text = (1..300).joinToString("\n") { "第 $it 行：" + "字".repeat(it % 37) }
        val cs = Chunks.split(text, 400)
        assertEquals(text, cs.joinToString("") { text.substring(it.first, it.last + 1) })
        assertTrue(cs.all { it.last - it.first + 1 <= 400 })
        // 除最后一块，都在换行后切
        assertTrue(cs.dropLast(1).all { text[it.last] == '\n' })
        // 一整段没有换行：硬切，不切开表情
        val emoji = "😀".repeat(500)
        val ce = Chunks.split(emoji, 101)
        assertEquals(emoji, ce.joinToString("") { emoji.substring(it.first, it.last + 1) })
        assertFalse(ce.any { Character.isLowSurrogate(emoji[it.first]) })
    }

    @Test fun paragraphsSplitLongLines() {
        val t = "短的一段\n" + "长".repeat(4000) + "\n\n最后"
        val ps = paragraphs(t)
        assertEquals("短的一段", t.substring(ps[0].first, ps[0].last + 1))
        assertTrue(ps.size >= 5)
        assertEquals("最后", t.substring(ps.last().first, ps.last().last + 1))
    }
}
