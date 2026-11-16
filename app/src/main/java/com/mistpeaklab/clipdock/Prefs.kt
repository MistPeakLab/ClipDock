package com.mistpeaklab.clipdock

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/** 快捷键：一个主键 + 修饰键掩码 */
data class Hotkey(val keyCode: Int, val mods: Int) {
    fun matches(e: KeyEvent): Boolean = e.keyCode == keyCode && modsOf(e) == mods

    fun label(): String {
        val parts = mutableListOf<String>()
        if (mods and META != 0) parts += "Win"
        if (mods and CTRL != 0) parts += "Ctrl"
        if (mods and ALT != 0) parts += "Alt"
        if (mods and SHIFT != 0) parts += "Shift"
        parts += keyName(keyCode)
        return parts.joinToString(" + ")
    }

    companion object {
        /** 键名；字母数字自己算，避免某些环境下 keyCodeToString 只给出数字 */
        fun keyName(code: Int): String = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_GRAVE -> "`"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        }

        const val CTRL = 1
        const val ALT = 2
        const val SHIFT = 4
        const val META = 8

        fun modsOf(e: KeyEvent): Int {
            var m = 0
            if (e.isCtrlPressed) m = m or CTRL
            if (e.isAltPressed) m = m or ALT
            if (e.isShiftPressed) m = m or SHIFT
            if (e.isMetaPressed) m = m or META
            return m
        }

        fun isModifierKey(code: Int) = KeyEvent.isModifierKey(code) ||
            code == KeyEvent.KEYCODE_FUNCTION || code == KeyEvent.KEYCODE_CAPS_LOCK
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        // 第一次以 v0.6+ 运行时判断一次“全新安装 / 从 0.5.x 升级”，结果写进 install_kind，之后不再判断
        if (!sp.contains(K_INSTALL_KIND)) detectInstallKind(context)
    }

    var hotkey: Hotkey
        get() = Hotkey(sp.getInt("hk_key", KeyEvent.KEYCODE_V), sp.getInt("hk_mods", Hotkey.META))
        set(v) = sp.edit().putInt("hk_key", v.keyCode).putInt("hk_mods", v.mods).apply()

    var hotkeyEnabled by bool("hk_enabled", true)

    // 关闭方式
    var closeOnSelect by bool("close_select", true)
    var closeOnEsc by bool("close_esc", true)
    var closeOnOutside by bool("close_outside", true)
    var closeOnHotkey by bool("close_hotkey", true)
    var closeOnAppSwitch by bool("close_app_switch", false)

    // 粘贴
    var autoPaste by bool("auto_paste", true)

    /** 粘贴时卡片飞向光标的动画 */
    var pasteAnim by bool("paste_anim", true)

    /** 粘贴前清理格式（零宽字符、行尾空白、多余空行、首尾空白） */
    var cleanText by bool("clean_text", false)

    /** 多选合并分隔：0 换行 1 空一行 2 空格 3 直接连接 */
    var mergeMode by int("merge_mode", 0)

    /** 面板上次停留的标签：0 最近 1 收藏，2 起是各个分组 */
    var panelTab by int("panel_tab", 0)

    /** 记录页布局：0 聚拢（卡片墙，默认），1 详情（列表 + 右侧面板） */
    var recordsLayout by int("records_layout", 0)

    /** 点桌面图标时直接唤起悬浮面板（否则打开主界面） */
    var iconOpensPanel by bool("icon_opens_panel", false)

    /**
     * 补漏检测：切换应用、解锁、长按菜单点击、选区消失时也去读一次剪贴板。
     * 读的那一瞬间当前窗口会短暂失去焦点，个别游戏可能因此暂停，所以可以关。
     */
    var passiveCapture by bool("passive_capture", true)

    /**
     * 最多保留条数（不含收藏）。可以是任意正数，不限于 [MAX_ITEMS_OPTIONS] 里的档位；
     * [Int.MAX_VALUE] 表示不限。小于 1 的值按 1 存，免得误传 0 把记录全部清掉。
     */
    var maxItems: Int
        get() = sp.getInt("max_items", 200)
        set(v) = sp.edit().putInt("max_items", v.coerceAtLeast(1)).apply()

    // 面板位置（-1 表示用默认位置）
    var panelX by int("panel_x", -1)
    var panelY by int("panel_y", -1)

    fun resetPanelPosition() = sp.edit().remove("panel_x").remove("panel_y").remove("panel_w").remove("panel_h")
        .remove("panel_px").remove("panel_py").remove("panel_pw").remove("panel_ph").apply()

    // 面板尺寸（dp）
    var panelW by int("panel_w", 340)
    var panelH by int("panel_h", 420)

    // 手机竖屏（宽度 < 600dp）单独记一套位置和大小，和平板 / 横屏互不覆盖。-1 表示用默认
    var panelPhoneX by int("panel_px", -1)
    var panelPhoneY by int("panel_py", -1)
    var panelPhoneW by int("panel_pw", -1)
    var panelPhoneH by int("panel_ph", -1)

    /** 面板每条最多显示几行 */
    var previewLines by int("preview_lines", 2)

    /** 主题：0 跟随系统 1 浅色 2 深色 */
    var theme by int("theme", 0)

    /** 界面语言：0 跟随系统 1 中文 2 English */
    var language by int("language", 0)

    // ---------- 首次引导 ----------

    /** 首次引导是否已走完（或被跳过）。升级用户不看引导，见 [upgradedFromLegacy] */
    var onboardingDone by bool("onboarding_done", false)

    /** 引导里选的使用方式：[PROFILE_UNSET] / [PROFILE_TABLET_KEYBOARD] / [PROFILE_PHONE_TOUCH] / [PROFILE_JUST_LOOKING] */
    var usageProfile by int("usage_profile", PROFILE_UNSET)

    /** 是否从 0.5.x 及更早版本升级而来（全新安装为 false）。只在第一次以 v0.6+ 运行时判定一次 */
    val upgradedFromLegacy: Boolean
        get() = sp.getInt(K_INSTALL_KIND, INSTALL_FRESH) == INSTALL_UPGRADE

    /** 升级用户的一次性提示“有新功能可以打开”。界面显示过或用户点掉后置回 false */
    var newFeaturesHintPending by bool(K_HINT, false)

    // ---------- 隐私 ----------

    /** 验证一次身份后，多少分钟内不用再验证。0 = 每次都验证 */
    var unlockMinutes by int("unlock_minutes", 5)

    /** 在密码管理器里复制的内容、被来源应用标记为“敏感”的内容不记录 */
    var skipSensitiveSources by bool("skip_sensitive_sources", true)

    /** 主界面禁止截屏、不出现在最近任务的缩略图里 */
    var secureWindow by bool("secure_window", false)

    /** 识别出的验证码多少分钟后自动清除。0 = 不清除 */
    var otpMinutes by int("otp_minutes", 10)

    /** 应用内密码（设备没有锁屏密码时用它验证）：盐和结果都是 Base64 */
    var pinSalt by str("pin_salt")
    var pinHash by str("pin_hash")
    var pinFails by int("pin_fails", 0)
    var pinLockedUntil by long("pin_locked_until", 0L)

    // ---------- 备份 ----------

    /** 自动备份存到哪个文件夹（系统文件选择器给的地址），空 = 没设 */
    var backupTree by str("backup_tree")

    /** 自动备份：0 关 1 每天 2 每周 */
    var backupFreq by int("backup_freq", 0)

    /** 最多保留几份自动备份 */
    var backupKeep by int("backup_keep", 7)

    /** 自动备份加密用的盐，和用系统密钥库包好的密钥（都是 Base64）。空 = 备份不加密 */
    var backupSalt by str("backup_salt")
    var backupKey by str("backup_key")

    /** 上次自动备份：时间（毫秒，0 = 还没备份过）和结果（空 = 成功，否则是出错原因） */
    var lastBackupAt by long("last_backup_at", 0L)
    var lastBackupError by str("last_backup_error")
    var lastBackupTriedAt by long("last_backup_tried_at", 0L)

    // ---------- 简洁 / 全能模式与功能开关 ----------

    /**
     * 当前模式。它只是一个标签，真正起作用的是各个 feat_* 开关。
     * 直接赋值只改标签、不动开关；套用预设请用 [applyMode]，改单项请用 [setFeature]。
     */
    var mode: Mode
        get() = sp.getString(K_MODE, null)?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SIMPLE
        set(v) {
            sp.edit().putString(K_MODE, v.name).apply()
            Features.invalidate()
        }

    /**
     * 套用预设：SIMPLE / FULL 会把所有开关写成预设值；
     * CUSTOM 没有预设值，不动开关，只把标签改成“自定义”。
     */
    fun applyMode(mode: Mode) {
        val e = sp.edit().putString(K_MODE, mode.name)
        if (mode != Mode.CUSTOM) {
            for (f in Feature.entries) e.putBoolean(featKey(f), f.presetValue(mode))
        }
        e.apply()
        Features.invalidate()
    }

    /**
     * 某项功能是否打开（直接读存储）。调用频繁的地方请用带缓存的 [Features.isOn]。
     * 开关没写过时（比如以后版本新增的功能）按当前模式给默认值：全能为开，其余按简洁预设。
     */
    fun isOn(feature: Feature): Boolean = sp.getBoolean(featKey(feature), feature.presetValue(mode))

    /** 开关单项功能。改完后如果整体正好等于某个预设，模式显示为该预设，否则为“自定义” */
    fun setFeature(feature: Feature, on: Boolean) {
        val states = Feature.entries.associateWith { if (it == feature) on else isOn(it) }
        val shown = states.filterKeys { !it.reserved }
        val newMode = when {
            shown.all { (f, v) -> v == f.inSimple } -> Mode.SIMPLE
            shown.all { (_, v) -> v } -> Mode.FULL
            else -> Mode.CUSTOM
        }
        // 把全部开关都落盘，而不只是改动的那一项：
        // 否则没写过的开关会跟着模式标签的变化改变默认值，出现“关了一项，另一项也跟着变”
        val e = sp.edit().putString(K_MODE, newMode.name)
        for ((f, v) in states) e.putBoolean(featKey(f), v)
        e.apply()
        Features.invalidate()
    }

    private fun featKey(f: Feature) = "feat_" + f.key

    /**
     * 区分全新安装和升级。0.5.x 没有留下任何版本标记，只能靠旁证，满足任一条即视为升级：
     * - 设置文件里已经有内容（改过任何设置、拖过面板）；
     * - 旧版的记录文件 history.json 还在（没改过设置但用过的人）；
     * - 系统记录的首次安装时间和最近更新时间不一致（覆盖安装过）。
     * 宁可把新用户误判成升级（只是多一条提示），也不要把老用户当成新用户。
     *
     * 两种情况都先落到简洁模式：升级用户的界面和原来一样，另挂一次“有新功能可以打开”的提示；
     * 新用户等首次引导里自己选。这里只新增键，不改动任何已有设置。
     */
    private fun detectInstallKind(context: Context) {
        synchronized(LOCK) {
            if (sp.contains(K_INSTALL_KIND)) return
            val app = context.applicationContext ?: context
            val hadSettings = sp.all.isNotEmpty()
            val hadHistory = try {
                java.io.File(app.filesDir, "history.json").exists() || java.io.File(app.filesDir, "history.json.v05.bak").exists()
            } catch (_: Exception) { false }
            val updated = try {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                pi.firstInstallTime != pi.lastUpdateTime
            } catch (_: Exception) { false }
            val upgrade = hadSettings || hadHistory || updated

            val e = sp.edit()
                .putInt(K_INSTALL_KIND, if (upgrade) INSTALL_UPGRADE else INSTALL_FRESH)
                .putString(K_MODE, Mode.SIMPLE.name)
            for (f in Feature.entries) e.putBoolean(featKey(f), f.inSimple)
            if (upgrade) e.putBoolean(K_HINT, true).putBoolean("onboarding_done", true)
            e.apply()
        }
        // 放在锁外面：Features 有自己的锁，嵌套着拿容易和它的读取路径互相等
        Features.invalidate()
    }

    companion object {
        /** 设置文件名。[Features] 监听它的变化 */
        internal const val FILE = "settings"
        internal const val K_MODE = "feature_mode"
        private const val K_HINT = "new_features_hint"
        private const val K_INSTALL_KIND = "install_kind"
        private const val INSTALL_FRESH = 1
        private const val INSTALL_UPGRADE = 2
        private val LOCK = Any()

        const val PROFILE_UNSET = 0
        /** 平板 + 实体键盘 */
        const val PROFILE_TABLET_KEYBOARD = 1
        /** 手机触屏 */
        const val PROFILE_PHONE_TOUCH = 2
        /** 先看看 */
        const val PROFILE_JUST_LOOKING = 3

        /**
         * 设置页提供的条数档位。没有“不限”：记录是整个放在内存里的，无限增长迟早会把内存占满。
         * （程序内部仍然认 [Int.MAX_VALUE] 为不限。）
         */
        val MAX_ITEMS_OPTIONS = listOf(50, 100, 200, 500, 1000, 3000, 9999)

        /** 条数上限的显示文字 */
        fun maxItemsLabel(value: Int): String = if (value == Int.MAX_VALUE) tr("不限", "No limit") else value.toString()
    }

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) = sp.edit().putInt(key, value).apply()
    }

    private fun long(key: String, def: Long) = object : kotlin.properties.ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Long) = sp.edit().putLong(key, value).apply()
    }

    /** 字符串设置：空串和没设是一回事 */
    private fun str(key: String) = object : kotlin.properties.ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getString(key, "") ?: ""
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String) {
            if (value.isEmpty()) sp.edit().remove(key).apply() else sp.edit().putString(key, value).apply()
        }
    }
}
