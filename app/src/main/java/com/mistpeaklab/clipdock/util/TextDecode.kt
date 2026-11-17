package com.mistpeaklab.clipdock.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 把导入的 txt / md 文件的字节变成文字。
 * 先认 BOM（UTF-8 / UTF-16）；没有 BOM 时按 UTF-8 严格解码，有任何不合法的字节就改用 GB18030
 * （国内老的 txt 多半是 GBK，GB18030 是它的超集）。
 */
object TextDecode {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    /** 统一换行：\r\n、\r 都换成 \n */
    fun normalize(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
}

/**
 * 把长文切成块（返回各块在原文里的 [起, 止) 位置）：尽量在换行处切，每块不超过 [max] 个字符；
 * 一段话本身超过 [max] 时在 [max] 处硬切（不切开代理对）。所有块首尾相接，拼起来就是原文。
 */
object Chunks {
    fun split(text: String, max: Int): List<IntRange> {
        require(max > 1)
        val out = ArrayList<IntRange>()
        var start = 0
        val n = text.length
        while (start < n) {
            var end = minOf(n, start + max)
            if (end < n) {
                // 往回找最近的换行，切在换行之后；找不到（一整段太长）就硬切
                val nl = text.lastIndexOf('\n', end - 1)
                if (nl >= start) end = nl + 1
                else if (Character.isHighSurrogate(text[end - 1])) end--
            }
            out += start until end
            start = end
        }
        return out
    }

    /** 第几块包含原文的这个位置 */
    fun indexOf(chunks: List<IntRange>, offset: Int): Int {
        val i = chunks.indexOfFirst { offset in it }
        return if (i >= 0) i else (chunks.size - 1).coerceAtLeast(0)
    }
}
