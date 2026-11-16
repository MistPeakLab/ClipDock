package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mistpeaklab.clipdock.util.ContentKind
import com.mistpeaklab.clipdock.util.Search
import com.mistpeaklab.clipdock.util.TextTransforms
import com.mistpeaklab.clipdock.util.Transform
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮剪贴板面板。
 * - 增强模式（无障碍）：不获取焦点，输入框不失焦、不弹输入法；键盘由服务转发 handleKey。
 * - 基础模式（悬浮窗权限）：可获焦，打开时能读当前剪贴板；键盘由窗口自己处理。
 * - 直接打字即搜索；Tab 在“最近 / 收藏 / 各分组”之间切换。
 * - 多选遵循常规习惯：Ctrl+A 全选、Ctrl+点击加选、Shift+点击 / Shift+↑↓ 连选；Ctrl+V 粘贴、Ctrl+C 复制。
 * - 平板和手机都是悬浮小窗：顶部把手拖动移动，右下角手柄缩放；两套位置和尺寸分开记。
 *
 * 0.6.2 外观重做（设计稿 design/0.6.2/panel）：
 * - 每行只有“光标所在 / 鼠标悬停”的那一行露出 拆开 / 收藏 / ⋯ 三个按钮，其他行干干净净；删除在 ⋯ 里或按 Del。
 * - 选中行是淡蓝底 + 左边一道蓝条；每行前面是序号（多选时是勾选圈）和类型小图标，第二行“类型 · 时间 · 来源”。
 * - 头部：分段标签、带状态的“收集”胶囊、⋯ 菜单（清空要确认）、打开主界面、关闭。
 * - 搜索命中的字（含拼音、首字母命中的汉字）标黄；底部一条快捷键提示，多选时换成操作条。
 * - 拆开挑选、变换后粘贴都在面板里换一页，变换页和主界面的变换弹窗同一套：分组、卡片、对照预览。
 */
class PanelController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val windowType: Int,
    private val focusable: Boolean,
    private val onAct: (text: String, mode: Int, cards: () -> List<FlyCard>) -> Unit,
    private val onFocused: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dark = when (prefs.theme) {
        1 -> false; 2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // 配色：和主界面同一套中性灰 + 一个强调蓝。面板比背后的应用亮一档、带细描边和大阴影，叠在什么上面都分得清
    private val cPanel = if (dark) 0xFF202227.toInt() else 0xFFFFFFFF.toInt()
    private val cLine = if (dark) 0x24FFFFFF else 0x1C141E3C
    private val cField = if (dark) 0xFF2A2D34.toInt() else 0xFFF0F2F6.toInt()
    private val cSegOn = if (dark) 0xFF3A3E47.toInt() else 0xFFFFFFFF.toInt()
    private val cText = if (dark) 0xFFF0F2F5.toInt() else 0xFF1F2328.toInt()
    private val cSub = if (dark) 0xFFB4BAC4.toInt() else 0xFF5F6672.toInt()
    private val cMute = if (dark) 0xFF7E8590.toInt() else 0xFF9AA0A6.toInt()
    private val cAccent = 0xFF4C8DF6.toInt()
    private val cTint = if (dark) 0x334C8DF6 else 0x1F4C8DF6
    private val cSelBg = if (dark) 0xFF26334A.toInt() else 0xFFEAF2FE.toInt()
    private val cOk = 0xFF2FA866.toInt()
    private val cDanger = 0xFFE5484D.toInt()
    private val cMark = if (dark) 0x66D4A72C else 0x99FFE08A.toInt()

    private val shadow = 16
    private val minW = 260; private val minH = 240
    private val maxRows = 200
    private val newlines = Regex("\\s*\\n\\s*")

    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var searchBox: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchTip: TextView
    private lateinit var tabBox: LinearLayout
    private lateinit var collectBtn: TextView
    private lateinit var footWrap: LinearLayout
    private lateinit var selWrap: LinearLayout
    private lateinit var selLabel: TextView
    private lateinit var lp: WindowManager.LayoutParams

    /** 标签：最近、收藏、各个分组（分组功能打开时） */
    private class TabDef(val key: String, val label: String)
    private var tabs: List<TabDef> = emptyList()
    private var tab = 0
    private var query = ""
    private var editing = false
    private var items: List<ClipItem> = emptyList()
    private val picked = LinkedHashSet<String>() // 多选
    private var anchor = -1 // Shift 连选的起点
    private var ctrlHeld = false
    private var shiftHeld = false
    private var touchMeta = 0 // 触摸按下时键盘修饰键状态
    private var selected = 0
    private var picker: PiecePicker? = null // 拆开挑选
    private lateinit var pickBox: LinearLayout
    private lateinit var pickScroll: ScrollView
    private lateinit var pickLabel: TextView
    /** 正在选“粘贴前变换”：要变换的文字 */
    private var transformFor: String? = null
    private var shown = false
    private var menu: View? = null
    private var wDp = if (phone) prefs.panelPhoneW else prefs.panelW
    private var hDp = if (phone) prefs.panelPhoneH else prefs.panelH
    private val listener: () -> Unit = { root.post { if (transformFor == null && picker == null) refresh() } }

    private val dm get() = ctx.resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private fun toDp(px: Int) = (px / dm.density).toInt()

    /** 手机竖屏：和平板一样是悬浮小窗（0.6.2 起不再默认贴底占半屏），位置和大小单独记一套 */
    private val phone get() = toDp(dm.widthPixels) < 600

    /** 底部提示说键盘还是说触屏：平板、或者手机接了实体键盘时说键盘 */
    private val keys get() = !phone || ctx.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY

    private fun on(f: Feature) = Features.isOn(ctx, f)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        clampSize()
        root = object : FrameLayout(ctx) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (menu != null) closeMenu()
                    else if (prefs.closeOnOutside) dismiss()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                handleModifier(event)
                // 窗口自己有焦点时（基础模式 / 正在用输入法搜索），键盘事件到这里
                if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    when {
                        menu != null -> closeMenu()
                        transformFor != null -> closeTransform()
                        picker != null -> closePicker()
                        editing || query.isNotEmpty() -> clearSearch()
                        else -> dismiss()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) onFocused()
            }
        }

        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cPanel, dp(20).toFloat(), cLine)
            elevation = dp(14).toFloat()
            setPadding(dp(10), 0, dp(10), 0)
            clipToOutline = true
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            setMargins(dp(shadow), dp(shadow), dp(shadow), dp(shadow))
        })

        // 顶部把手：明确告诉用户“这里可以拖”。标题栏里有可横向滑动的标签，手指按在标签上会被滑动吃掉，所以单独留一条
        val handle = FrameLayout(ctx).apply {
            addView(View(ctx).apply { background = round(cMute and 0x00FFFFFF or 0x80000000.toInt(), dp(2).toFloat()) },
                FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        panel.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (phone) 18 else 14)))
        enableDrag(handle)

        // 标题栏：分段标签 | 收集 · ⋯ · 打开主界面 · 关闭。空白处也能拖动
        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(cField, dp(11).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        header.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBox)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        collectBtn = TextView(ctx).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
            setPadding(dp(11), 0, dp(12), 0)
            setOnClickListener { toggleCollect() }
        }
        if (on(Feature.COLLECT)) header.addView(collectBtn, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(2) })
        header.addView(iconButton(R.drawable.ic_gen_more, tr("更多", "More")) { showHeaderMenu(it) })
        header.addView(iconButton(R.drawable.ic_gen_open, tr("打开主界面", "Open ClipDock")) {
            dismiss()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(iconButton(R.drawable.ic_gen_close, tr("关闭", "Close")) { dismiss() })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        enableDrag(header)

        // 搜索框：直接打字即可；点一下可用输入法（中文）
        searchBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(cField, dp(12).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { startEditing() }
        }
        searchBox.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_gen_search); imageTintList = ColorStateList.valueOf(cMute)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        search = EditText(ctx).apply {
            hint = tr("直接打字搜索", "Type to search")
            setHintTextColor(cMute); setTextColor(cText); textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(dp(8), 0, dp(8), 0)
            isFocusable = false; isFocusableInTouchMode = false
            setOnClickListener { startEditing() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    if (q != query) { query = q; selected = 0; refresh() }
                }
            })
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        searchTip = TextView(ctx).apply { setTextColor(cMute); textSize = 11.5f; maxLines = 1 }
        searchBox.addView(searchTip)
        panel.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })

        scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildFooter()
        buildSelBar()
        buildPickBox()
        buildTransformBox()

        val grip = ResizeGrip(ctx, cMute)
        // 子控件按高度（elevation）排先后：面板有 14dp 阴影，手柄不抬高的话会被面板盖住，既看不见也拖不动
        grip.elevation = dp(15).toFloat()
        root.addView(grip, FrameLayout.LayoutParams(dp(if (phone) 32 else 24), dp(if (phone) 32 else 24), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(shadow + 2), dp(shadow + 2))
        })
        enableResize(grip)

        lp = WindowManager.LayoutParams(
            dp(wDp + shadow * 2), dp(hDp + shadow * 2),
            windowType, baseFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = if (phone) prefs.panelPhoneX else prefs.panelX
            val sy = if (phone) prefs.panelPhoneY else prefs.panelY
            x = if (sx >= 0) sx else (dm.widthPixels - width) / 2
            // 手机默认在屏幕上半部（输入法一般在下半部），平板默认居中
            y = if (sy >= 0) sy else if (phone) dp(56) else (dm.heightPixels - height) / 2
            softInputMode = if (phone) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = android.R.style.Animation_Dialog
        }
        clampPos()
        wm.addView(root, lp)
        shown = true
        ClipStore.addListener(listener)
        buildTabs()
        refresh()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        ClipStore.removeListener(listener)
        if (editing) hideIme()
        runCatching { wm.removeView(root) }
        onDismiss()
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        f = f or if (focusable) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return f
    }

    // ---------------- 底部：快捷键提示 / 多选操作条 ----------------

    /** 横贯面板的一条底栏：上面一道细线，左右顶到面板边（面板本身左右有 10dp 内边距） */
    private fun bottomBar(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(ctx).apply { setBackgroundColor(cLine) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun barParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = -dp(10); marginEnd = -dp(10)
    }

    private fun buildFooter() {
        footWrap = bottomBar()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(if (phone) 34 else 28), 0)
        }
        val act = if (prefs.autoPaste) tr("粘贴", "paste") else tr("复制", "copy")
        fun label(s: String) = row.addView(TextView(ctx).apply { text = s; setTextColor(cMute); textSize = 11.5f; maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        fun dot() = row.addView(TextView(ctx).apply { text = "·"; setTextColor(cMute); alpha = 0.6f; textSize = 11.5f },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8); marginEnd = dp(8) })
        if (keys) {
            row.addView(keycap("Enter")); label(act); dot()
            row.addView(keycap("→")); label(tr("拆开", "pick part")); dot()
            row.addView(keycap("Ctrl")); label(tr("+点击 多选", "+click multi-select")); dot()
            row.addView(keycap("Del")); label(tr("删除", "delete"))
        } else {
            row.addView(TextView(ctx).apply {
                text = (if (prefs.autoPaste) tr("点一下粘贴", "Tap to paste") else tr("点一下复制", "Tap to copy")) +
                    tr(" · 长按多选 · 拖顶部把手移动", " · long-press to multi-select · drag the top bar to move")
                setTextColor(cMute); textSize = 11.5f; maxLines = 1
            })
        }
        footWrap.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, FrameLayout.LayoutParams(-2, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        panel.addView(footWrap, barParams())
    }

    private fun buildSelBar() {
        selWrap = bottomBar()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(if (phone) 34 else 28), dp(8))
        }
        // 按钮多，放不下时左边这段可以左右滑；“粘贴”固定在最右，手机上也一直看得到
        val pills = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pills.addView(iconButton(R.drawable.ic_gen_close, tr("取消多选（Esc）", "Clear selection (Esc)"), small = true) { picked.clear(); refresh() })
        selLabel = TextView(ctx).apply { setTextColor(cText); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
        pills.addView(selLabel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        if (on(Feature.PASTE_QUEUE)) pills.addView(pill(tr("依次粘贴", "One by one")) { startQueue() })
        if (on(Feature.SPLIT_MERGE)) pills.addView(pill(tr("合并", "Merge")) { mergePicked() })
        if (on(Feature.TRANSFORM)) pills.addView(pill(tr("变换", "Transform"), icon = R.drawable.ic_gen_transform) { transformSelection() })
        pills.addView(pill(tr("复制", "Copy")) { actOnSelection(MODE_COPY) })
        pills.addView(iconButton(R.drawable.ic_gen_trash, tr("删除（Del）", "Delete (Del)"), small = true) {
            val ids = picked.toSet(); picked.clear(); deleteWithUndo(ids)
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) })
        bar.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(tr("粘贴", "Paste"), primary = true, cap = if (keys) "Ctrl+V" else null) { actOnSelection(MODE_PASTE) })
        selWrap.addView(bar)
        selWrap.visibility = View.GONE
        panel.addView(selWrap, barParams())
    }

    /** 列表页的底栏：多选时是操作条，否则是快捷键提示；拆开 / 变换页里两个都不显示 */
    private fun updateBars() {
        val inPage = picker != null || transformFor != null
        selWrap.visibility = if (!inPage && picked.isNotEmpty()) View.VISIBLE else View.GONE
        footWrap.visibility = if (!inPage && picked.isEmpty()) View.VISIBLE else View.GONE
        selLabel.text = tr("已选 %d 条", "%d selected", picked.size)
    }

    /** 在列表页和“拆开 / 变换”页之间切换：页里不显示标签、搜索框和列表 */
    private fun showPage(page: View?) {
        val list = page == null
        header.visibility = if (list) View.VISIBLE else View.GONE
        searchBox.visibility = if (list) View.VISIBLE else View.GONE
        scroll.visibility = if (list) View.VISIBLE else View.GONE
        pickBox.visibility = if (page === pickBox) View.VISIBLE else View.GONE
        tfBox.visibility = if (page === tfBox) View.VISIBLE else View.GONE
        updateBars()
    }

    // ---------------- 搜索 ----------------

    /** 点搜索框：临时让窗口可获焦并允许输入法，方便输入中文 */
    private fun startEditing() {
        if (editing) return
        editing = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        runCatching { wm.updateViewLayout(root, lp) }
        search.isFocusable = true; search.isFocusableInTouchMode = true
        search.requestFocus()
        search.setSelection(search.text.length)
        search.post { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0) }
    }

    private fun stopEditing() {
        if (!editing) return
        editing = false
        hideIme()
        search.clearFocus()
        search.isFocusable = false; search.isFocusableInTouchMode = false
        lp.flags = baseFlags()
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun hideIme() = ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)

    private fun setQuery(q: String) {
        search.setText(q)
        search.setSelection(q.length)
    }

    private fun clearSearch() {
        setQuery("")
        stopEditing()
    }

    // ---------------- 标签 ----------------

    private fun buildTabs() {
        val l = arrayListOf(TabDef("recent", tr("最近", "Recent")), TabDef("fav", tr("收藏", "Favourites")))
        if (on(Feature.GROUP)) ClipStore.groups.value.forEach { l += TabDef("g:" + it.id, it.name) }
        tabs = l
        tab = prefs.panelTab.coerceIn(0, tabs.lastIndex)
        tabBox.removeAllViews()
        tabs.forEachIndexed { i, t -> tabBox.addView(tabView(t.label) { switchTab(i) }) }
        updateTabs()
        updateCollect()
    }

    private fun switchTab(t: Int) {
        if (tab == t || t !in tabs.indices) return
        tab = t; prefs.panelTab = t
        selected = 0; picked.clear()
        updateTabs(); refresh()
    }

    private fun updateTabs() {
        for (i in 0 until tabBox.childCount) {
            val v = tabBox.getChildAt(i) as TextView
            val on = tab == i
            v.setTextColor(if (on) cText else cSub)
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.background = if (on) round(cSegOn, dp(8).toFloat()) else null
            v.elevation = if (on && !dark) dp(1).toFloat() else 0f
        }
        (tabBox.getChildAt(tab))?.let { v -> (tabBox.parent as? HorizontalScrollView)?.post { (tabBox.parent as HorizontalScrollView).smoothScrollTo(v.left - dp(20), 0) } }
    }

    private fun base(): List<ClipItem> {
        val key = tabs.getOrNull(tab)?.key ?: "recent"
        return when {
            key == "fav" -> ClipStore.favorites()
            key.startsWith("g:") -> key.substring(2).let { gid -> ClipStore.items.value.filter { it.groupId == gid } }
            else -> ClipStore.history()
        }
    }

    // ---------------- 连续收集 ----------------

    private fun toggleCollect() {
        if (ClipStore.collect.value == null) {
            ClipStore.startCollect()
            Toast.makeText(ctx, tr("开始连续收集：接下来复制的内容会合并成一条", "Collecting: what you copy next is joined into one item"), Toast.LENGTH_SHORT).show()
        } else {
            ClipStore.stopCollect()
            Toast.makeText(ctx, tr("收集结束", "Collecting stopped"), Toast.LENGTH_SHORT).show()
        }
        updateCollect()
    }

    /** 关着：描边胶囊 + 灰点“收集”；开着：浅蓝胶囊 + 蓝点“收集中 N”，一眼看得出它在工作 */
    private fun updateCollect() {
        val c = ClipStore.collect.value
        val onNow = c != null
        collectBtn.text = if (c == null) tr("收集", "Collect") else tr("收集中 %d", "Collecting %d", c.parts)
        collectBtn.setTextColor(if (onNow) cAccent else cSub)
        collectBtn.typeface = if (onNow) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        collectBtn.background = if (onNow) round(cTint, dp(15).toFloat()) else round(Color.TRANSPARENT, dp(15).toFloat(), cLine, dp(1))
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (onNow) cAccent else cMute); setSize(dp(6), dp(6)) }
        collectBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        val tip = if (onNow) tr("点一下结束收集", "Tap to stop collecting") else tr("连续收集：接下来复制的内容合并成一条", "Collect: join what you copy next into one item")
        collectBtn.contentDescription = tip; collectBtn.tooltipText = tip
    }

    // ---------------- 列表 ----------------

    private fun refresh() {
        if (!shown) return
        updateCollect()
        val base = base()
        val q = query.trim()
        val terms = if (q.isEmpty()) emptyList() else Search.terms(q)
        val pinyin = on(Feature.PINYIN_SEARCH)
        items = if (q.isEmpty()) base else base.filter { it.matches(terms, pinyin) }
        picked.retainAll(base.map { it.id }.toSet())
        selected = selected.coerceIn(0, max(0, items.size - 1))
        list.removeAllViews()
        updateBars()
        searchTip.text = when {
            q.isNotEmpty() -> tr("找到 %d 条", "%d found", items.size)
            pinyin && !phone -> tr("拼音、首字母都行", "pinyin works too")
            else -> ""
        }

        if (items.isEmpty()) {
            list.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_empty)
                imageTintList = ColorStateList.valueOf(cMute)
                alpha = 0.6f
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(32) })
            list.addView(TextView(ctx).apply {
                text = when {
                    q.isNotEmpty() -> tr("没有包含“%s”的内容", "Nothing matches “%s”", q)
                    !ClipStore.loaded.value -> tr("正在读取记录…", "Loading…")
                    tabs.getOrNull(tab)?.key == "fav" -> tr("还没有收藏\n在主界面给记录点 ☆，或新增常用语", "No favourites yet\nStar an item or add a phrase in ClipDock")
                    tabs.getOrNull(tab)?.key?.startsWith("g:") == true -> tr("这个分组还是空的", "This group is empty")
                    else -> tr("还没有记录\n复制一些文字后会出现在这里", "Nothing yet\nCopied text shows up here")
                }
                setTextColor(cSub); textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(32))
            })
            return
        }
        items.take(maxRows).forEachIndexed { i, item -> list.addView(itemView(i, item, terms, pinyin)) }
        ensureVisible()
    }

    /** 光标行：淡蓝底 + 左边一道蓝条；多选选中的行：只有淡蓝底 */
    private fun rowBackground(cursor: Boolean, isPicked: Boolean): Drawable? {
        val r = dp(12).toFloat()
        if (!cursor) return if (isPicked) round(cSelBg, r) else null
        val bar = GradientDrawable().apply { setColor(cAccent); cornerRadius = dp(2).toFloat() }
        return LayerDrawable(arrayOf(round(cSelBg, r), bar)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(3))
            setLayerInsetTop(1, dp(10)); setLayerInsetBottom(1, dp(10))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun itemView(index: Int, item: ClipItem, terms: List<String>, pinyin: Boolean): View {
        val cursor = index == selected
        val isPicked = item.id in picked
        val multi = picked.isNotEmpty()
        val kind = if (on(Feature.CONTENT_KIND)) item.kind else ContentKind.TEXT
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(6), dp(9))
            background = RippleDrawable(ColorStateList.valueOf(0x264C8DF6), rowBackground(cursor, isPicked), round(Color.WHITE, dp(12).toFloat()))
            setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchMeta = ev.metaState; false }
            setOnClickListener { onRowClick(index) }
            setOnLongClickListener { togglePick(index); true }
        }

        // 最前面：序号 1–9（搜索时不显示，因为 1–9 这时是在打字）；多选时换成勾选圈
        if (multi) {
            row.addView(FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isPicked) setColor(cAccent) else { setColor(Color.TRANSPARENT); setStroke(dp(1.5f), cMute) }
                }
                if (isPicked) addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_gen_check); imageTintList = ColorStateList.valueOf(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(2); marginEnd = dp(10) })
        } else {
            row.addView(TextView(ctx).apply {
                text = if (index < 9 && query.isEmpty()) "${index + 1}" else ""
                setTextColor(if (cursor) cAccent else cMute); textSize = 11.5f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }

        row.addView(kindTile(item, kind), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })

        val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val shownText = if (item.hidden) tr("已隐藏的内容", "Hidden item") else item.preview.replace(newlines, " ⏎ ")
        textCol.addView(TextView(ctx).apply {
            val blue = !item.hidden && (kind == ContentKind.URL || kind == ContentKind.EMAIL)
            setTextColor(if (item.hidden) cSub else if (blue) cAccent else cText); textSize = 14f
            maxLines = prefs.previewLines; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            val marks = if (item.hidden || terms.isEmpty()) emptyList() else Search.highlight(shownText, terms, pinyin)
            text = if (marks.isEmpty()) shownText else SpannableString(shownText).apply {
                marks.forEach { setSpan(BackgroundColorSpan(cMark), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        })
        // 第二行小字：备注、类型、时间、来源应用（相应功能打开时）
        val meta = buildList {
            if (on(Feature.NOTE) && item.note != null) add("✎ " + item.note)
            if (on(Feature.CONTENT_KIND)) add(Kinds.label(kind))
            add(timeLabel(item.time))
            if (on(Feature.SOURCE_APP) && item.sourcePkg != null) add(AppInfo.label(ctx, item.sourcePkg))
        }
        textCol.addView(TextView(ctx).apply {
            text = meta.joinToString(" · ")
            setTextColor(cMute); textSize = 11.5f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 收藏标记：没露出按钮时，收藏过的行尾放一颗小实心星
        val pinMark = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_gen_star_filled); imageTintList = ColorStateList.valueOf(cAccent)
        }
        row.addView(pinMark, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8); marginEnd = dp(8) })

        // 行内按钮：只在光标行（或鼠标悬停的行）露出；多选时都不露
        val acts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!item.hidden && item.preview.length >= 12) {
            acts.addView(iconButton(R.drawable.ic_gen_pick, tr("拆开挑选（→）", "Pick part (→)"), small = true) { selected = index; openPicker(item) })
        }
        if (!item.phrase) {
            acts.addView(iconButton(if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star,
                if (item.pinned) tr("取消收藏", "Unstar") else tr("收藏（Ctrl+P）", "Star (Ctrl+P)"), small = true,
                tint = if (item.pinned) cAccent else cSub) { ClipStore.togglePin(item.id) })
        }
        acts.addView(iconButton(R.drawable.ic_gen_more, tr("更多", "More"), small = true) { showRowMenu(it, index, item) })
        row.addView(acts, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })

        fun showActs(v: Boolean) {
            acts.visibility = if (v && !multi) View.VISIBLE else View.GONE
            pinMark.visibility = if (item.pinned && acts.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }
        showActs(cursor)
        row.setOnHoverListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> showActs(true)
                // 指针移到行里的按钮上时，行本身也会收到 EXIT：还在行内就不收起
                MotionEvent.ACTION_HOVER_EXIT -> if (ev.x < 0 || ev.y < 0 || ev.x >= v.width || ev.y >= v.height) showActs(cursor)
            }
            false
        }

        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
        return row
    }

    /** 类型小方块：网址、邮箱蓝色，电话、地址绿色，颜色直接画出那个颜色，其他灰色 */
    private fun kindTile(item: ClipItem, kind: ContentKind): View {
        val tile = FrameLayout(ctx)
        val color = if (!item.hidden && kind == ContentKind.COLOR) runCatching { Color.parseColor(item.text.trim()) }.getOrNull() else null
        val (icon, tint) = when {
            item.hidden -> R.drawable.ic_gen_lock to cSub
            kind == ContentKind.URL -> R.drawable.ic_kind_link to cAccent
            kind == ContentKind.EMAIL -> R.drawable.ic_kind_at to cAccent
            kind == ContentKind.PHONE -> R.drawable.ic_kind_phone to cOk
            kind == ContentKind.ADDRESS -> R.drawable.ic_kind_place to cOk
            kind == ContentKind.NUMBER || kind == ContentKind.TRACKING_NO -> R.drawable.ic_kind_number to cSub
            else -> R.drawable.ic_kind_text to cSub
        }
        tile.background = round(if (tint == cSub || color != null) cField else (tint and 0x00FFFFFF) or (if (dark) 0x33000000 else 0x1F000000), dp(10).toFloat())
        if (color != null) {
            tile.addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(1, cLine) }
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        } else {
            tile.addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(tint) },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
        return tile
    }

    // ---------------- 小菜单（头部 ⋯、行内 ⋯） ----------------

    private class MenuEntry(val label: String, val icon: Int? = null, val danger: Boolean = false, val checked: Boolean? = null, val act: () -> Unit)

    /**
     * 面板里的小菜单：画在面板窗口自己里面（悬浮窗再弹 PopupWindow 在一些系统上弹不出来）。
     * 点菜单外任何地方、按 Esc / 返回都会收起。
     */
    private fun showMenu(anchor: View, entries: List<MenuEntry>) {
        closeMenu()
        // 抬得比面板高（面板 14dp），否则会被面板盖住
        val scrim = FrameLayout(ctx).apply { elevation = dp(20).toFloat(); setOnClickListener { closeMenu() } }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cPanel, dp(14).toFloat(), cLine)
            elevation = dp(12).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            minimumWidth = dp(190)
        }
        entries.forEach { e ->
            val color = if (e.danger) cDanger else cText
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(14), 0)
                background = RippleDrawable(ColorStateList.valueOf(0x264C8DF6), null, round(Color.WHITE, dp(9).toFloat()))
                val ic = when { e.checked == true -> R.drawable.ic_gen_check; e.checked == false -> null; else -> e.icon }
                addView(ImageView(ctx).apply {
                    if (ic != null) setImageResource(ic)
                    imageTintList = ColorStateList.valueOf(if (e.checked == true) cAccent else if (e.danger) cDanger else cSub)
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
                addView(TextView(ctx).apply { text = e.label; setTextColor(color); textSize = 13.5f; maxLines = 1 })
                setOnClickListener { closeMenu(); e.act() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        scrim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = scrim

        val a = IntArray(2); anchor.getLocationInWindow(a)
        val r = IntArray(2); root.getLocationInWindow(r)
        card.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val edge = dp(shadow + 4)
        val x = (a[0] - r[0] + anchor.width - card.measuredWidth).coerceIn(edge, max(edge, root.width - card.measuredWidth - edge))
        var y = a[1] - r[1] + anchor.height + dp(2)
        if (y + card.measuredHeight > root.height - edge) y = a[1] - r[1] - card.measuredHeight - dp(2)
        (card.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x; topMargin = y.coerceAtLeast(edge) }
    }

    private fun closeMenu() {
        val m = menu ?: return
        menu = null
        root.removeView(m)
    }

    private fun showHeaderMenu(anchor: View) {
        showMenu(anchor, listOf(
            MenuEntry(tr("选中一条后自动收起", "Close after picking an item"), checked = prefs.closeOnSelect) {
                prefs.closeOnSelect = !prefs.closeOnSelect
            },
            MenuEntry(tr("清空记录…", "Clear history…"), R.drawable.ic_gen_trash, danger = true) {
                // 清空是一下子删很多条，再确认一次
                showMenu(anchor, listOf(
                    MenuEntry(tr("确定清空（收藏的会保留）", "Clear now (favourites stay)"), R.drawable.ic_gen_trash, danger = true) {
                        ClipStore.clearUnpinned()
                    },
                    MenuEntry(tr("取消", "Cancel"), R.drawable.ic_gen_close) {},
                ))
            },
        ))
    }

    private fun showRowMenu(anchor: View, index: Int, item: ClipItem) {
        selected = index
        showMenu(anchor, buildList {
            add(MenuEntry(tr("粘贴", "Paste"), R.drawable.ic_paste) { actOn(listOf(item), MODE_PASTE) })
            add(MenuEntry(tr("复制", "Copy"), R.drawable.ic_gen_copy) { actOn(listOf(item), MODE_COPY) })
            if (on(Feature.TRANSFORM) && !item.hidden) add(MenuEntry(tr("变换后粘贴…", "Transform & paste…"), R.drawable.ic_gen_transform) { openTransform(listOf(item)) })
            add(MenuEntry(tr("多选", "Select"), R.drawable.ic_gen_check) { togglePick(index) })
            if (!item.phrase) add(MenuEntry(tr("删除", "Delete"), R.drawable.ic_gen_trash, danger = true) { deleteWithUndo(listOf(item.id)) })
        })
    }

    // ---------------- 选择与动作 ----------------

    private fun visibleCount() = minOf(items.size, maxRows)

    private fun onRowClick(index: Int) {
        val ctrl = ctrlHeld || (touchMeta and KeyEvent.META_CTRL_ON) != 0
        val shift = shiftHeld || (touchMeta and KeyEvent.META_SHIFT_ON) != 0
        when {
            ctrl -> togglePick(index)
            shift -> selectRange(index)
            // 已经在多选时，点一下就是加选 / 取消，免得手机上一点就把选好的全贴出去
            picked.isNotEmpty() -> togglePick(index)
            else -> items.getOrNull(index)?.let { selected = index; actOn(listOf(it), MODE_DEFAULT) }
        }
    }

    private fun togglePick(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!picked.remove(item.id)) picked += item.id
        anchor = index; selected = index
        refresh()
    }

    private fun selectRange(to: Int) {
        val from = if (anchor in items.indices) anchor else selected
        for (i in minOf(from, to)..maxOf(from, to)) items.getOrNull(i)?.let { picked += it.id }
        selected = to
        refresh()
    }

    private fun selectAll() {
        picked.clear()
        items.forEach { picked += it.id }
        refresh()
    }

    /** 有多选时是选中的全部（按列表顺序），否则是光标所在的一条 */
    private fun targets(): List<ClipItem> =
        if (picked.isNotEmpty()) base().filter { it.id in picked } else listOfNotNull(items.getOrNull(selected))

    private fun actOnSelection(mode: Int) = actOn(targets(), mode)

    /**
     * 对几条做“粘贴 / 复制”。里面有隐藏的内容时先收起面板去验证身份，验证完再贴：
     * 验证界面会把焦点从输入框拿走，面板留着也没用。
     */
    private fun actOn(list: List<ClipItem>, mode: Int) {
        if (list.isEmpty()) return
        val ids = list.mapTo(HashSet()) { it.id }
        val clicked = items.getOrNull(selected)?.id
        if (list.any { it.hidden }) {
            picked.clear()
            dismiss()
            Unlock.run(ctx) {
                val texts = list.mapNotNull { ClipStore.plain(it) }
                if (texts.isNotEmpty()) PanelHost.deliver(ctx, TextTools.join(texts, prefs.mergeMode), mode)
            }
            return
        }
        val text = TextTools.join(list.map { it.text }, prefs.mergeMode)
        picked.clear()
        onAct(text, mode) { snapshot(ids, clicked) }
        if (!shown) return
        if (prefs.closeOnSelect) dismiss() else { stopEditing(); refresh() }
    }

    /** 删除并给出 5 秒撤销（回收站开着时） */
    private fun deleteWithUndo(ids: Collection<String>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        ClipStore.deleteAll(list.toSet())
        Undo.offer(ctx, list)
    }

    /** 依次粘贴：先贴第一条，之后每点一次“下一条”贴下一条 */
    private fun startQueue() {
        val list = targets()
        if (list.size < 2) { Toast.makeText(ctx, tr("先多选几条", "Select several items first"), Toast.LENGTH_SHORT).show(); return }
        picked.clear()
        dismiss()
        val go = { PasteQueue.start(ctx, list.mapNotNull { ClipStore.plain(it) }.map { TextTools.prepare(prefs, it) }, pasteFirst = true) }
        if (list.any { it.hidden }) Unlock.run(ctx, go) else go()
    }

    private fun mergePicked() {
        val ids = targets().map { it.id }
        if (ClipStore.merge(ids, TextTools.separator(prefs.mergeMode)) != null) {
            Toast.makeText(ctx, tr("已合并成一条，放在最前面", "Merged into one item at the top"), Toast.LENGTH_SHORT).show()
            picked.clear()
            if (tab != 0) switchTab(0) else refresh()
        } else Toast.makeText(ctx, tr("至少选两条（隐藏的不参与）", "Select at least two (hidden items don't count)"), Toast.LENGTH_SHORT).show()
    }

    // ---------------- 页头（拆开 / 变换页共用） ----------------

    private fun pageHead(title: String, sub: String?, onBack: () -> Unit, trailing: View? = null): LinearLayout {
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(10), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, round(Color.WHITE, dp(16).toFloat()))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_p_back); imageTintList = ColorStateList.valueOf(cSub) },
                LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(TextView(ctx).apply { text = tr("返回", "Back"); setTextColor(cSub); textSize = 13.5f },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2) })
            contentDescription = tr("返回（Esc）", "Back (Esc)"); tooltipText = contentDescription
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(-2, dp(32)))
        head.addView(TextView(ctx).apply {
            text = title; setTextColor(cText); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        head.addView(TextView(ctx).apply {
            text = sub ?: ""; setTextColor(cMute); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(4) })
        if (trailing != null) head.addView(trailing)
        head.addView(iconButton(R.drawable.ic_gen_close, tr("关闭", "Close")) { dismiss() })
        enableDrag(head)
        return head
    }

    // ---------------- 粘贴前变换（和主界面的变换弹窗同一套：分组、卡片、对照预览） ----------------

    private lateinit var tfBox: LinearLayout
    private lateinit var tfGroups: LinearLayout
    private lateinit var tfGrid: LinearLayout
    private lateinit var tfBefore: TextView
    private lateinit var tfAfter: TextView
    private lateinit var tfScroll: ScrollView
    private var tfGroup = TGroup.CLEAN
    private var tfChosen: Transform? = null
    /** 每种变换在这段文字（前 [PROBE_CHARS] 字）上的试算结果，后台算完才有 */
    private var tfResults: Map<Transform, String> = emptyMap()
    private var tfProbe = ""

    private fun buildTransformBox() {
        tfBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        tfBox.addView(pageHead(tr("变换后粘贴", "Transform & paste"), tr("原记录不动", "the original stays"), { closeTransform() }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        tfGroups = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tfBox.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tfGroups) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(10)) }
        tfGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tfGrid)
        // 对照预览：原文 / 变换后
        val prev = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cField, dp(14).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        fun prevRow(label: String, body: TextView) = prev.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(ctx).apply { text = label; setTextColor(cSub); textSize = 12f; typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(dp(54), -2))
            addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (prev.childCount > 0) dp(10) else 0 })
        tfBefore = TextView(ctx).apply { setTextColor(cSub); textSize = 13f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        tfAfter = TextView(ctx).apply { setTextColor(cText); textSize = 13f; maxLines = 6; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.3f) }
        prevRow(tr("原文", "Before"), tfBefore)
        prevRow(tr("变换后", "After"), tfAfter)
        col.addView(prev, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        tfScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(col) }
        tfBox.addView(tfScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val foot = bottomBar()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(if (phone) 34 else 28), dp(8))
        }
        val hints = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (keys && !phone) {
            hints.addView(keycap("Tab"))
            hints.addView(TextView(ctx).apply { text = tr("换组", "group"); setTextColor(cMute); textSize = 11.5f },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(10) })
            hints.addView(keycap("←"))
            hints.addView(keycap("→"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(3) })
            hints.addView(TextView(ctx).apply { text = tr("换一种", "pick"); setTextColor(cMute); textSize = 11.5f },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        }
        row.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(hints) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pill(tr("复制", "Copy")) { tfChosen?.let { applyTransform(it, MODE_COPY) } })
        row.addView(pill(tr("粘贴", "Paste"), primary = true, cap = if (keys) "Enter" else null) { tfChosen?.let { applyTransform(it, MODE_PASTE) } })
        foot.addView(row)
        tfBox.addView(foot, barParams())
        panel.addView(tfBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun transformSelection() = openTransform(targets())

    private fun openTransform(chosen: List<ClipItem>) {
        if (chosen.isEmpty()) return
        if (chosen.any { it.hidden }) { Toast.makeText(ctx, tr("隐藏的内容不能变换", "Hidden items can't be transformed"), Toast.LENGTH_SHORT).show(); return }
        if (editing) stopEditing()
        val src = TextTools.join(chosen.map { it.text }, prefs.mergeMode)
        transformFor = src
        tfProbe = src.take(PROBE_CHARS)
        tfResults = emptyMap()
        tfGroup = TGroup.CLEAN; tfChosen = SPECS.first().t
        showPage(tfBox)
        tfScroll.scrollTo(0, 0)
        renderTransform()
        // 每种变换都在这段文字上试一遍：没效果的变灰，提取类直接显示找到几个
        val probe = tfProbe
        Thread {
            val r = SPECS.associate { it.t to runCatching { TextTransforms.apply(it.t, probe) }.getOrDefault(probe) }
            root.post {
                if (transformFor !== src) return@post
                tfResults = r
                SPECS.firstOrNull { useful(it.t) }?.let { tfGroup = it.group; tfChosen = it.t }
                renderTransform()
            }
        }.start()
    }

    private fun useful(t: Transform): Boolean = badgeOf(t, tfProbe, tfResults[t])?.second ?: true

    private fun tfCols() = if (wDp >= 380) 2 else 1

    private fun renderTransform() {
        val ready = tfResults.isNotEmpty()
        tfGroups.removeAllViews()
        TGroup.entries.forEach { g ->
            val sel = g == tfGroup
            tfGroups.addView(pill(g.label, primary = sel, icon = g.icon) {
                tfGroup = g
                tfChosen = SPECS.firstOrNull { it.group == g && useful(it.t) }?.t ?: SPECS.first { it.group == g }.t
                renderTransform()
            }.apply {
                if (!sel && ready && SPECS.none { it.group == g && useful(it.t) }) alpha = 0.45f
                (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(6) }
            })
        }
        tfGrid.removeAllViews()
        val cols = tfCols()
        SPECS.filter { it.group == tfGroup }.chunked(cols).forEach { chunk ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { line.addView(transformCard(it), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }) }
            repeat(cols - chunk.size) { line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }) }
            tfGrid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); marginStart = -dp(4); marginEnd = -dp(4) })
        }
        val src = transformFor ?: ""
        tfBefore.text = src.replace(newlines, " ⏎ ")
        val t = tfChosen
        val out = t?.let { tfResults[it] ?: runCatching { TextTransforms.apply(it, tfProbe) }.getOrNull() }
        tfAfter.setTextColor(cText)
        tfAfter.text = when {
            t == null || out == null -> tr("选一种变换", "Pick a transform").also { tfAfter.setTextColor(cMute) }
            out.isBlank() -> tr("（结果是空的）", "(empty result)").also { tfAfter.setTextColor(cMute) }
            t in EXTRACTS -> chips(out.lines().filter { it.isNotBlank() })
            else -> out
        }
    }

    /** 提取类的结果：一个个浅蓝小块 */
    private fun chips(parts: List<String>): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        parts.forEach { p ->
            if (sb.isNotEmpty()) sb.append("  ")
            val s = sb.length
            sb.append("\u2009").append(p).append("\u2009")
            sb.setSpan(BackgroundColorSpan(cTint), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(cAccent), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun transformCard(spec: TSpec): View {
        val sel = spec.t == tfChosen
        val badge = badgeOf(spec.t, tfProbe, tfResults[spec.t])
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(0x264C8DF6),
                if (sel) round(cTint, dp(14).toFloat(), cAccent, dp(2)) else round(cPanel, dp(14).toFloat(), cLine, dp(1)),
                round(Color.WHITE, dp(14).toFloat()))
            if (badge != null && !badge.second && !sel) alpha = 0.45f
            setOnClickListener { tfChosen = spec.t; renderTransform() }
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(ctx).apply {
            background = round(cTint, dp(8).toFloat())
            addView(ImageView(ctx).apply { setImageResource(spec.icon); imageTintList = ColorStateList.valueOf(cAccent) },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (badge != null) top.addView(TextView(ctx).apply {
            text = badge.first; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (badge.second) cOk else cMute)
            background = round(if (badge.second) 0x1F2FA866 else cField, dp(9).toFloat())
            setPadding(dp(7), dp(1), dp(7), dp(1))
        })
        card.addView(top)
        card.addView(TextView(ctx).apply {
            text = spec.t.label; setTextColor(cText); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        if (spec.example.isNotEmpty()) card.addView(TextView(ctx).apply {
            text = spec.example; setTextColor(cMute); textSize = 11f; typeface = Typeface.MONOSPACE; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        return card
    }

    private fun applyTransform(t: Transform, mode: Int) {
        val src = transformFor ?: return
        val out = runCatching { TextTransforms.apply(t, src) }.getOrDefault(src)
        if (out.isEmpty()) { Toast.makeText(ctx, tr("变换后是空的", "The result is empty"), Toast.LENGTH_SHORT).show(); return }
        closeTransform()
        picked.clear()
        onAct(out, mode) { emptyList() }
        if (shown && prefs.closeOnSelect) dismiss()
    }

    private fun closeTransform() {
        if (transformFor == null) return
        transformFor = null
        showPage(null)
        refresh()
    }

    /** 变换页的键盘：Tab 换组，←→（↑↓ 按行）换一种，Enter 粘贴，Ctrl+C 复制，Esc / 退格返回 */
    private fun handleTransformKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        if (e.isAltPressed || e.isMetaPressed) return false
        if (ctrl && !e.isShiftPressed) when (e.keyCode) {
            KeyEvent.KEYCODE_C -> { tfChosen?.let { applyTransform(it, MODE_COPY) }; return true }
            KeyEvent.KEYCODE_V -> { tfChosen?.let { applyTransform(it, MODE_PASTE) }; return true }
        }
        if (ctrl) return false
        val inGroup = SPECS.filter { it.group == tfGroup }
        fun moveBy(d: Int) {
            val i = inGroup.indexOfFirst { it.t == tfChosen }
            tfChosen = inGroup[((if (i < 0) 0 else i + d) % inGroup.size + inGroup.size) % inGroup.size].t
            renderTransform()
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> closeTransform()
            KeyEvent.KEYCODE_TAB -> {
                val g = TGroup.entries; val i = g.indexOf(tfGroup)
                tfGroup = g[(i + if (e.isShiftPressed) g.size - 1 else 1) % g.size]
                tfChosen = SPECS.firstOrNull { it.group == tfGroup && useful(it.t) }?.t ?: SPECS.first { it.group == tfGroup }.t
                renderTransform()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(tfCols())
            KeyEvent.KEYCODE_DPAD_UP -> moveBy(-tfCols())
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> tfChosen?.let { applyTransform(it, MODE_DEFAULT) }
            else -> return e.unicodeChar != 0 // 吞掉普通字符，避免误输入到后面的应用
        }
        return true
    }

    /** 截取要飞出去的卡片（最多 5 张，只取屏幕上看得到的）；一张都看不到时退回光标所在的那张 */
    private fun snapshot(ids: Set<String>, fallback: String?): List<FlyCard> {
        if (!shown) return emptyList()
        val idx = items.withIndex().filter { it.value.id in ids && it.index < list.childCount }.map { it.index }
        var cards = idx.mapNotNull { list.getChildAt(it)?.let(FlyCard::of) }.take(5)
        if (cards.isEmpty()) {
            val i = items.indexOfFirst { it.id == fallback }
            cards = listOfNotNull(list.getChildAt(i)?.let(FlyCard::of))
        }
        return cards
    }

    /** 记录 Ctrl / Shift 是否按住（用于 Ctrl+点击、Shift+点击） */
    fun handleModifier(e: KeyEvent) {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ctrlHeld = down
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> shiftHeld = down
        }
    }

    private fun ensureVisible() {
        val v = list.getChildAt(selected) ?: return
        scroll.post {
            if (v.top < scroll.scrollY) scroll.smoothScrollTo(0, v.top - dp(5))
            else if (v.bottom > scroll.scrollY + scroll.height) scroll.smoothScrollTo(0, v.bottom - scroll.height + dp(5))
        }
    }

    // ---------------- 键盘 ----------------

    /** 返回 true 表示已处理（不再传给前台应用 / 输入框）。 */
    fun handleKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed; val alt = e.isAltPressed; val meta = e.isMetaPressed
        val shift = e.isShiftPressed
        val plain = !ctrl && !alt && !meta
        if (menu != null) {
            closeMenu()
            if (e.keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        }
        if (picker != null) return handlePickKey(e)
        if (transformFor != null) return handleTransformKey(e)
        if (plain && !shift && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !editing) {
            items.getOrNull(selected)?.takeIf { !it.hidden }?.let { openPicker(it) }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    picked.isNotEmpty() -> { picked.clear(); refresh() }
                    query.isNotEmpty() || editing -> clearSearch()
                    prefs.closeOnEsc -> dismiss()
                    else -> return false
                }
                return true
            }
            KeyEvent.KEYCODE_TAB -> if (plain && tabs.size > 1) {
                switchTab(if (shift) (tab - 1 + tabs.size) % tabs.size else (tab + 1) % tabs.size)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> if (plain) {
                val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (shift) {
                    if (picked.isEmpty()) anchor = selected
                    selectRange((selected + d).coerceIn(0, max(0, visibleCount() - 1)))
                } else move(d)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> { move(5); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { move(-5); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (plain) {
                if (shift) togglePick(selected) else actOnSelection(MODE_DEFAULT)
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> if (plain) {
                if (picked.isNotEmpty()) { val ids = picked.toSet(); picked.clear(); deleteWithUndo(ids) }
                else items.getOrNull(selected)?.takeIf { !it.phrase }?.let { deleteWithUndo(listOf(it.id)) }
                return true
            }
        }
        // 标准快捷键（正在用输入法编辑搜索框时，交给搜索框自己处理）
        if (!editing && ctrl && !alt && !meta && !shift) when (e.keyCode) {
            KeyEvent.KEYCODE_A -> { selectAll(); return true }
            KeyEvent.KEYCODE_V -> { actOnSelection(MODE_PASTE); return true }
            KeyEvent.KEYCODE_C -> { actOnSelection(MODE_COPY); return true }
            KeyEvent.KEYCODE_P -> {
                items.getOrNull(selected)?.takeIf { !it.phrase }?.let { ClipStore.togglePin(it.id) }; return true
            }
            KeyEvent.KEYCODE_Z -> { Undo.undo(ctx); return true }
        }
        if (editing) return false
        if (!plain) return false
        // 搜索框为空时，1–9 是快选
        if (query.isEmpty() && !shift && e.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            items.getOrNull(e.keyCode - KeyEvent.KEYCODE_1)?.let { selected = e.keyCode - KeyEvent.KEYCODE_1; actOn(listOf(it), MODE_DEFAULT) }
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_DEL) {
            if (query.isEmpty()) return true
            setQuery(query.dropLast(1)); return true
        }
        val ch = e.unicodeChar
        if (ch != 0 && !Character.isISOControl(ch)) {
            if (ch == ' '.code && query.isEmpty()) return true
            setQuery(query + String(Character.toChars(ch)))
            return true
        }
        return false
    }

    // ---------------- 拆开挑选（像 Word 一样自由选择） ----------------

    private fun buildPickBox() {
        pickBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val all = pill(tr("全选", "Select all")) { picker?.selectAll() }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(2) }
        pickBox.addView(pageHead(tr("拆开挑选", "Pick a part"), null, { closePicker() }, all),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        pickScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true; isFocusable = false
            background = round(cField, dp(14).toFloat())
        }
        pickBox.addView(pickScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val foot = bottomBar()
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(if (phone) 34 else 28), dp(8))
        }
        pickLabel = TextView(ctx).apply { setTextColor(cMute); textSize = 11.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        bottom.addView(pickLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        bottom.addView(pill(tr("复制", "Copy")) { actPiece(MODE_COPY) })
        bottom.addView(pill(tr("粘贴", "Paste"), primary = true, cap = if (keys) "Enter" else null) { actPiece(MODE_PASTE) })
        foot.addView(bottom)
        pickBox.addView(foot, barParams())
        panel.addView(pickBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openPicker(item: ClipItem) {
        if (item.hidden) return
        if (editing) stopEditing()
        val pk = PiecePicker(
            ctx, item.text, cAccent, cPanel,
            mods = { (if (ctrlHeld) KeyEvent.META_CTRL_ON else 0) or (if (shiftHeld) KeyEvent.META_SHIFT_ON else 0) },
            onChange = { updatePickUi() },
        ).apply {
            setTextColor(cText)
            textSize = 14.5f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        picker = pk
        picked.clear()
        pickScroll.removeAllViews()
        pickScroll.addView(pk)
        pickScroll.scrollTo(0, 0)
        showPage(pickBox)
        updatePickUi()
    }

    private fun closePicker() {
        if (picker == null) return
        picker = null
        pickScroll.removeAllViews()
        showPage(null)
        refresh()
    }

    private var pickHint: String? = null

    private fun updatePickUi() {
        val pk = picker ?: return
        pickLabel.setTextColor(if (pickHint != null || pk.hasPick()) cAccent else cMute)
        pickLabel.text = pickHint ?: if (pk.hasPick()) tr("已选 %d 字 · Enter 粘贴 · Ctrl+C 复制", "%d chars selected · Enter pastes · Ctrl+C copies", pk.selectedChars())
        else if (keys) tr("拖动选择，双击选词，三击选段；Ctrl+单击加选一句；Shift+方向键扩选",
            "Drag to select, double-click a word, triple-click a paragraph; Ctrl+click adds a sentence; Shift+arrows extend")
        else tr("点按放光标，双击或长按选词，拖两端手柄调整", "Tap to place the caret, double-tap or long-press a word, drag the handles")
        pickHint = null
    }

    private fun actPiece(mode: Int) {
        val pk = picker ?: return
        if (!pk.hasPick()) { pickHint = tr("先选中要粘贴的文字（Ctrl+A 全选）", "Select the text to paste first (Ctrl+A selects all)"); updatePickUi(); return }
        val text = pk.result()
        if (text.isBlank()) return
        onAct(text, mode) {
            val bmp = pk.chip(text, cPanel, cAccent, cText, maxW = pk.width.coerceAtLeast(dp(120)))
            if (bmp == null) emptyList() else {
                val pos = pk.firstPieceScreenPos()
                listOf(FlyCard(bmp, pos[0] - dp(12), pos[1] - dp(8)))
            }
        }
        if (!shown) return
        if (prefs.closeOnSelect) dismiss() else closePicker()
    }

    private fun handlePickKey(e: KeyEvent): Boolean {
        val pk = picker ?: return false
        if (e.isAltPressed || e.isMetaPressed) return false
        if (e.isCtrlPressed && !e.isShiftPressed) when (e.keyCode) {
            KeyEvent.KEYCODE_A -> { pk.selectAll(); return true }
            KeyEvent.KEYCODE_V -> { actPiece(MODE_PASTE); return true }
            KeyEvent.KEYCODE_C -> { actPiece(MODE_COPY); return true }
        }
        if (pk.handleKey(e)) return true
        if (e.isCtrlPressed) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> if (pk.hasPick()) pk.clear() else closePicker()
            KeyEvent.KEYCODE_DEL -> closePicker()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> actPiece(MODE_DEFAULT)
            else -> return e.unicodeChar != 0 // 吞掉普通字符，避免误输入到后面的应用
        }
        return true
    }

    private fun move(d: Int) {
        if (items.isEmpty()) return
        selected = (selected + d).coerceIn(0, minOf(items.size, maxRows) - 1)
        refresh()
    }

    // ---------------- 拖动 / 缩放 ----------------

    private fun clampSize() {
        if (phone) {
            // 手机：没设置过就是一个居中的悬浮小窗，不铺满宽度、不占半屏
            val sw = toDp(dm.widthPixels); val sh = toDp(dm.heightPixels)
            if (wDp <= 0) wDp = minOf(sw - shadow * 2 - 24, 360)
            if (hDp <= 0) hDp = minOf((sh * 0.46f).toInt(), 420)
        }
        val maxW = toDp(dm.widthPixels) - shadow * 2
        val maxH = toDp(dm.heightPixels) - shadow * 2
        wDp = wDp.coerceIn(minW, max(minW, maxW))
        hDp = hDp.coerceIn(minH, max(minH, maxH))
    }

    private fun clampPos() {
        lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - lp.height))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View) {
        var dx0 = 0f; var dy0 = 0f; var x0 = 0; var y0 = 0; var dragging = false
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx0 = ev.rawX; dy0 = ev.rawY; x0 = lp.x; y0 = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dx0; val dy = ev.rawY - dy0
                    if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) dragging = true
                    if (dragging) {
                        lp.x = x0 + dx.toInt(); lp.y = y0 + dy.toInt(); clampPos()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    if (phone) { prefs.panelPhoneX = lp.x; prefs.panelPhoneY = lp.y } else { prefs.panelX = lp.x; prefs.panelY = lp.y }
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableResize(grip: View) {
        var rx = 0f; var ry = 0f; var w0 = 0; var h0 = 0
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rx = ev.rawX; ry = ev.rawY; w0 = wDp; h0 = hDp }
                MotionEvent.ACTION_MOVE -> {
                    wDp = (w0 + toDp((ev.rawX - rx).toInt())).coerceIn(minW, max(minW, toDp(dm.widthPixels - lp.x) - shadow * 2))
                    hDp = (h0 + toDp((ev.rawY - ry).toInt())).coerceIn(minH, max(minH, toDp(dm.heightPixels - lp.y) - shadow * 2))
                    (panel.layoutParams as FrameLayout.LayoutParams).apply { width = dp(wDp); height = dp(hDp) }
                    lp.width = dp(wDp + shadow * 2); lp.height = dp(hDp + shadow * 2)
                    runCatching { wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (phone) { prefs.panelPhoneW = wDp; prefs.panelPhoneH = hDp } else { prefs.panelW = wDp; prefs.panelH = hDp }
                    if (transformFor != null) renderTransform() // 宽度变了，卡片可能要换列数
                }
            }
            true
        }
    }

    // ---------------- 小部件 ----------------

    private fun round(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeW: Int = 1) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (stroke != Color.TRANSPARENT) setStroke(strokeW, stroke)
        }

    private fun tabView(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13.5f
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 键帽：细描边的小方块，用在底部提示和主按钮里 */
    private fun keycap(label: String, onPrimary: Boolean = false) = TextView(ctx).apply {
        text = label; textSize = 11f; maxLines = 1
        gravity = Gravity.CENTER
        setTextColor(if (onPrimary) 0xE6FFFFFF.toInt() else cSub)
        background = round(Color.TRANSPARENT, dp(5).toFloat(), if (onPrimary) 0x8CFFFFFF.toInt() else cLine or 0x18000000, dp(1))
        setPadding(dp(5), 0, dp(5), 0)
        minWidth = dp(20)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(19))
    }

    /** 胶囊按钮：灰底；主按钮蓝底白字，可以带一个键帽（Ctrl+V / Enter）和一个小图标 */
    private fun pill(label: String, primary: Boolean = false, icon: Int? = null, cap: String? = null, onClick: () -> Unit): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (icon != null) 10 else 14), 0, dp(if (cap != null) 6 else 14), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(if (primary) cAccent else cField, dp(16).toFloat()), null)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) }
        }
        if (icon != null) box.addView(ImageView(ctx).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(if (primary) Color.WHITE else cSub)
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) })
        box.addView(TextView(ctx).apply {
            text = label; textSize = 13f; maxLines = 1
            setTextColor(if (primary) Color.WHITE else cText)
            if (primary) typeface = Typeface.DEFAULT_BOLD
        })
        if (cap != null) box.addView(keycap(cap, onPrimary = primary).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(7)
        })
        return box
    }

    private fun iconButton(res: Int, desc: String, small: Boolean = false, tint: Int = cSub, onClick: (View) -> Unit) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = desc
            tooltipText = desc
            val p = if (small) dp(7) else dp(8)
            setPadding(p, p, p, p)
            background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, round(Color.WHITE, dp(18).toFloat()))
            isFocusable = false
            setOnClickListener { onClick(it) }
            val s = if (small) dp(30) else dp(34)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
}

/** 动作：0 按设置（直接粘贴或只复制） 1 直接粘贴 2 只复制 */
const val MODE_DEFAULT = 0
const val MODE_PASTE = 1
const val MODE_COPY = 2

/** 右下角三道斜线的缩放手柄 */
private class ResizeGrip(ctx: Context, color: Int) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = ctx.resources.displayMetrics.density * 1.6f
        strokeCap = android.graphics.Paint.Cap.ROUND; alpha = 140
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        for (i in 1..3) {
            val o = i * 4.5f * d
            c.drawLine(w - 6 * d - o + 4 * d, h - 6 * d, w - 6 * d, h - 6 * d - o + 4 * d, paint)
        }
    }
}
