package com.mistpeaklab.clipdock.data

import com.mistpeaklab.clipdock.tr

import java.security.MessageDigest

/**
 * 数据层用到的纯数据类和纯函数（不依赖 Android，可以在 JVM 单元测试里直接用）。
 */
object ItemKind {
    /** 剪贴板记录 */
    const val CLIP = 0
    /** 常用语 */
    const val PHRASE = 1
    /** 文库篇章（v0.7） */
    const val LIBRARY = 2
}

/** 应用规则：在这个应用里复制的内容怎么处理 */
object AppRule {
    /** 不记录 */
    const val SKIP = 0
    /** 自动隐藏 */
    const val HIDE = 1
}

/**
 * items 表里的一行。
 *
 * 时间字段有两种单位，别混用：
 * - [createdAt] [updatedAt] [deletedAt] 是 HLC（见 [Hlc]），要显示成时间先用 [Hlc.millis]；
 * - [usedAt] [evictedAt] [expireAt] 是普通的毫秒时间。
 *
 * 隐藏的条目：[text] [search] 是空的，内容在 [cipher] 里（加密的）。[hash] [chars] 仍然是原文的。
 */
class ItemRow(
    val id: String,
    val kind: Int,
    val text: String,
    val search: String,
    val chars: Int,
    val sourcePkg: String?,
    val groupId: String?,
    val pinned: Boolean,
    val hidden: Boolean,
    val expireAt: Long?,
    val sortKey: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val usedAt: Long,
    val useCount: Int,
    val evictedAt: Long?,
    val deletedAt: Long?,
    val note: String?,
    /** 正文已清空，只剩一行空壳 */
    val wiped: Boolean,
    val cipher: ByteArray?,
    val deviceId: String,
    val hash: String,
) {
    /** 还在列表里：没删除、没被淘汰、正文还在 */
    val live: Boolean get() = deletedAt == null && evictedAt == null && !wiped
}

/** 新增一条时由调用方给出的内容，其余列（hash、时间戳、序号…）由 ItemRepo 填 */
class NewItem(
    val id: String,
    val kind: Int,
    /** 原文。要隐藏时也传原文（用来算 hash 和字数），另外给出 [cipher]，库里不会存原文 */
    val text: String,
    /** 现在的毫秒时间，写入 used_at */
    val nowMs: Long,
    val sourcePkg: String? = null,
    val groupId: String? = null,
    val pinned: Boolean = false,
    val expireAt: Long? = null,
    val sortKey: Double? = null,
    val note: String? = null,
    /** 非空 = 这是一条隐藏的内容，这是它加密后的样子 */
    val cipher: ByteArray? = null,
)

/**
 * 时间戳由调用方指定的一行，用于迁移旧数据。
 * [createdAt] [updatedAt] 是 HLC，[usedAt] 是毫秒。
 */
data class RawItem(
    val id: String,
    val kind: Int,
    val text: String,
    val pinned: Boolean,
    val sortKey: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val usedAt: Long,
)

/** 分组。[scope]：0 = 收藏里的分组，1 = 文库里的文集（v0.7） */
data class GroupRow(
    val id: String,
    val name: String,
    val sortKey: Double,
    val scope: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val color: Int? = null,
)

/** 导出文件里的一条记录（也是导入时读到的样子）。隐藏的条目这里是明文，所以只会出现在加密的导出文件里 */
class PortItem(
    val id: String,
    val kind: Int,
    val text: String,
    val sourcePkg: String?,
    val groupId: String?,
    val pinned: Boolean,
    val hidden: Boolean,
    val note: String?,
    val sortKey: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val usedAt: Long?,
    val deletedAt: Long?,
    /** 导出时它在“超出上限”的回收站里 */
    val evicted: Boolean,
    val expireAt: Long?,
    val deviceId: String?,
)

/** [ItemRepo.importItem] 等的统计 */
class ImportStats(var added: Int = 0, var updated: Int = 0, var skipped: Int = 0, var groups: Int = 0)

object ItemText {
    /** 单条长度上限（字符数）。超过的部分截掉，并在预览里标注 */
    const val MAX_CHARS = 1_000_000
    const val PREVIEW_CHARS = 300
    /** 超长被截断时，预览开头用来提示 */
    val TRUNCATED_MARK: String get() = tr("（内容过长，已截断）", "(truncated) ")

    private val HEX = "0123456789abcdef".toCharArray()

    /** SHA-256(原文的 UTF-8 字节)，小写十六进制。去重用 */
    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /** 超过 [MAX_CHARS] 的截掉。不会把一个代理对（比如表情符号）从中间切开 */
    fun limit(text: String): String = if (text.length <= MAX_CHARS) text else cut(text, MAX_CHARS)

    /** 列表预览：前 [PREVIEW_CHARS] 个字符，连续的空白（含换行）折叠成一个空格 */
    fun preview(text: String): String {
        val sb = StringBuilder(minOf(text.length, PREVIEW_CHARS) + TRUNCATED_MARK.length)
        if (text.length >= MAX_CHARS) sb.append(TRUNCATED_MARK)
        val max = sb.length + PREVIEW_CHARS
        var space = true   // 开头的空白直接丢掉
        for (c in text) {
            if (sb.length >= max) break
            if (c.isWhitespace()) {
                if (!space) sb.append(' ')
                space = true
            } else {
                sb.append(c)
                space = false
            }
        }
        val s = sb.toString().trimEnd()
        // 预览可能正好停在代理对中间
        return if (s.isNotEmpty() && s.last().isHighSurrogate()) s.dropLast(1) else s
    }

    private fun cut(text: String, n: Int): String =
        if (text[n - 1].isHighSurrogate()) text.substring(0, n - 1) else text.substring(0, n)
}
