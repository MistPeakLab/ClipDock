package com.mistpeaklab.clipdock.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 导出文件的加密：用密码算出密钥（PBKDF2），再分块用 AES-256-GCM 加密。
 * 纯 JDK 实现，不依赖 Android，Windows 端照着这里的格式写就能互通（见 docs/protocol）。
 *
 * 为什么要分块：导出文件可能有几十 MB，GCM 一次性加解密要把整个文件放进内存。
 * 分成每块 [CHUNK] 字节，每块单独加密、单独校验：
 *
 *     第 i 块的随机数 = 8 字节前缀（每个文件随机生成） + 4 字节块序号（大端，从 0 开始）
 *     附加校验数据   = 1 字节：最后一块是 1，其余是 0
 *     每块输出       = 密文 + 16 字节校验
 *
 * 块序号进了随机数，所以块不能被调换顺序；“是不是最后一块”进了校验，所以文件不能被从中间截断。
 */
object Crypto {
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val CIPHER = "AES-256-GCM"
    const val ITERATIONS = 310_000
    const val CHUNK = 1 shl 20
    private const val TAG = 16

    private val random = SecureRandom()
    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** 密码 → 256 位密钥。比较慢（故意的，防暴力猜密码），不要在界面线程上调用 */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, index: Int) = ByteArray(12).also {
        System.arraycopy(prefix, 0, it, 0, 8)
        it[8] = (index ushr 24).toByte(); it[9] = (index ushr 16).toByte(); it[10] = (index ushr 8).toByte(); it[11] = index.toByte()
    }

    /** 加密输出流。一定要调用 [close]，最后一块在那时才写出去 */
    class SealStream(
        private val out: OutputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        chunk: Int = CHUNK,
    ) : OutputStream() {
        private val buf = ByteArray(chunk)
        private var n = 0
        private var index = 0
        private var closed = false

        init { require(prefix.size == 8) }

        override fun write(b: Int) {
            if (n == buf.size) flushChunk(false)
            buf[n++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                // 缓冲满了、后面还有数据，才能确定它不是最后一块
                if (n == buf.size) flushChunk(false)
                val k = minOf(left, buf.size - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; left -= k
            }
        }

        private fun flushChunk(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index++)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            out.write(c.doFinal(buf, 0, n))
            n = 0
        }

        /** 只把最后一块写出去，不关闭下层的流（下层是压缩包，后面可能还要写别的） */
        override fun close() {
            if (closed) return
            closed = true
            flushChunk(true)
            out.flush()
        }
    }

    /** 密码不对，或者文件被改过 / 损坏 */
    class BadKeyException : IOException("密码不对，或者文件已损坏")

    /** 解密输入流 */
    class OpenStream(
        private val input: InputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val chunk: Int = CHUNK,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        // 多读一个字节，用来判断当前这块后面还有没有数据（有 = 不是最后一块）
        private var ahead = input.read()

        private fun fill(): Boolean {
            if (done) return false
            val enc = ByteArray(chunk + TAG)
            var n = 0
            if (ahead >= 0) { enc[0] = ahead.toByte(); n = 1 }
            while (n < enc.size) {
                val r = input.read(enc, n, enc.size - n)
                if (r < 0) break
                n += r
            }
            ahead = if (n == enc.size) input.read() else -1
            val last = ahead < 0
            if (n < TAG) throw EOFException("文件不完整")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index++)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            plain = try {
                c.doFinal(enc, 0, n)
            } catch (e: AEADBadTagException) {
                throw BadKeyException()
            }
            pos = 0
            done = last
            return plain.isNotEmpty() || !done
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!fill()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!fill()) return -1
            val k = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, k)
            pos += k
            return k
        }
    }
}
