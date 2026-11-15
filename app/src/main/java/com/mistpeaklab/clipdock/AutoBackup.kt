package com.mistpeaklab.clipdock

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import com.mistpeaklab.clipdock.data.Backup
import com.mistpeaklab.clipdock.data.Crypto
import com.mistpeaklab.clipdock.data.DbThread
import com.mistpeaklab.clipdock.data.ItemRepo
import com.mistpeaklab.clipdock.data.Vault
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.spec.SecretKeySpec

/** 系统定时叫醒它做自动备份 */
class BackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        AutoBackup.run(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/**
 * 自动备份：按天或按周往用户选的文件夹里写一份 .clipdock，只留最近几份。
 * 不联网，写到哪里由用户决定（可以是网盘同步的文件夹）。
 * 系统定时任务可能被厂商拦下，所以打开随贴时如果发现过期了，也会补做一次。
 */
object AutoBackup {
    private const val JOB_ID = 6001
    private const val DAY = 86_400_000L
    const val PREFIX = "ClipDock-backup-"

    fun periodMs(p: Prefs) = if (p.backupFreq == 2) 7 * DAY else DAY

    fun enabled(p: Prefs) = p.backupFreq != 0 && p.backupTree.isNotEmpty()

    /** 按设置排上（或取消）系统的定时任务 */
    fun schedule(ctx: Context) {
        val p = Prefs(ctx)
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(p)) { js.cancel(JOB_ID); return }
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, BackupJob::class.java))
                .setPeriodic(periodMs(p))
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build(),
        )
    }

    /** 系统里是否已经排着这个定时任务（被系统清掉之后，下次打开随贴要补上） */
    fun scheduled(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null
    }.getOrDefault(true) // 查不了就当已经排着，免得每次打开都重排、把周期一直往后推

    /** 过期了就补做一次（打开随贴时调用）。一小时内试过的不再试，免得反复失败时一直打扰 */
    fun runIfDue(ctx: Context) {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!enabled(p) || now - p.lastBackupAt < periodMs(p) || now - p.lastBackupTriedAt < 3_600_000L) return
        run(ctx) {}
    }

    /** 立即备份一次。done 在后台线程上回调 */
    fun run(ctx: Context, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        ClipStore.init(app)
        DbThread.post {
            val p = Prefs(app)
            p.lastBackupTriedAt = System.currentTimeMillis()
            val err = runCatching { write(app, p) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            if (err == null) {
                p.lastBackupAt = System.currentTimeMillis()
                p.lastBackupError = ""
                Diag.log("BACKUP", null, Diag.OK, null)
            } else {
                p.lastBackupError = err
                Diag.log("BACKUP", null, Diag.ERROR, err.take(120))
            }
            done(err)
        }
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)

    /** 只能在数据库线程上调用 */
    private fun write(ctx: Context, p: Prefs) {
        val cr = ctx.contentResolver
        val tree = Uri.parse(p.backupTree)
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val name = PREFIX + stamp.format(Date()) + "." + Backup.EXT
        val doc = DocumentsContract.createDocument(cr, dir, "application/octet-stream", name)
            ?: error(tr("没法在备份文件夹里新建文件（文件夹可能已被删除，请重新选择）", "Couldn't create a file in the backup folder (it may have been removed; please choose it again)"))
        val opts = backupKey(p)?.let { (key, salt) ->
            Backup.Options(includeHidden = true, key = key, salt = salt, app = appLabel(ctx))
        } ?: Backup.Options(app = appLabel(ctx))
        try {
            cr.openOutputStream(doc, "w")!!.use { Backup.export(ItemRepo.get(ctx), it, opts) }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            throw e
        }
        prune(ctx, tree, dirId, p.backupKeep.coerceAtLeast(1))
    }

    /** 只留最近 keep 份（只看自己生成的文件，别的文件不碰） */
    private fun prune(ctx: Context, tree: Uri, dirId: String, keep: Int) {
        val cr = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val mine = ArrayList<Pair<String, String>>()
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                if (n.startsWith(PREFIX) && n.endsWith("." + Backup.EXT)) mine += c.getString(0) to n
            }
        }
        // 文件名里带着时间，按名字倒排就是从新到旧
        mine.sortedByDescending { it.second }.drop(keep).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    private fun appLabel(ctx: Context) = "ClipDock Android " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")

    /**
     * 设置备份密码：由密码算出密钥（很慢，要在后台线程调用），用系统密钥库包好后存起来，
     * 之后自动备份不用再问密码。password 为空表示不加密。
     * @return 是否成功（系统密钥库不可用时无法记住密码）
     */
    fun setPassword(ctx: Context, password: CharArray?): Boolean {
        val p = Prefs(ctx)
        if (password == null || password.isEmpty()) {
            p.backupKey = ""
            p.backupSalt = ""
            return true
        }
        val salt = Crypto.randomBytes(16)
        val key = Crypto.deriveKey(password, salt)
        val wrapped = runCatching { Vault.wrapper.wrap(key.encoded) }.getOrNull() ?: return false
        p.backupSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        p.backupKey = Base64.encodeToString(wrapped, Base64.NO_WRAP)
        return true
    }

    /** 记住的备份密钥；没设密码返回 null，设了但解不开时抛异常（不能悄悄改成不加密） */
    private fun backupKey(p: Prefs): Pair<javax.crypto.SecretKey, ByteArray>? {
        if (p.backupKey.isEmpty()) return null
        val raw = runCatching { Vault.wrapper.unwrap(Base64.decode(p.backupKey, Base64.NO_WRAP)) }.getOrNull()
            ?: error(tr("备份密码失效了，请到 我的 › 备份 重新设置", "The backup password is no longer usable; please set it again in Me › Backup"))
        return SecretKeySpec(raw, "AES") to Base64.decode(p.backupSalt, Base64.NO_WRAP)
    }
}
