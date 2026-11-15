package com.mistpeaklab.clipdock.data

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 隐私模式的“保险箱”：隐藏条目的加解密，以及“现在是否已经验证过身份”。
 *
 * 密钥分两层：
 * - 数据密钥：随机的 AES-256 密钥，真正用来加密隐藏的内容；
 * - 它本身用系统密钥库（Android Keystore）里的一把密钥包起来，存在数据库的 meta 表里。
 *   启动时解开一次，之后加解密都在本进程里做（很快）。
 *
 * 能防什么、不能防什么（界面上也这样告诉用户）：
 * - 数据库文件被拷走、被别的应用或备份工具读到：看不到隐藏的内容；
 * - 别人拿到没锁屏的设备、打开随贴：要先通过指纹 / 锁屏密码验证（见 UnlockActivity）才能看或贴；
 * - 防不了已经取得系统最高权限（root）的攻击者。
 *
 * 系统密钥库里的那把密钥没有绑定“必须先验证指纹才能用”。绑定了更安全，但用户改一次锁屏密码、
 * 加一枚指纹，密钥就会永久作废，隐藏的内容再也解不开——对一个剪贴板工具来说，这个代价不划算。
 */
object Vault {
    /** 把数据密钥包起来 / 解开的办法。真机上是系统密钥库；单元测试里换成假的 */
    interface Wrapper {
        fun wrap(plain: ByteArray): ByteArray
        fun unwrap(wrapped: ByteArray): ByteArray
    }

    @Volatile var wrapper: Wrapper = KeystoreWrapper

    const val META_KEY = "vault_wrapped"

    /** 还没建过 */
    const val STATE_NONE = 0

    /** 可以用 */
    const val STATE_READY = 1

    /** 以前建过，但现在解不开（系统密钥库里的密钥没了）：已隐藏的内容读不出来 */
    const val STATE_BROKEN = 2

    @Volatile private var key: SecretKey? = null
    @Volatile var state = STATE_NONE
        private set
    val ready: Boolean get() = key != null

    private val random = SecureRandom()

    /** 启动时在数据库线程上调用：wrapped 是 meta 表里存的值，没有就传 null */
    fun load(value: String?) {
        key = null
        wrapped = value
        if (value == null) { state = STATE_NONE; return }
        state = try {
            key = SecretKeySpec(wrapper.unwrap(Base64.decode(value, Base64.NO_WRAP)), "AES")
            STATE_READY
        } catch (e: Throwable) {
            STATE_BROKEN
        }
    }

    /** 包好的数据密钥（meta 表里存的就是它）。数据库损坏重建时要把它重新存进去 */
    @Volatile var wrapped: String? = null
        private set

    /**
     * 生成一把新的数据密钥，但先不启用。
     * 调用方**必须先把返回的第一项存进 meta 表，再调用 [adopt]**，然后才能加密——
     * 顺序反了的话，万一没存上，用这把密钥加密的内容下次启动就再也解不开。
     * @return (要存进 meta 表的值, 密钥本身)；这台设备的密钥库不可用时返回 null
     */
    fun generate(): Pair<String, ByteArray>? = try {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        Base64.encodeToString(wrapper.wrap(raw), Base64.NO_WRAP) to raw
    } catch (e: Throwable) {
        null
    }

    /** 启用 [generate] 生成的密钥 */
    @Synchronized
    fun adopt(wrappedValue: String, raw: ByteArray) {
        key = SecretKeySpec(raw, "AES")
        wrapped = wrappedValue
        state = STATE_READY
    }

    /** 放弃旧密钥（已隐藏的内容将永远读不出来），回到“还没建过”的状态 */
    @Synchronized
    fun forget() {
        key = null
        wrapped = null
        state = STATE_NONE
    }

    /** 加密：12 字节随机数 + 密文 + 16 字节校验。没有密钥返回 null */
    fun seal(text: String): ByteArray? {
        val k = key ?: return null
        return try {
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
            nonce + c.doFinal(text.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            null
        }
    }

    /** 解密；解不开（没有密钥、数据被改过）返回 null */
    fun open(blob: ByteArray?): String? {
        val k = key ?: return null
        if (blob == null || blob.size < 28) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------- 是否已验证身份 ----------------

    @Volatile private var unlockedUntil = 0L

    /** 用开机以来的时间计，改系统时间骗不过去 */
    fun isUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    /** 验证通过。timeoutMs 是之后多久不用再验证；至少给 5 秒，让验证后要做的那件事来得及做 */
    fun markUnlocked(timeoutMs: Long) {
        unlockedUntil = SystemClock.elapsedRealtime() + maxOf(timeoutMs, 5_000L)
    }

    fun lock() {
        unlockedUntil = 0L
    }

    /** 系统密钥库：一把不导出的 AES 密钥，只用来包数据密钥 */
    private object KeystoreWrapper : Wrapper {
        private const val ALIAS = "clipdock_vault_kek"

        private fun kek(create: Boolean): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "密钥库里的密钥不见了" }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        override fun wrap(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, kek(true))   // 密钥库里的密钥要由系统自己选随机数
            return c.iv + c.doFinal(plain)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, kek(false), GCMParameterSpec(128, wrapped, 0, 12))
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        }
    }
}
