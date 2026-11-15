package com.mistpeaklab.clipdock.data

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * UUID v7（RFC 9562）：前 48 位是毫秒时间，后面是随机数，所以按字符串排序大致就是按生成时间排序。
 *
 *     毫秒时间 48 位 | 版本 4 位 = 7 | rand_a 12 位 | 变体 2 位 = 10 | rand_b 62 位
 *
 * 纯 Kotlin，不依赖 Android。输出是小写的 8-4-4-4-12 形式。
 */
object Uuid7 {
    /** 时间部分能表示的最大毫秒数（48 位） */
    const val MAX_MILLIS = (1L shl 48) - 1

    private val random = SecureRandom()
    private var lastMs = -1L
    private var counter = 0

    /**
     * 生成一个新的 ID。本进程内严格递增：同一毫秒内（或系统时间被往回调时）
     * 把 rand_a 当计数器加一，加满了就向毫秒部分借一位。
     */
    @Synchronized
    fun next(nowMs: Long = System.currentTimeMillis()): String {
        val ms = nowMs.coerceIn(0L, MAX_MILLIS)
        if (ms > lastMs) {
            lastMs = ms
            // 只在低 11 位里取随机起点，同一毫秒内至少还能再生成 2048 个
            counter = random.nextInt(0x800)
        } else if (++counter > 0xFFF) {
            lastMs++
            counter = random.nextInt(0x800)
        }
        return format(lastMs, counter, random.nextLong())
    }

    /**
     * 用指定的毫秒时间生成 ID，其余位随机。迁移旧数据时用它，让旧记录的 ID 仍然按原来的时间排序。
     * 不影响 [next] 的递增状态。
     */
    fun at(ms: Long, rnd: Random = random): String = format(ms, rnd.nextInt(0x1000), rnd.nextLong())

    /** 按位拼出 UUID 字符串。各部分超出位数的会被截掉 / 夹到范围内 */
    fun format(ms: Long, randA: Int, randB: Long): String {
        val hi = (ms.coerceIn(0L, MAX_MILLIS) shl 16) or 0x7000L or (randA.toLong() and 0xFFFL)
        val lo = (randB and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(hi, lo).toString()
    }

    /** 取出 ID 里的毫秒时间；不是合法的 UUID v7 时返回 null */
    fun millis(id: String): Long? {
        if (id.length != 36) return null
        val u = try { UUID.fromString(id) } catch (e: IllegalArgumentException) { return null }
        if (u.version() != 7 || u.variant() != 2) return null
        return u.mostSignificantBits ushr 16
    }

    fun isValid(id: String): Boolean = millis(id) != null
}
