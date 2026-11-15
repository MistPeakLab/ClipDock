package com.mistpeaklab.clipdock.data

/**
 * 混合逻辑时钟（HLC）：64 位整数 = 毫秒时间(48 位) << 16 | 计数器(16 位)。
 *
 * 用它而不是直接用系统时间，是为了让“哪次修改更新”在多台设备之间可以比较：
 * - 本机每次写入都比上一次大，哪怕系统时间被往回调了；
 * - 见过别的设备的时间戳以后（[observe]），本机之后的写入一定比它大。
 *
 * 纯 Kotlin，不依赖 Android。这个类只管算，不管存：
 * 调用方（ItemRepo）负责把 [last] 写进 meta.hlc_last，下次启动再传回来。
 *
 * @param last  上次持久化的值，首次为 0
 * @param clock 当前毫秒时间，测试时可以换成假的
 */
class Hlc(last: Long = 0L, private val clock: () -> Long = System::currentTimeMillis) {

    /** 目前发出过 / 见过的最大值 */
    var last: Long = last
        private set

    /** 生成下一个时间戳：max(现在 << 16, 上一个 + 1)。同一毫秒内靠计数器递增，计数器满了自然进位到毫秒部分 */
    @Synchronized
    fun next(): Long {
        last = maxOf(pack(clock(), 0), last + 1)
        return last
    }

    /** 收到外部数据（导入、同步）时调用，保证之后本机生成的时间戳都比它大 */
    @Synchronized
    fun observe(remote: Long) {
        if (remote > last) last = remote
    }

    companion object {
        const val COUNTER_BITS = 16
        const val COUNTER_MASK = 0xFFFFL

        /** 毫秒部分的上限（47 位，保证打包后仍是正数） */
        const val MAX_MILLIS = (1L shl 47) - 1

        /** 把毫秒时间和计数器拼成 HLC。超出范围的值会被夹到合法范围内 */
        fun pack(millis: Long, counter: Int = 0): Long =
            (millis.coerceIn(0L, MAX_MILLIS) shl COUNTER_BITS) or (counter.toLong() and COUNTER_MASK)

        /** HLC 里的毫秒时间 */
        fun millis(hlc: Long): Long = hlc ushr COUNTER_BITS

        /** HLC 里的计数器 */
        fun counter(hlc: Long): Int = (hlc and COUNTER_MASK).toInt()
    }
}
