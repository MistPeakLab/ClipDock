package com.mistpeaklab.clipdock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 可以单独开关的功能。每项对应 Prefs 里的一个布尔开关 feat_<key>。
 * 关闭的功能在面板、主界面、设置里都不出现入口，也不做对应的后台计算。
 *
 * key 一旦发布就不要改（它是存储用的键名）；新增功能往后加即可，
 * 没写过开关的新功能会按用户当前模式取默认值。
 *
 * @param inSimple 简洁模式下是否打开。简洁模式的原则：界面和 0.5.x 一样，只有记录、搜索、粘贴、收藏。
 *   所以凡是会在卡片、菜单、筛选栏上多出东西的一律关；只留“看不见、也不会让人意外”的两项：
 *   拼音搜索（只是让搜索多命中一些）和回收站（删错了还能找回，不去找它就不碍事）。
 *   敏感内容识别虽然也看不见，但它会改变条目的去留（验证码到期清除、卡号隐藏），
 *   万一误判会让人觉得“记录丢了”，所以简洁模式下不开，由用户自己决定。
 * @param reserved 预留项：功能还没做，设置页不要显示它的开关，[Features.isOn] 也一律当作关。
 */
enum class Feature(
    val key: String,
    private val zh: String,
    private val en: String,
    private val descZh: String,
    private val descEn: String,
    val inSimple: Boolean = false,
    val reserved: Boolean = false,
) {
    PASTE_QUEUE(
        "queue", "依次粘贴", "Paste queue",
        "多选几条后，每粘贴一次自动换到下一条，填表时不用来回切换。",
        "Select several items, then each paste gives you the next one. Handy for filling forms.",
    ),
    COLLECT(
        "collect", "连续收集", "Collect mode",
        "开启后接下来复制的内容自动合并成一条，适合从多处摘抄。",
        "While on, everything you copy is appended to one entry. Good for gathering excerpts.",
    ),
    CONTENT_KIND(
        "kind", "内容类型识别", "Content types",
        "认出网址、电话、邮箱、地址、单号、颜色，卡片上显示类型，可以按类型筛选，并提供打开、拨号等快捷动作。",
        "Recognises links, phone numbers, e-mails, addresses, tracking numbers and colours; adds a type filter and quick actions.",
    ),
    TRANSFORM(
        "transform", "粘贴前变换", "Transform before paste",
        "粘贴前可以去换行、转大小写、全角转半角、去掉网址跟踪参数等，不改原记录。",
        "Remove line breaks, change case, strip tracking parameters and more before pasting. The original stays untouched.",
    ),
    SOURCE_APP(
        "source", "来源应用", "Source app",
        "卡片上显示内容是从哪个应用复制的，并可以按来源筛选。",
        "Shows which app each item was copied from, and lets you filter by it.",
    ),
    SPLIT_MERGE(
        "split", "按行拆分与合并", "Split and merge",
        "把一条多行文字拆成多条，或把多条合并成一条。",
        "Split a multi-line item into separate items, or merge several into one.",
    ),
    NOTE(
        "note", "备注", "Notes",
        "给看不出含义的内容加一句备注，搜索时也能搜到。",
        "Add a short note to an item; notes are searchable.",
    ),
    TEMPLATE("template", "模板常用语", "Templates", "后续版本提供。", "Coming in a later version.", reserved = true),
    GROUP(
        "group", "分组", "Groups",
        "把收藏的内容分到不同的组里，面板里可以直接切到某一组。",
        "Sort favourites into groups; the panel gets a tab for each group.",
    ),
    LIBRARY("library", "文库", "Library", "后续版本提供。", "Coming in a later version.", reserved = true),
    TRASH(
        "trash", "回收站", "Trash",
        "删除的记录先进回收站，30 天内可以找回。关闭后删除即彻底删除。",
        "Deleted items are kept for 30 days. When off, deleting is permanent.",
        inSimple = true,
    ),
    PINYIN_SEARCH(
        "pinyin", "拼音搜索", "Pinyin search",
        "搜索时输入拼音或拼音首字母也能找到中文内容。",
        "Find Chinese text by typing pinyin or pinyin initials.",
        inSimple = true,
    ),
    SENSITIVE_DETECT(
        "sensitive", "敏感内容识别", "Sensitive content",
        "自动认出验证码和银行卡号：验证码过一会儿自动清除，卡号自动隐藏（需要同时打开“隐藏内容”）。",
        "Detects verification codes and bank card numbers: codes are removed after a while, card numbers are hidden (needs “Hidden items”).",
    ),
    HIDE(
        "hide", "隐藏内容", "Hidden items",
        "可以把某条内容隐藏起来：列表里不显示内容，查看或粘贴前要验证指纹或锁屏密码，并且加密保存。",
        "Hide an item: its content is not shown, viewing or pasting needs your fingerprint or screen lock, and it is stored encrypted.",
    );

    val title: String get() = tr(zh, en)
    val desc: String get() = tr(descZh, descEn)

    /** 这项功能在某个预设下的取值。CUSTOM 没有预设值，按简洁处理（只用作没写过开关时的默认值） */
    fun presetValue(mode: Mode): Boolean = !reserved && (if (mode == Mode.FULL) true else inSimple)
}

/** 功能预设：简洁（只开 [Feature.inSimple] 的几项）、全能（全部打开）、自定义（逐项调过，和两个预设都不一样） */
enum class Mode(private val zh: String, private val en: String, private val descZh: String, private val descEn: String) {
    SIMPLE("简洁模式", "Simple", "只有记录、搜索、粘贴、收藏，界面最清爽。", "Just history, search, paste and favourites."),
    FULL("全能模式", "Full", "打开全部功能。", "Everything turned on."),
    CUSTOM("自定义", "Custom", "按你逐项选择的功能。", "Your own selection.");

    val title: String get() = tr(zh, en)
    val desc: String get() = tr(descZh, descEn)
}

/**
 * 功能开关的快速读取入口。
 *
 * 无障碍服务每收到一个事件、列表每画一张卡片都可能来问，所以把全部开关读成一份内存快照，
 * 之后只是数组取值。设置里一改（[Prefs.applyMode] / [Prefs.setFeature]）就同步作废快照，
 * 下一次读取立即看到新值；另外挂一个 SharedPreferences 监听兜底，
 * 防止有代码（比如导入设置）绕过 Prefs 直接写文件。
 */
object Features {
    @Volatile private var cache: BooleanArray? = null
    private var watching: SharedPreferences? = null

    // SharedPreferences 只弱引用监听器，必须自己拿住，否则会被回收
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // key 为 null 表示整个文件被 clear()
        if (key == null || key.startsWith("feat_") || key == Prefs.K_MODE) invalidate()
    }

    private val _version = MutableStateFlow(0)

    /** 每次开关变化加一。Compose 界面 collectAsState 它，开关变化后就会重组 */
    val version: StateFlow<Int> = _version

    fun isOn(ctx: Context, feature: Feature): Boolean = (cache ?: load(ctx))[feature.ordinal]

    /**
     * 作废快照。和 load 里“读开关 + 存快照”用同一把锁：
     * 要么 load 先存完再被这里清掉，要么 load 在新值写入之后才读，不会留下旧值。
     */
    fun invalidate() {
        synchronized(this) { cache = null }
        _version.update { it + 1 }
    }

    private fun load(ctx: Context): BooleanArray {
        val app = ctx.applicationContext ?: ctx
        // 在锁外构造 Prefs：首次运行时它会写入默认开关并回调 invalidate()，放在锁里容易互相等
        val prefs = Prefs(app)
        return synchronized(this) {
            cache?.let { return it }
            if (watching == null) {
                val sp = app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                sp.registerOnSharedPreferenceChangeListener(listener)
                watching = sp
            }
            val all = Feature.entries
            BooleanArray(all.size) { !all[it].reserved && prefs.isOn(all[it]) }.also { cache = it }
        }
    }
}
