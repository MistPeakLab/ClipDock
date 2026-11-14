package com.mistpeaklab.clipdock

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 收录诊断：记下最近每一次“去读剪贴板”是被什么触发的、结果如何。
 * 只记触发来源、应用包名、结果和字数，**不记剪贴板内容**，方便用户截图或复制后反馈。
 */
object Diag {
    // 结果
    const val NEW = "NEW"            // 读到新内容并记录
    const val DUP = "DUP"            // 和已记录的相同
    const val EMPTY = "EMPTY"        // 能读，但没读到文字（剪贴板为空，或系统没放行）
    const val NO_FOCUS = "NO_FOCUS"  // 探针窗口没拿到焦点，读不到
    const val ADD_FAIL = "ADD_FAIL"  // 探针窗口加不上
    const val FALLBACK = "FALLBACK"  // 没读到剪贴板，改用了选中的文字

    data class Entry(val time: Long, val trigger: String, val pkg: String?, val result: String, val detail: String?, val count: Int = 1)

    private const val MAX = 50
    private const val MAX_SAMPLES = 300
    private val list = ArrayDeque<Entry>()
    private val sampleLines = ArrayDeque<String>()
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    private val _version = MutableStateFlow(0)
    /** 有新记录时加一，界面据此刷新 */
    val version: StateFlow<Int> = _version

    /** 事件采样截止时间：在此之前，无障碍服务会把收到的事件概要记下来，用来适配新机型 */
    @Volatile var sampleUntil = 0L
    val sampling get() = System.currentTimeMillis() < sampleUntil

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "diag.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Entry(
                    o.getLong("t"), o.getString("g"), o.optString("p").ifEmpty { null }, o.getString("r"),
                    o.optString("d").ifEmpty { null }, o.optInt("n", 1),
                )
            }
        }
    }

    fun log(trigger: String, pkg: String?, result: String, detail: String? = null) {
        synchronized(this) {
            val last = list.lastOrNull()
            // 连续相同的结果合并成一条并计数，避免被重复的兜底探测刷屏
            if (last != null && last.trigger == trigger && last.result == result && last.pkg == pkg && last.detail == detail) {
                list[list.lastIndex] = last.copy(time = System.currentTimeMillis(), count = last.count + 1)
            } else {
                list += Entry(System.currentTimeMillis(), trigger, pkg, result, detail)
                while (list.size > MAX) list.removeFirst()
            }
            save()
        }
        _version.value++
    }

    /** 从新到旧 */
    @Synchronized fun entries(): List<Entry> = list.reversed()

    fun clear() {
        synchronized(this) { list.clear(); sampleLines.clear(); save() }
        _version.value++
    }

    fun startSampling(seconds: Int) {
        synchronized(this) { sampleLines.clear() }
        sampleUntil = System.currentTimeMillis() + seconds * 1000L
        _version.value++
    }

    fun sample(line: String) {
        synchronized(this) {
            if (sampleLines.lastOrNull()?.substringAfter(' ') == line) return
            sampleLines += clock.format(Date()) + " " + line
            while (sampleLines.size > MAX_SAMPLES) sampleLines.removeFirst()
        }
    }

    @Synchronized fun sampleCount() = sampleLines.size

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("t", it.time).put("g", it.trigger).put("p", it.pkg ?: "").put("r", it.result).put("d", it.detail ?: "").put("n", it.count))
        }
        val s = arr.toString()
        io.execute { runCatching { f.writeText(s) } }
    }

    // ---------------- 状态与报告 ----------------

    /** 一项状态：名称、当前值、是否正常（null 表示只是信息） */
    data class Status(val name: String, val value: String, val ok: Boolean?)

    fun status(ctx: Context): List<Status> {
        val enabled = ctx.isClipServiceEnabledInSystem()
        val running = ClipService.instance != null
        val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        val prefs = Prefs(ctx)
        return listOf(
            Status("无障碍开关（系统设置里）", if (enabled) "已开启" else "未开启", enabled),
            Status(
                "无障碍服务实际运行", when {
                    running -> "运行中"
                    enabled -> "已被系统停止：请在无障碍设置里把随贴关掉再重新打开"
                    else -> "未运行"
                }, running,
            ),
            Status("显示在其他应用上层", if (Settings.canDrawOverlays(ctx)) "已允许" else "未允许", Settings.canDrawOverlays(ctx)),
            Status("忽略电池优化", if (battery) "已允许" else "未允许（服务可能被系统清理）", battery),
            Status("补漏检测", if (prefs.passiveCapture) "开" else "关", null),
            Status("设备", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）", null),
            Status("随贴版本", versionOf(ctx), null),
        )
    }

    private fun versionOf(ctx: Context) = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName}（${p.longVersionCode}）"
    }.getOrDefault("?")

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun timeLabel(t: Long): String = stamp.format(Date(t))

    /** 纯文本报告：不含剪贴板内容；做过事件采样时会带上按钮文字（8 个字以内）和窗口标题 */
    fun report(ctx: Context): String = buildString {
        append("【随贴 收录诊断】\n")
        status(ctx).forEach { append(it.name).append("：").append(it.value).append('\n') }
        val es = entries()
        append("\n最近 ").append(es.size).append(" 条记录（新→旧）：\n")
        es.forEach { e ->
            append(timeLabel(e.time)).append(' ').append(e.trigger).append(" → ").append(e.result)
            if (e.count > 1) append(" ×").append(e.count)
            e.pkg?.let { append(" · ").append(it) }
            e.detail?.let { append(" · ").append(it) }
            append('\n')
        }
        val samples = synchronized(this@Diag) { sampleLines.toList() }
        if (samples.isNotEmpty()) {
            append("\n事件采样（").append(samples.size).append(" 行）：\n")
            samples.forEach { append(it).append('\n') }
        }
    }

    fun triggerName(t: String) = when (t) {
        "KEY_COPY" -> "键盘 Ctrl+C / X"
        "CLICK_COPY" -> "点了“复制”"
        "LONG_CLICK" -> "长按菜单里的点击"
        "SELECTION_END" -> "文字选区消失"
        "COPIED_TOAST" -> "出现“已复制”提示"
        "SYSTEM_CLIP_UI" -> "系统剪贴板预览"
        "APP_SWITCH" -> "切换应用"
        "UNLOCK" -> "解锁屏幕"
        "PANEL_OPEN" -> "打开面板"
        "SERVICE_START" -> "服务启动"
        "FOREGROUND" -> "打开随贴"
        "MANUAL" -> "手动读取"
        "PROCESS_TEXT" -> "选中文字 › 存到随贴"
        "SHARE" -> "分享到随贴"
        else -> t
    }

    fun resultName(r: String) = when (r) {
        NEW -> "已记录"
        DUP -> "内容没变"
        EMPTY -> "没读到文字（剪贴板为空，或系统没放行）"
        NO_FOCUS -> "没读到（探针未获得焦点）"
        ADD_FAIL -> "没读到（探针窗口添加失败）"
        FALLBACK -> "没读到剪贴板，用了选中的文字"
        else -> r
    }

    fun isFailure(r: String) = r == NO_FOCUS || r == ADD_FAIL
}

/** 系统设置里是否开着随贴的无障碍服务（开着但 [ClipService.instance] 为空，说明服务被系统停掉了） */
fun Context.isClipServiceEnabledInSystem(): Boolean {
    val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(this, ClipService::class.java)
    return s.split(':').any { ComponentName.unflattenFromString(it) == me }
}
