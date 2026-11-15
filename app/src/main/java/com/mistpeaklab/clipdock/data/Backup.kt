package com.mistpeaklab.clipdock.data

import com.mistpeaklab.clipdock.tr

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey

/**
 * 导出 / 导入。**都只能在数据库线程上调用**（要读写数据库，也可能很慢）。
 *
 * 文件格式 `.clipdock` 是一个 zip：
 *
 *     manifest.json   说明：格式版本、条数、是否加密、加密参数
 *     data.json       内容（未加密时）
 *     data.enc        内容（加密时；data.json 经 [Crypto] 分块加密）
 *
 * 多端共用这个格式（见 docs/protocol），改动只能加字段，不能改已有字段的含义。
 */
object Backup {
    const val FORMAT = "clipdock-export"
    const val FORMAT_VERSION = 1
    const val EXT = "clipdock"

    /**
     * @param includeTrash  连回收站里的也导出
     * @param includeHidden 连隐藏的内容也导出（明文写进文件，所以只有加密导出时才生效）
     * @param password      设了就加密
     * @param key / salt    已经算好的密钥和它对应的盐（自动备份用，免得每次都要密码）；和 password 二选一
     */
    class Options(
        val includeTrash: Boolean = false,
        val includeHidden: Boolean = false,
        val password: CharArray? = null,
        val key: SecretKey? = null,
        val salt: ByteArray? = null,
        val app: String = "ClipDock Android",
    ) {
        val encrypted get() = password != null || (key != null && salt != null)
    }

    /** 这是什么文件 */
    enum class Kind { CLIPDOCK, LEGACY_JSON, TEXT }

    class Info(
        val kind: Kind,
        val items: Int = 0,
        val groups: Int = 0,
        val encrypted: Boolean = false,
        val createdAt: Long = 0,
        val app: String = "",
        val formatVersion: Int = FORMAT_VERSION,
        internal val salt: ByteArray? = null,
        internal val iterations: Int = Crypto.ITERATIONS,
        internal val prefix: ByteArray? = null,
        internal val chunk: Int = Crypto.CHUNK,
    )

    /** 文件是更新版本的随贴导出的，这个版本读不了 */
    class TooNewException(val version: Int) : IOException(tr("文件是更新版本的随贴导出的（格式版本 %d）", "This file comes from a newer ClipDock (format version %d)", version))

    /** 加密的文件没给密码 */
    class NeedPasswordException : IOException(tr("这个文件是加密的，需要密码", "This file is encrypted and needs a password"))

    // ---------------- 导出 ----------------

    /** @return 写出的条数 */
    fun export(repo: ItemRepo, out: OutputStream, opts: Options): Int {
        val withHidden = opts.includeHidden && opts.encrypted
        val ids = repo.exportIds(opts.includeTrash).let { all -> if (withHidden) all else all.filter { repo.get(it)?.hidden == false } }
        val groups = repo.exportGroups().filter { it.deletedAt == null }

        var key: SecretKey? = null
        var salt: ByteArray? = null
        var prefix: ByteArray? = null
        if (opts.encrypted) {
            salt = opts.salt ?: Crypto.randomBytes(16)
            key = opts.key ?: Crypto.deriveKey(opts.password!!, salt)
            prefix = Crypto.randomBytes(8)
        }

        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("manifest.json"))
        val m = JSONObject()
            .put("format", FORMAT).put("format_version", FORMAT_VERSION).put("app", opts.app)
            .put("created_at", System.currentTimeMillis()).put("device_id", repo.device)
            .put("counts", JSONObject().put("items", ids.size).put("groups", groups.size))
        if (key != null) m.put(
            "encryption", JSONObject().put("kdf", Crypto.KDF).put("iterations", Crypto.ITERATIONS)
                .put("salt", b64(salt!!)).put("cipher", Crypto.CIPHER).put("chunk", Crypto.CHUNK).put("nonce_prefix", b64(prefix!!)),
        ) else m.put("encryption", JSONObject.NULL)
        zip.write(m.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.putNextEntry(ZipEntry(if (key != null) "data.enc" else "data.json"))
        val seal = key?.let { Crypto.SealStream(zip, it, prefix!!) }
        val w = JsonWriter(OutputStreamWriter(seal ?: zip, Charsets.UTF_8))
        var n = 0
        w.beginObject()
        // 分组写在前面：导入时先有分组，后面的条目才知道自己属于哪一组
        w.name("groups").beginArray()
        for (g in groups) {
            w.beginObject()
            w.name("id").value(g.id).name("name").value(g.name).name("sort_key").value(g.sortKey).name("scope").value(g.scope.toLong())
            w.name("created_at").value(g.createdAt).name("updated_at").value(g.updatedAt)
            w.name("color"); if (g.color == null) w.nullValue() else w.value(g.color.toLong())
            w.endObject()
        }
        w.endArray()
        w.name("items").beginArray()
        for (id in ids) {
            val r = repo.get(id) ?: continue
            // 隐藏的内容解不开（密钥没了）就跳过，没法导出
            val text = if (r.hidden) Vault.open(r.cipher) ?: continue else r.text
            w.beginObject()
            w.name("id").value(r.id).name("kind").value(r.kind.toLong()).name("text").value(text)
            w.name("source_pkg").value(r.sourcePkg).name("group_id").value(r.groupId)
            w.name("pinned").value(r.pinned).name("hidden").value(r.hidden).name("note").value(r.note)
            w.name("sort_key"); if (r.sortKey == null) w.nullValue() else w.value(r.sortKey)
            w.name("created_at").value(r.createdAt).name("updated_at").value(r.updatedAt).name("used_at").value(r.usedAt)
            w.name("deleted_at"); if (r.deletedAt == null) w.nullValue() else w.value(r.deletedAt)
            w.name("evicted").value(r.evictedAt != null)
            w.name("expire_at"); if (r.expireAt == null) w.nullValue() else w.value(r.expireAt)
            w.name("device_id").value(r.deviceId)
            w.endObject()
            n++
        }
        w.endArray()
        w.endObject()
        w.flush()           // 不能 close：会把整个压缩包关掉
        seal?.close()       // 写出最后一块密文
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return n
    }

    private val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 导出成给人看的纯文本（不含隐藏的内容）。不能再导入回来合并。@return 写出的条数 */
    fun exportText(repo: ItemRepo, out: OutputStream): Int {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        var n = 0
        for (id in repo.exportIds(false)) {
            val r = repo.get(id) ?: continue
            if (r.hidden) continue
            w.write(if (r.kind == ItemKind.PHRASE) tr("【常用语】", "[Phrase]") else "[" + day.format(Date(r.usedAt)) + "]")
            if (r.note != null) w.write(" " + r.note)
            w.write("\n")
            w.write(r.text)
            w.write("\n\n────────\n\n")
            n++
        }
        w.flush()
        return n
    }

    // ---------------- 导入 ----------------

    private const val MAX_PLAIN_BYTES = 8 * 1024 * 1024

    /** 看一眼这是什么文件。读完后流就用掉了，真正导入时要重新打开一次 */
    fun peek(input: InputStream): Info {
        val bin = BufferedInputStream(input)
        if (isZip(bin)) {
            val zip = ZipInputStream(bin)
            return readManifest(zip)
        }
        val text = readText(bin)
        return if (looksLikeLegacy(text)) Info(Kind.LEGACY_JSON, items = runCatching { LegacyMigrator.parse(text).size }.getOrDefault(0))
        else Info(Kind.TEXT, items = if (text.isBlank()) 0 else 1)
    }

    /**
     * 导入。整个文件在一个事务里：出错（包括密码不对）就什么都不改。
     * @param replace true = 先把现有的记录、常用语都移进回收站，再导入（“用文件里的替换现有的”）
     * @param seal    见 [ItemRepo.importItem]
     */
    fun import(
        repo: ItemRepo, input: InputStream, password: CharArray?, replace: Boolean,
        seal: (String) -> ByteArray?,
    ): ImportStats {
        val bin = BufferedInputStream(input)
        val stats = ImportStats()
        if (!isZip(bin)) {
            val text = readText(bin)
            repo.transaction {
                if (replace) repo.softDelete(repo.exportIds(false))
                if (looksLikeLegacy(text)) {
                    val items = LegacyMigrator.parse(text)
                    stats.added = repo.importLegacy(items, markMigrated = false).size
                    stats.skipped = items.size - stats.added
                } else if (text.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    val stamp = Hlc.pack(now)
                    val r = PortItem(
                        Uuid7.next(now), ItemKind.CLIP, ItemText.limit(text), null, null, pinned = false, hidden = false, note = null,
                        sortKey = null, createdAt = stamp, updatedAt = stamp, usedAt = now, deletedAt = null, evicted = false,
                        expireAt = null, deviceId = null,
                    )
                    if (repo.importItem(r, seal) == 1) stats.added++ else stats.skipped++
                }
            }
            return stats
        }

        val zip = ZipInputStream(bin)
        val info = readManifest(zip)
        val entry = zip.nextEntry ?: throw IOException(tr("文件里没有内容", "The file is empty"))
        val data: InputStream = when (entry.name) {
            "data.json" -> zip
            "data.enc" -> {
                if (password == null) throw NeedPasswordException()
                val key = Crypto.deriveKey(password, info.salt ?: throw IOException(tr("文件缺少加密参数", "The file is missing encryption parameters")), info.iterations)
                Crypto.OpenStream(zip, key, info.prefix ?: throw IOException(tr("文件缺少加密参数", "The file is missing encryption parameters")), info.chunk)
            }
            else -> throw IOException(tr("认不出的文件内容：%s", "Unrecognised content: %s", entry.name))
        }
        repo.transaction {
            if (replace) repo.softDelete(repo.exportIds(false))
            val r = JsonReader(InputStreamReader(data, Charsets.UTF_8))
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "groups" -> { r.beginArray(); while (r.hasNext()) readGroup(r)?.let { if (repo.importGroup(it)) stats.groups++ }; r.endArray() }
                    "items" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            val item = readItem(r)
                            if (item == null) { stats.skipped++; continue }
                            when (repo.importItem(item, seal)) { 1 -> stats.added++; 2 -> stats.updated++; else -> stats.skipped++ }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            repo.dropOrphanGroupRefs()
        }
        return stats
    }

    private fun isZip(bin: BufferedInputStream): Boolean {
        bin.mark(4)
        val a = bin.read(); val b = bin.read()
        bin.reset()
        return a == 'P'.code && b == 'K'.code
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_PLAIN_BYTES) throw IOException(tr("文件太大了（超过 8 MB）", "The file is too large (over 8 MB)"))
        }
        return String(out.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
    }

    /** 0.5 版的记录文件：一个 JSON 数组，里面是带 text 的对象 */
    private fun looksLikeLegacy(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("[") && t.contains("\"text\"") && runCatching { org.json.JSONArray(t) }.isSuccess
    }

    private fun readManifest(zip: ZipInputStream): Info {
        val e = zip.nextEntry
        if (e == null || e.name != "manifest.json") throw IOException(tr("这不是随贴导出的文件", "This is not a ClipDock export"))
        val m = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
        if (m.optString("format") != FORMAT) throw IOException(tr("这不是随贴导出的文件", "This is not a ClipDock export"))
        val v = m.optInt("format_version", 1)
        if (v > FORMAT_VERSION) throw TooNewException(v)
        val counts = m.optJSONObject("counts")
        val enc = m.optJSONObject("encryption")
        if (enc != null && (enc.optString("kdf") != Crypto.KDF || enc.optString("cipher") != Crypto.CIPHER)) {
            throw IOException(tr("不认识的加密方式", "Unknown encryption"))
        }
        return Info(
            Kind.CLIPDOCK, counts?.optInt("items") ?: 0, counts?.optInt("groups") ?: 0, enc != null,
            m.optLong("created_at"), m.optString("app"), v,
            enc?.let { unb64(it.getString("salt")) }, enc?.optInt("iterations", Crypto.ITERATIONS) ?: Crypto.ITERATIONS,
            enc?.let { unb64(it.getString("nonce_prefix")) }, enc?.optInt("chunk", Crypto.CHUNK) ?: Crypto.CHUNK,
        )
    }

    private fun JsonReader.str(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
    private fun JsonReader.long(): Long? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextLong()
    private fun JsonReader.dbl(): Double? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextDouble()
    private fun JsonReader.bool(): Boolean = if (peek() == JsonToken.NULL) { nextNull(); false } else nextBoolean()

    /** 缺了必需字段（id、text）的条目返回 null，跳过它 */
    private fun readItem(r: JsonReader): PortItem? {
        var id: String? = null; var kind = ItemKind.CLIP; var text: String? = null
        var src: String? = null; var group: String? = null; var pinned = false; var hidden = false; var note: String? = null
        var sort: Double? = null; var created = 0L; var updated = 0L; var used: Long? = null; var deleted: Long? = null
        var evicted = false; var expire: Long? = null; var device: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.str()
                "kind" -> kind = (r.long() ?: 0L).toInt()
                "text" -> text = r.str()
                "source_pkg" -> src = r.str()
                "group_id" -> group = r.str()
                "pinned" -> pinned = r.bool()
                "hidden" -> hidden = r.bool()
                "note" -> note = r.str()
                "sort_key" -> sort = r.dbl()
                "created_at" -> created = r.long() ?: 0L
                "updated_at" -> updated = r.long() ?: 0L
                "used_at" -> used = r.long()
                "deleted_at" -> deleted = r.long()
                "evicted" -> evicted = r.bool()
                "expire_at" -> expire = r.long()
                "device_id" -> device = r.str()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id.isNullOrEmpty() || text.isNullOrBlank()) return null
        // 这个版本只认记录和常用语；别的类型（以后的文库）先不导入，免得显示成奇怪的东西
        if (kind != ItemKind.CLIP && kind != ItemKind.PHRASE) return null
        if (created <= 0) created = Hlc.pack(System.currentTimeMillis())
        if (updated <= 0) updated = created
        return PortItem(
            id, kind, ItemText.limit(text), src, group, pinned, hidden, note?.takeIf { it.isNotBlank() }, sort, created, updated, used,
            deleted, evicted, expire, device,
        )
    }

    private fun readGroup(r: JsonReader): GroupRow? {
        var id: String? = null; var name: String? = null; var sort = 0.0; var scope = 0
        var created = 0L; var updated = 0L; var deleted: Long? = null; var color: Int? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.str()
                "name" -> name = r.str()
                "sort_key" -> sort = r.dbl() ?: 0.0
                "scope" -> scope = (r.long() ?: 0L).toInt()
                "created_at" -> created = r.long() ?: 0L
                "updated_at" -> updated = r.long() ?: 0L
                "deleted_at" -> deleted = r.long()
                "color" -> color = r.long()?.toInt()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id.isNullOrEmpty() || name.isNullOrBlank()) return null
        if (created <= 0) created = Hlc.pack(System.currentTimeMillis())
        if (updated <= 0) updated = created
        return GroupRow(id, name, sort, scope, created, updated, deleted, color)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
