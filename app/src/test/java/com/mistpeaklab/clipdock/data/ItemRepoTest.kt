package com.mistpeaklab.clipdock.data

import com.mistpeaklab.clipdock.TestEnv
import com.mistpeaklab.clipdock.TestEnv.onDb
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

/** 数据库读写：用真实的 SQLite 跑，重点是“不丢、不重复、淘汰不算删除” */
@RunWith(RobolectricTestRunner::class)
class ItemRepoTest {
    private val day = 86_400_000L

    @Before fun setUp() { TestEnv.restart(); TestEnv.realisticClock() }
    @After fun tearDown() = TestEnv.restart()

    private fun now() = System.currentTimeMillis()

    private fun put(text: String, kind: Int = ItemKind.CLIP, pinned: Boolean = false): String {
        TestEnv.tick()
        val n = NewItem(Uuid7.next(now()), kind, text, now(), pinned = pinned)
        onDb { it.insert(n, Search.build(text)) }
        return n.id
    }

    private fun raw(sql: String) = onDb { Db.get(TestEnv.ctx()).writableDatabase.execSQL(sql) }
    private fun count(where: String): Int = onDb {
        Db.get(TestEnv.ctx()).readableDatabase.rawQuery("SELECT count(*) FROM items WHERE $where", null).use { c -> c.moveToFirst(); c.getInt(0) }
    }
    private fun seqOf(id: String): Long = onDb {
        Db.get(TestEnv.ctx()).readableDatabase.rawQuery("SELECT seq FROM items WHERE id='$id'", null).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    @Test fun insertThenLoadGivesBackEveryField() {
        val at = now()
        val n = NewItem(Uuid7.next(at), ItemKind.PHRASE, "你好 Hello", at, "com.a.b", null, pinned = true, sortKey = 42.0, note = "备注")
        onDb { it.insert(n, Search.build(n.text, n.note)) }
        val r = onDb { it.loadLive() }.single()
        assertEquals(n.id, r.id)
        assertEquals(ItemKind.PHRASE, r.kind)
        assertEquals("你好 Hello", r.text)
        assertEquals(Search.build(n.text, n.note), r.search)
        assertEquals(8, r.chars)
        assertEquals("com.a.b", r.sourcePkg)
        assertTrue(r.pinned)
        assertEquals(42.0, r.sortKey!!, 0.0)
        assertEquals(at, r.usedAt)
        assertEquals("备注", r.note)
        assertTrue(r.live)
    }

    @Test fun veryLongTextSurvivesIntact() {
        // 超过分段读取的门槛，夹着汉字、表情（代理对）和换行
        val text = buildString { while (length < 250_000) append("汉字😀abc\n第二行 ") }
        val id = put(text)
        assertEquals(text, onDb { it.get(id) }!!.text)
        assertEquals(text, onDb { it.loadLive() }.single().text)
    }

    @Test fun deleteGoesToTrashAndRestoreBringsItBack() {
        val id = put("要删的")
        val before = onDb { it.get(id) }!!
        onDb { it.softDelete(listOf(id)) }
        assertTrue(onDb { it.loadLive() }.isEmpty())
        val t = onDb { it.trash() }.single()
        assertEquals(id, t.id)
        assertFalse(t.evicted)
        assertEquals("要删的", t.preview)

        val r = onDb { it.restore(id, now()) }
        assertTrue(r is RestoreResult.Ok)
        val after = onDb { it.loadLive() }.single()
        assertEquals("要删的", after.text)
        assertNull(after.deletedAt)
        assertTrue("恢复是一次内容变化", after.updatedAt > before.updatedAt)
        assertTrue(onDb { it.trash() }.isEmpty())
    }

    @Test fun evictionIsLocalAndNotADeletion() {
        val id = put("被挤掉的")
        val before = onDb { it.get(id) }!!
        val seq = seqOf(id)
        onDb { it.evict(listOf(id), now()) }
        val evicted = onDb { it.get(id) }!!
        assertFalse(evicted.live)
        assertNull("淘汰不能写成删除", evicted.deletedAt)
        assertEquals("淘汰不算内容变化", before.updatedAt, evicted.updatedAt)
        assertEquals(seq, seqOf(id))
        assertTrue(onDb { it.trash() }.single().evicted)

        assertTrue(onDb { it.restore(id, now()) } is RestoreResult.Ok)
        val back = onDb { it.get(id) }!!
        assertTrue(back.live)
        assertEquals(before.updatedAt, back.updatedAt)
    }

    @Test fun restoringSomethingThatWasCopiedAgainDoesNotDuplicate() {
        val deleted = put("同样的内容")
        onDb { it.softDelete(listOf(deleted)) }
        val fresh = put("同样的内容")
        assertEquals(RestoreResult.Duplicate, onDb { it.restore(deleted, now()) })
        assertEquals(listOf(fresh), onDb { it.loadLive() }.map { it.id })
        val shell = onDb { it.get(deleted) }!!
        assertTrue("删除过的留一行空壳", shell.wiped && shell.text.isEmpty() && shell.deletedAt != null)

        val evicted = put("另一段")
        onDb { it.evict(listOf(evicted), now()) }
        put("另一段")
        assertEquals(RestoreResult.Duplicate, onDb { it.restore(evicted, now()) })
        assertNull("只是被淘汰的整行去掉，不留删除标记", onDb { it.get(evicted) })
        assertEquals(RestoreResult.Gone, onDb { it.restore(evicted, now()) })
    }

    @Test fun purgeWipesDeletedRowsDropsEvictedRowsAndLeavesLiveRowsAlone() {
        val deleted = put("a"); val evicted = put("b"); val live = put("c")
        onDb { it.softDelete(listOf(deleted)); it.evict(listOf(evicted), now()) }
        onDb { it.purge(listOf(deleted, evicted, live)) }
        val shell = onDb { it.get(deleted) }!!
        assertTrue(shell.wiped && shell.text.isEmpty() && shell.search.isEmpty())
        assertNull(onDb { it.get(evicted) })
        assertEquals("c", onDb { it.get(live) }!!.text)
        assertTrue(onDb { it.trash() }.isEmpty())
        assertEquals(RestoreResult.Gone, onDb { it.restore(deleted, now()) })
    }

    @Test fun trashIsCleanedAfterThirtyDays() {
        val oldDeleted = put("old-deleted"); val oldEvicted = put("old-evicted")
        val newDeleted = put("new-deleted"); val newEvicted = put("new-evicted")
        val ancient = put("ancient")
        onDb { it.softDelete(listOf(oldDeleted, newDeleted, ancient)); it.evict(listOf(oldEvicted, newEvicted), now()) }
        raw("UPDATE items SET deleted_at=${Hlc.pack(now() - 31 * day)} WHERE id='$oldDeleted'")
        raw("UPDATE items SET evicted_at=${now() - 31 * day} WHERE id='$oldEvicted'")
        raw("UPDATE items SET deleted_at=${Hlc.pack(now() - 300 * day)} WHERE id='$ancient'")
        onDb { it.cleanExpired(now()) }

        assertTrue("到期的删除记录清空正文、留空壳", onDb { it.get(oldDeleted) }!!.let { it.wiped && it.text.isEmpty() })
        assertNull("到期的淘汰记录整行去掉", onDb { it.get(oldEvicted) })
        assertNull("放了很久的空壳也去掉", onDb { it.get(ancient) })
        assertEquals(setOf(newDeleted, newEvicted), onDb { it.trash() }.map { it.id }.toSet())
        assertEquals("new-deleted", onDb { it.get(newDeleted) }!!.text)
    }

    @Test fun touchOnlyChangesLocalFields() {
        val id = put("x")
        val before = onDb { it.get(id) }!!
        val seq = seqOf(id)
        onDb { it.touch(id, before.usedAt + 1000, "com.wechat") }
        val after = onDb { it.get(id) }!!
        assertEquals(before.usedAt + 1000, after.usedAt)
        assertEquals(1, after.useCount)
        assertEquals("com.wechat", after.sourcePkg)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(seq, seqOf(id))
    }

    @Test fun contentChangesBumpClockAndSeq() {
        val id = put("x")
        val before = onDb { it.get(id) }!!
        val seq = seqOf(id)
        onDb { it.setText(id, "y", Search.build("y")) }
        val after = onDb { it.get(id) }!!
        assertEquals("y", after.text)
        assertEquals(1, after.chars)
        assertTrue(after.updatedAt > before.updatedAt)
        assertTrue(seqOf(id) > seq)
    }

    @Test fun importingLegacyTwiceAddsNothingTheSecondTime() {
        val items = (1..20).map {
            RawItem(Uuid7.at(1_700_000_000_000L + it), if (it % 5 == 0) ItemKind.PHRASE else ItemKind.CLIP, "条目 $it",
                pinned = it == 3, sortKey = if (it % 5 == 0) it.toDouble() else null,
                createdAt = Hlc.pack(1_700_000_000_000L + it), updatedAt = Hlc.pack(1_700_000_000_000L + it), usedAt = 1_700_000_000_000L + it)
        }
        assertEquals(20, onDb { it.importLegacy(items, markMigrated = true) }.size)
        // 第二次即使换了一批新的 ID（重新解析旧文件就会这样），也不该多出东西
        val again = items.map { it.copy(id = Uuid7.at(it.usedAt)) }
        assertEquals(0, onDb { it.importLegacy(again, markMigrated = true) }.size)
        assertEquals(20, count("1=1"))
        assertNotNull(onDb { it.getMeta(Db.META_MIGRATED_FROM_JSON) })
    }

    @Test fun importCarriesPinOverToAnExistingTwin() {
        val id = put("已有的")
        val legacy = RawItem(Uuid7.at(1_700_000_000_000L), ItemKind.CLIP, "已有的", pinned = true, sortKey = null,
            createdAt = Hlc.pack(1_700_000_000_000L), updatedAt = Hlc.pack(1_700_000_000_000L), usedAt = 1_700_000_000_000L)
        assertTrue(onDb { it.importLegacy(listOf(legacy), markMigrated = false) }.isEmpty())
        assertTrue(onDb { it.get(id) }!!.pinned)
        assertEquals(1, count("1=1"))
    }

    @Test fun clockAndSeqContinueAfterRestart() {
        val a = put("a")
        val before = onDb { it.get(a) }!!
        val seq = seqOf(a)
        TestEnv.restart()
        val b = put("b")
        assertTrue(onDb { it.get(b) }!!.updatedAt > before.updatedAt)
        assertTrue(seqOf(b) > seq)
    }

    @Test fun schemaHasTheColumnsLaterVersionsNeed() {
        val cols = onDb {
            Db.get(TestEnv.ctx()).readableDatabase.rawQuery("PRAGMA table_info(items)", null).use { c ->
                val out = HashSet<String>(); while (c.moveToNext()) out += c.getString(1); out
            }
        }
        for (c in listOf("id", "kind", "text", "cipher", "hash", "search", "preview", "chars", "source_pkg", "group_id", "pinned",
            "hidden", "expire_at", "sort_key", "created_at", "updated_at", "used_at", "use_count", "evicted_at", "deleted_at",
            "device_id", "note", "title", "source_title", "source_author", "source_url", "cipher_scheme", "purged", "wiped", "seq", "extra")) {
            assertTrue("缺少列 $c", c in cols)
        }
    }
}
