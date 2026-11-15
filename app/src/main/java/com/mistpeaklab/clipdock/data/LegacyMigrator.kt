package com.mistpeaklab.clipdock.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import java.io.File

/**
 * 把 v0.5 及以前的 history.json 搬进数据库，只做一次。
 *
 * 原则是绝不丢数据：
 * - 全部记录在一个事务里写入，并在同一个事务里打上“已迁移”标记，要么都成功、要么都没发生；
 * - 成功以后旧文件只改名为 history.json.v05.bak，永远不删；
 * - 读文件、写数据库出错（比如磁盘满）都原样留着旧文件，下次启动再试；
 * - 导入时按内容去重，所以哪怕重复执行，也不会出现重复记录。
 */
object LegacyMigrator {
    private const val FILE = "history.json"
    private const val BACKUP = "history.json.v05.bak"

    /** [run] 的结果 */
    sealed interface Result {
        /** 不需要迁移（没有旧文件，或者早就迁移过了） */
        data object Nothing : Result

        /** 导入完成，新增了 [added] 条 */
        data class Imported(val added: Int) : Result

        /** 旧文件的内容认不出来（旧版本自己也读不了这样的文件）。文件已留作备份，不再重试 */
        data object Unreadable : Result
    }

    /** 只能在数据库线程上调用。读文件或写库失败会抛异常，此时什么都没改，下次启动可以再试 */
    fun run(ctx: Context, repo: ItemRepo): Result {
        val f = File(ctx.filesDir, FILE)
        val bak = File(ctx.filesDir, BACKUP)
        if (repo.getMeta(Db.META_MIGRATED_FROM_JSON) != null) {
            // 上次导入成功了但没来得及改名
            if (f.exists() && !bak.exists()) f.renameTo(bak)
            return Result.Nothing
        }
        // 没迁移过：正常情况读 history.json。
        // 只剩备份而数据库是新的，说明数据库坏掉后重建了，这时把备份里的旧记录找回来
        val src = when {
            f.exists() -> f
            bak.exists() -> bak
            else -> return Result.Nothing
        }
        val items = try {
            parse(src.readText())
        } catch (e: JSONException) {
            repo.putMeta(Db.META_MIGRATED_FROM_JSON, "unreadable")
            keep(src, f, bak)
            return Result.Unreadable
        }
        val added = repo.importLegacy(items, markMigrated = true).size
        keep(src, f, bak)
        return Result.Imported(added)
    }

    /** 把旧文件改名成备份。已经有备份了就不覆盖它：第一份才是迁移那一刻的原件 */
    private fun keep(src: File, f: File, bak: File) {
        if (src == f && !bak.exists()) f.renameTo(bak)
    }

    /** 本机还留着旧版的记录文件（迁移时的备份） */
    fun hasBackup(ctx: Context): Boolean = File(ctx.filesDir, BACKUP).exists() || File(ctx.filesDir, FILE).exists()

    /**
     * 把备份里的旧版记录再导入一次，列表里已有的不会重复。
     * 返回新增那些行的 ID；没有备份返回 null。只能在数据库线程上调用。
     */
    fun reimport(ctx: Context, repo: ItemRepo): List<String>? {
        val src = File(ctx.filesDir, BACKUP).takeIf { it.exists() }
            ?: File(ctx.filesDir, FILE).takeIf { it.exists() }
            ?: return null
        return repo.importLegacy(parse(src.readText()), markMigrated = true)
    }

    /**
     * 旧格式：[{id:数字, text, time, pinned, phrase}]。
     * 历史记录的 time 是复制时间；常用语的 time 是排序键（越大越靠前）。
     */
    fun parse(json: String): List<RawItem> {
        val arr = JSONArray(json)
        val out = ArrayList<RawItem>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = ItemText.limit(o.optString("text"))
            if (text.isBlank()) continue
            val phrase = o.optBoolean("phrase")
            // 旧数据里同一类不会有重复的文字；以防万一，重复的只留第一条
            if (!seen.add((if (phrase) "p" else "c") + text)) continue
            val time = o.optLong("time")
            // 旧 id 就是创建时的毫秒时间，用它生成新 ID，顺序不变；不像时间的就用 time
            val born = o.optLong("id").takeIf { it in MIN_SANE_MS..Uuid7.MAX_MILLIS } ?: time.coerceIn(0, Uuid7.MAX_MILLIS)
            val stamp = Hlc.pack(born, i and 0xFFFF)
            out += RawItem(
                id = Uuid7.at(born),
                kind = if (phrase) ItemKind.PHRASE else ItemKind.CLIP,
                text = text,
                pinned = o.optBoolean("pinned"),
                sortKey = if (phrase) time.toDouble() else null,
                createdAt = stamp,
                updatedAt = stamp,
                usedAt = if (phrase) born else time,
            )
        }
        return out
    }

    /** 2001 年。比它小的数字不可能是这个应用产生的时间戳 */
    private const val MIN_SANE_MS = 1_000_000_000_000L
}
