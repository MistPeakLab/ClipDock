package com.mistpeaklab.clipdock

import com.mistpeaklab.clipdock.TestEnv.Legacy
import com.mistpeaklab.clipdock.TestEnv.onDb
import com.mistpeaklab.clipdock.TestEnv.settle
import com.mistpeaklab.clipdock.TestEnv.tick
import com.mistpeaklab.clipdock.data.Db
import com.mistpeaklab.clipdock.data.LegacyMigrator
import com.mistpeaklab.clipdock.data.RestoreResult
import com.mistpeaklab.clipdock.util.Search
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

/** 界面用的那一层：从 0.5 升级、读库期间的新内容、回收站、条数上限、重启后是否还在 */
@RunWith(RobolectricTestRunner::class)
class ClipStoreTest {
    private val ctx get() = TestEnv.ctx()
    private val base = 1_759_000_000_000L

    @Before fun setUp() { TestEnv.restart(); TestEnv.realisticClock() }
    @After fun tearDown() = TestEnv.restart()

    private fun start() { ClipStore.init(ctx); settle() }
    private fun restart() { TestEnv.restart(); start() }
    private fun add(text: String, pkg: String? = null): Boolean { tick(); return ClipStore.add(text, Prefs(ctx).maxItems, sourcePkg = pkg) }
    private fun texts() = ClipStore.history().map { it.text }
    private fun trash() = TestEnv.onDb { it.trash() }

    /** 一份像样的旧数据：30 条记录（3 条置顶）、5 条常用语 */
    private fun legacy(): List<Legacy> =
        (1..30).map { Legacy(base + it * 1000, "记录 $it\n第二行", base + it * 1000 + (it % 7) * 50_000, pinned = it % 10 == 0) } +
            (1..5).map { Legacy(base + 100_000 + it, "常用语 $it", base + 200_000 + it * 10, phrase = true) }

    /** 0.5 版显示的顺序：置顶在前，其余按时间从新到旧 */
    private fun oldOrder(l: List<Legacy>) = l.sortedWith(compareByDescending<Legacy> { it.pinned }.thenByDescending { it.time })

    @Test fun upgradeFromV05KeepsEverythingInTheSameOrder() {
        val old = legacy()
        TestEnv.writeLegacy(old)
        start()
        val expectHistory = oldOrder(old.filter { !it.phrase })
        val expectPhrases = oldOrder(old.filter { it.phrase })

        fun check() {
            assertTrue(ClipStore.loaded.value)
            assertNull(ClipStore.notice.value)
            assertEquals(expectHistory.map { it.text }, texts())
            assertEquals(expectHistory.map { it.pinned }, ClipStore.history().map { it.pinned })
            assertEquals(expectHistory.map { it.time }, ClipStore.history().map { it.time })
            assertEquals(expectPhrases.map { it.text }, ClipStore.phrases().map { it.text })
        }
        check()
        assertFalse("旧文件改名留作备份", TestEnv.file("history.json").exists())
        assertTrue(TestEnv.file("history.json.v05.bak").exists())

        restart()          // 第二次启动：从数据库读，不再迁移
        check()
        assertEquals(35, onDb { it.loadLive() }.size)
    }

    @Test fun contentCopiedWhileStillLoadingIsKeptExactlyOnce() {
        TestEnv.writeLegacy(legacy())
        ClipStore.init(ctx)
        // 还没读完就来了两条：一条是旧记录里已有的，一条是新的
        tick()
        assertTrue(ClipStore.add("记录 5\n第二行", 200, sourcePkg = "com.a"))
        tick()
        assertTrue(ClipStore.add("全新的", 200))
        assertEquals("读库期间已经把它当作当前剪贴板", "全新的", ClipStore.current.value)
        settle()

        assertEquals(1, texts().count { it == "记录 5\n第二行" })
        assertEquals(1, texts().count { it == "全新的" })
        assertEquals(31, texts().size)
        // 置顶的 3 条在最前，接着是刚复制的两条
        assertEquals(listOf("全新的", "记录 5\n第二行"), texts().drop(3).take(2))
        restart()
        assertEquals(31, texts().size)
        assertEquals(listOf("全新的", "记录 5\n第二行"), texts().drop(3).take(2))
    }

    @Test fun awaitLoadedMakesItemsAvailableImmediately() {
        TestEnv.writeLegacy(legacy())
        ClipStore.init(ctx)
        assertTrue(ClipStore.awaitLoaded())
        assertEquals(30, texts().size)
        // 前 3 条是置顶的，第 4 条是最新的一条普通记录：再存一次应该知道它已经在最前面了
        assertFalse(ClipStore.add(texts()[3], 200, fromClipboard = false))
        assertTrue(ClipStore.flush())
    }

    @Test fun newItemsSurviveRestart() {
        start()
        add("第一条", "com.tencent.mm"); add("第二条"); add("第一条", "com.android.notes")
        assertEquals(listOf("第一条", "第二条"), texts())
        assertEquals("重复复制时更新来源", "com.android.notes", ClipStore.history().first().sourcePkg)
        restart()
        assertEquals(listOf("第一条", "第二条"), texts())
        assertEquals("com.android.notes", ClipStore.history().first().sourcePkg)
    }

    @Test fun textWrittenBackByTheAppIsNotRecorded() {
        start()
        ClipStore.markPicked("自己贴出去的")
        assertFalse(ClipStore.add("自己贴出去的", 200))
        assertTrue(texts().isEmpty())
        assertTrue(add("别处复制的"))
    }

    @Test fun deleteGoesToTrashAndCanBeRestored() {
        start()
        add("a"); add("b"); add("c")
        val b = ClipStore.history().first { it.text == "b" }
        ClipStore.delete(b.id)
        settle()
        assertEquals(listOf("c", "a"), texts())
        assertEquals(listOf("b"), trash().map { it.preview })

        var result: RestoreResult? = null
        tick()
        ClipStore.restore(b.id) { result = it }
        settle()
        assertTrue(result is RestoreResult.Ok)
        assertEquals(listOf("b", "c", "a"), texts())
        restart()
        assertEquals(listOf("b", "c", "a"), texts())
        assertTrue(trash().isEmpty())
    }

    @Test fun withTrashTurnedOffDeletedItemsAreGoneForGood() {
        start()
        Prefs(ctx).setFeature(Feature.TRASH, false)
        add("a")
        ClipStore.delete(ClipStore.history().single().id)
        settle()
        assertTrue(texts().isEmpty())
        assertTrue(trash().isEmpty())
    }

    @Test fun limitEvictsTheOldestButNeverPinnedOrPhrases() {
        Prefs(ctx).maxItems = 3
        start()
        add("1"); add("2")
        ClipStore.togglePin(ClipStore.history().first { it.text == "1" }.id)
        ClipStore.addPhrase("常用语")
        add("3"); add("4"); add("5"); add("6")
        settle()
        assertEquals(listOf("1", "6", "5", "4"), texts())
        assertEquals(listOf("常用语"), ClipStore.phrases().map { it.text })
        val t = trash()
        assertEquals(setOf("2", "3"), t.map { it.preview }.toSet())
        assertTrue("挤掉的是淘汰，不是删除", t.all { it.evicted })
        restart()
        assertEquals(listOf("1", "6", "5", "4"), texts())
    }

    @Test fun loweringTheLimitMovesTheOverflowToTrash() {
        start()
        (1..10).forEach { add("n$it") }
        ClipStore.applyLimit(4)
        settle()
        assertEquals(listOf("n10", "n9", "n8", "n7"), texts())
        assertEquals(6, trash().size)
    }

    @Test fun clearAllKeepsPhrases() {
        start()
        add("a"); add("b"); ClipStore.addPhrase("留着")
        ClipStore.togglePin(ClipStore.history().first().id)
        ClipStore.clearAll()
        settle()
        assertTrue(texts().isEmpty())
        assertEquals(listOf("留着"), ClipStore.phrases().map { it.text })
        assertEquals(2, trash().size)
        restart()
        assertTrue(texts().isEmpty())
        assertEquals(listOf("留着"), ClipStore.phrases().map { it.text })
    }

    @Test fun phraseOrderEditsAndNotesSurviveRestart() {
        start()
        tick(); ClipStore.addPhrase("甲"); tick(); ClipStore.addPhrase("乙"); tick(); ClipStore.addPhrase("丙")
        assertFalse("同样的常用语不重复加", ClipStore.addPhrase("乙"))
        assertEquals(listOf("丙", "乙", "甲"), ClipStore.phrases().map { it.text })
        ClipStore.movePhrase(ClipStore.phrases().first().id, 1)
        ClipStore.updateText(ClipStore.phrases().last().id, "甲改")
        add("一段记录")
        val rec = ClipStore.history().single()
        ClipStore.setNote(rec.id, "  公司地址  ")
        ClipStore.togglePin(rec.id)
        settle()

        fun check() {
            assertEquals(listOf("乙", "丙", "甲改"), ClipStore.phrases().map { it.text })
            val r = ClipStore.history().single()
            assertEquals("公司地址", r.note)
            assertTrue(r.pinned)
            assertTrue("备注也能搜到", r.matches(Search.terms("公司"), pinyin = false))
            assertTrue(ClipStore.phrases().last().matches(Search.terms("甲改"), pinyin = false))
        }
        check()
        restart()
        check()
    }

    @Test fun corruptLegacyFileIsKeptAndReported() {
        TestEnv.file("history.json").writeText("[{\"id\":1,\"text\":\"坏掉的")
        start()
        assertTrue(texts().isEmpty())
        assertNotNull(ClipStore.notice.value)
        assertTrue("坏文件也要留着", TestEnv.file("history.json.v05.bak").exists())
        add("之后照常能用")
        restart()
        assertEquals(listOf("之后照常能用"), texts())
        assertNull("不再反复提示", ClipStore.notice.value)
    }

    @Test fun corruptDatabaseIsSetAsideAndOldRecordsComeBack() {
        TestEnv.writeLegacy(legacy())
        start()
        add("升级后新加的")
        TestEnv.restart()

        val db = ctx.getDatabasePath(Db.NAME)
        listOf("-wal", "-shm").forEach { java.io.File(db.path + it).delete() }
        db.writeText("这已经不是一个数据库文件了".repeat(200))
        start()

        assertEquals("升级前的记录从备份里找回来", 30, texts().size)
        assertEquals(5, ClipStore.phrases().size)
        assertNotNull(ClipStore.notice.value)
        assertTrue("坏掉的数据库文件留着", db.parentFile!!.listFiles()!!.any { it.name.startsWith(Db.NAME + ".corrupt-") })
        add("还能继续用")
        restart()
        assertEquals(31, texts().size)
    }

    @Test fun reimportBringsBackOnlyWhatIsMissing() {
        TestEnv.writeLegacy(legacy())
        start()
        assertTrue(ClipStore.hasLegacyBackup())
        val gone = ClipStore.history().filter { !it.pinned }.take(4).map { it.id }.toSet()
        ClipStore.deleteAll(gone)
        settle()
        assertEquals(26, texts().size)
        var n = -2
        ClipStore.reimportLegacy { n = it }
        settle()
        assertEquals(4, n)
        assertEquals(30, texts().size)
        assertEquals(30, texts().toSet().size)
        ClipStore.reimportLegacy { n = it }
        settle()
        assertEquals(0, n)
        assertEquals(30, texts().size)
    }

    @Test fun overflowRules() {
        fun item(id: String, len: Int, time: Long, pinned: Boolean = false, phrase: Boolean = false) =
            ClipItem(id, "x".repeat(len), time, pinned, phrase)
        // 条数：最新的 2 条留下；置顶和常用语不占名额
        val a = listOf(item("p", 1, 9, pinned = true), item("n3", 1, 3), item("n2", 1, 2), item("ph", 1, 2, phrase = true), item("n1", 1, 1))
        assertEquals(setOf("n1"), ClipStore.overflow(a, 2))
        assertTrue(ClipStore.overflow(a, Int.MAX_VALUE).isEmpty())

        // 长文：合计超过预算时，更旧的长文淘汰，短的不受影响；最新的一篇无论多长都留着
        val big = "y".repeat(4_000_000)
        fun long(id: String, time: Long) = ClipItem(id, big, time)
        val b = listOf(long("b4", 40), item("s", 10, 35), long("b3", 30), long("b2", 20), long("b1", 10), item("old", 10, 1))
        assertEquals(setOf("b2", "b1"), ClipStore.overflow(b, Int.MAX_VALUE))
        val huge = ClipItem("huge", "z".repeat(1_000_000), 99)
        assertFalse("huge" in ClipStore.overflow(listOf(huge) + b, Int.MAX_VALUE))
    }

    @Test fun legacyParserSkipsBlanksAndDuplicates() {
        val parsed = LegacyMigrator.parse(
            """[{"id":1700000000001,"text":"a","time":1700000000001,"pinned":true,"phrase":false},
               {"id":1700000000002,"text":"  ","time":1700000000002},
               {"id":1700000000003,"text":"a","time":1700000000003},
               {"id":7,"text":"p","time":1700000000009,"phrase":true},
               "不是对象", {"text":"没有时间"}]""",
        )
        assertEquals(listOf("a", "p", "没有时间"), parsed.map { it.text })
        assertTrue(parsed[0].pinned)
        assertEquals(1700000000001, parsed[0].usedAt)
        assertEquals(1700000000009.0, parsed[1].sortKey!!, 0.0)
        assertEquals(3, parsed.map { it.id }.toSet().size)
    }
}
