package com.mistpeaklab.clipdock.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 数据库：建表、版本升级、WAL。进程内只有一个实例（[get]）。
 *
 * 表结构是多端共用的数据模型（docs/protocol/data-model.md），改动要非常小心：
 * 只允许“加列 / 加表 / 加索引”，并且在 [migrate] 里写对应的升级步骤，同时把 [VERSION] 加一。
 *
 * 除了 v0.6 用到的列，items 和 groups 里还预先放了几列后面版本才会用到的（标题、出处、
 * 加密方案、变更序号等），这样 v0.7～v0.9 不需要再做一次表结构迁移。
 */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION, KeepCorruptFile) {

    init {
        // WAL：读不挡写，而且每次提交只追加日志，比默认的回滚日志模式写得快、掉电更不容易坏
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meta (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        // 列的顺序是有意的：又长又少用的（正文、搜索串、密文）放在最后。
        // SQLite 读一行里靠后的列要先跳过前面的列，把大块头放最后，只查状态和预览时就不用碰它们。
        db.execSQL(
            """
            CREATE TABLE items (
              id            TEXT PRIMARY KEY,
              kind          INTEGER NOT NULL,
              hash          TEXT NOT NULL,
              chars         INTEGER NOT NULL,
              preview       TEXT NOT NULL DEFAULT '',
              source_pkg    TEXT,
              group_id      TEXT,
              pinned        INTEGER NOT NULL DEFAULT 0,
              hidden        INTEGER NOT NULL DEFAULT 0,
              expire_at     INTEGER,
              sort_key      REAL,
              created_at    INTEGER NOT NULL,
              updated_at    INTEGER NOT NULL,
              used_at       INTEGER NOT NULL,
              use_count     INTEGER NOT NULL DEFAULT 0,
              evicted_at    INTEGER,
              deleted_at    INTEGER,
              wiped         INTEGER NOT NULL DEFAULT 0,
              purged        INTEGER NOT NULL DEFAULT 0,
              device_id     TEXT NOT NULL,
              seq           INTEGER NOT NULL DEFAULT 0,
              cipher_scheme INTEGER NOT NULL DEFAULT 1,
              note          TEXT,
              title         TEXT,
              source_title  TEXT,
              source_author TEXT,
              source_url    TEXT,
              extra         TEXT,
              search        TEXT NOT NULL DEFAULT '',
              cipher        BLOB,
              text          TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        // 目前只建用得到的索引：按内容找重复、找到期的。安卓端是把列表整个读进内存的，
        // 用不上“按类型 + 时间”的索引；以后需要时再加（加索引只是一步很简单的升级）。
        db.execSQL("CREATE INDEX idx_items_hash   ON items(hash)")
        db.execSQL("CREATE INDEX idx_items_expire ON items(expire_at) WHERE expire_at IS NOT NULL")
        db.execSQL(
            """
            CREATE TABLE groups (
              id         TEXT PRIMARY KEY,
              name       TEXT NOT NULL,
              color      INTEGER,
              sort_key   REAL NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              device_id  TEXT NOT NULL,
              scope      INTEGER NOT NULL DEFAULT 0,
              seq        INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE app_rules (
              pkg   TEXT PRIMARY KEY,
              rule  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        putMeta(db, META_SCHEMA_VERSION, VERSION.toString())
        putMeta(db, META_DEVICE_ID, UUID.randomUUID().toString())
    }

    /**
     * 逐个版本往上升：从 3 升到 5 就依次执行“升到 4”“升到 5”两步。
     * 框架把整个 onUpgrade 包在一个事务里，中途出错会全部回滚，数据库停在旧版本，下次启动再试。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (target in oldVersion + 1..newVersion) migrate(db, target)
        putMeta(db, META_SCHEMA_VERSION, newVersion.toString())
    }

    /**
     * 把数据库从 target-1 升到 target。以后加版本时在这里加分支，例如：
     *
     *     2 -> db.execSQL("ALTER TABLE items ADD COLUMN foo TEXT")
     *
     * 没写步骤就抛错：宁可打不开，也不能带着不匹配的表结构继续写。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun migrate(db: SQLiteDatabase, target: Int) {
        when (target) {
            else -> throw IllegalStateException("数据库缺少升级到版本 $target 的步骤")
        }
    }

    // 不重写 onDowngrade：默认行为是抛异常、数据库原样保留。
    // 装回旧版本时宁可报错，也不能像常见写法那样“删表重建”，那会把用户的记录清空。

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues(2)
        cv.put("key", key)
        cv.put("value", value)
        db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 数据库文件损坏时，系统默认的做法是把文件直接删掉再建一个空的——用户的记录就这样没了。
     * 这里改成改名留着（clipdock.db.corrupt-时间），说不定还能用工具救回一部分。
     */
    private object KeepCorruptFile : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            val path = db.path
            // 打开时就发现损坏的，这时库还没真正打开，不能再调 close()：
            // 系统接下来要用同一个对象重新打开，先关掉会让它再也用不了
            if (db.isOpen) runCatching { db.close() }
            if (path.isNullOrEmpty() || path == ":memory:") return
            val stamp = System.currentTimeMillis()
            for (suffix in arrayOf("", "-wal", "-shm", "-journal")) {
                val f = File(path + suffix)
                if (f.exists()) f.renameTo(File("$path.corrupt-$stamp$suffix"))
            }
            lastCorrupt = "$path.corrupt-$stamp"
            // 接下来会建一个新的空库，时钟、序号、设备号都要从新库里重新读
            ItemRepo.reset()
        }
    }

    companion object {
        const val NAME = "clipdock.db"
        const val VERSION = 1

        const val META_SCHEMA_VERSION = "schema_version"
        const val META_DEVICE_ID = "device_id"
        const val META_HLC_LAST = "hlc_last"
        const val META_SEQ_LAST = "seq_last"
        const val META_MIGRATED_FROM_JSON = "migrated_from_json"

        /** 这次运行中数据库文件损坏过：坏文件被改名成了这个路径。没坏过为 null */
        @Volatile var lastCorrupt: String? = null
            internal set

        @Volatile private var instance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx.applicationContext ?: ctx).also { instance = it }
        }

        /** 单元测试用：关掉数据库，模拟进程重启 */
        internal fun resetForTest() = synchronized(this) {
            runCatching { instance?.close() }
            instance = null
            lastCorrupt = null
            ItemRepo.reset()
        }
    }
}

/**
 * 数据库专用线程：所有读写都排在这一条线程上依次执行。
 *
 * 为什么只用一条线程：写入顺序就是界面上操作的顺序（先加后删不会变成先删后加），
 * 而且 HLC、变更序号这些计数器不需要加锁。
 */
object DbThread {
    @Volatile private var thread: Thread? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "clipdock-db").also { thread = it } }

    /** 任务里没接住的异常交给它（记到诊断里）。不设置就悄悄丢弃——无论如何不能让数据库线程死掉 */
    @Volatile var onError: ((Throwable) -> Unit)? = null

    fun isCurrent(): Boolean = Thread.currentThread() === thread

    /** 排队执行，不等结果 */
    fun post(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (e: Throwable) {
                runCatching { onError?.invoke(e) }
            }
        }
    }

    /**
     * 排队执行并等结果；排在它前面的写入都会先完成，所以读到的一定是最新的。
     * 会阻塞调用线程，界面线程上只适合用在很快的查询上。已经在数据库线程上时直接执行。
     */
    fun <T> call(block: () -> T): T {
        if (isCurrent()) return block()
        try {
            return exec.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * 等排在前面的任务都做完，最多等 timeoutMs 毫秒；等到了返回 true。
     * 会卡住调用它的线程，只用在必须确认“已经存好了”的地方。已经在数据库线程上时直接返回。
     */
    fun await(timeoutMs: Long): Boolean {
        if (isCurrent()) return true
        return try {
            exec.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            false
        } catch (e: ExecutionException) {
            true
        }
    }

    fun check() {
        check(isCurrent()) { "只能在数据库线程上调用" }
    }
}
