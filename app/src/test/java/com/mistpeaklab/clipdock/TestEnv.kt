package com.mistpeaklab.clipdock

import android.content.Context
import android.os.Looper
import com.mistpeaklab.clipdock.data.Db
import com.mistpeaklab.clipdock.data.DbThread
import com.mistpeaklab.clipdock.data.ItemRepo
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/** 测试共用的小工具 */
internal object TestEnv {
    fun ctx(): Context = RuntimeEnvironment.getApplication()

    /** 等数据库线程和主线程上排着的事情都做完 */
    fun settle() {
        repeat(4) {
            check(DbThread.await(30_000)) { "数据库线程 30 秒没做完" }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** 模拟进程重启：内存里的状态全部清掉，磁盘上的文件不动 */
    fun restart() {
        settle()
        ClipStore.resetForTest()
        Db.resetForTest()
        Features.invalidate()
    }

    /** 在数据库线程上执行 */
    fun <T> onDb(block: (ItemRepo) -> T): T = DbThread.call { block(ItemRepo.get(ctx())) }

    /** 让时间往前走一点，保证先后两次操作的时间戳不同 */
    fun tick(ms: Long = 5) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        Thread.sleep(2)
    }

    /** 把时钟拨到一个像样的日期（Robolectric 的时钟默认从 1970 年开始） */
    fun realisticClock() {
        if (System.currentTimeMillis() < 1_600_000_000_000L) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_760_000_000_000L - System.currentTimeMillis()))
        }
    }

    fun file(name: String) = File(ctx().filesDir, name)

    /** 按 0.5 版的格式写一个 history.json */
    fun writeLegacy(items: List<Legacy>) {
        val sb = StringBuilder("[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(it.id).append(",\"text\":").append(org.json.JSONObject.quote(it.text))
                .append(",\"time\":").append(it.time).append(",\"pinned\":").append(it.pinned)
                .append(",\"phrase\":").append(it.phrase).append('}')
        }
        file("history.json").writeText(sb.append(']').toString())
    }

    data class Legacy(val id: Long, val text: String, val time: Long, val pinned: Boolean = false, val phrase: Boolean = false)
}
