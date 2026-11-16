package com.mistpeaklab.clipdock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
 * - 平板上：标题栏拖动移动，右下角手柄缩放；手机竖屏：贴在屏幕底部，拖标题栏调高度。位置和尺寸会记住。
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
    private val cBg = if (dark) 0xF5202227.toInt() else 0xF7F6F7FA.toInt()
    private val cCard = if (dark) 0xFF2E3138.toInt() else 0xFFFFFFFF.toInt()
    private val cField = if (dark) 0xFF292C32.toInt() else 0xFFECEEF2.toInt()
    private val cText = if (dark) 0xFFF0F2F5.toInt() else 0xFF1F2328.toInt()
    private val cSub = if (dark) 0xFFB4BAC4.toInt() else 0xFF5F6672.toInt()
    private val cAccent = 0xFF4C8DF6.toInt()
    private val cSelBg = if (dark) 0xFF263452.toInt() else 0xFFE3EDFF.toInt()
    private val cBorder = if (dark) 0x33FFFFFF else 0x1F000000

    private val shadow = 12
    private val minW = 260; private val minH = 240
    private val maxRows = 200
    private val newlines = Regex("\\s*\\n\\s*")

    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var tabBox: LinearLayout
    private lateinit var collectBtn: TextView
    private lateinit var selBar: LinearLayout
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
    private var wDp = if (phone) prefs.panelPhoneW else prefs.panelW
    private var hDp = if (phone) prefs.panelPhoneH else prefs.panelH
    private val listener: () -> Unit = { root.post { if (transformFor == null) refresh() } }

    private val dm get() = ctx.resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private fun toDp(px: Int) = (px / dm.density).toInt()

    /**
     * 手机竖屏：默认出现在屏幕下半部、几乎铺满宽度，手指好够到；
     * 和平板一样可以拖顶部把手移动、拖右下角缩放，位置和大小单独记一套。
     */
    private val phone get() = toDp(dm.widthPixels) < 600

    private fun on(f: Feature) = Features.isOn(ctx, f)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        clampSize()
        root = object : FrameLayout(ctx) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (prefs.closeOnOutside) dismiss()
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
            background = round(cBg, dp(18).toFloat(), cBorder)
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(8))
            clipToOutline = true
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            setMargins(dp(shadow), dp(shadow), dp(shadow), dp(shadow))
        })

        // 顶部把手：明确告诉用户“这里可以拖”。标题栏里有可横向滑动的标签，手指按在标签上会被滑动吃掉，所以单独留一条
        val handle = FrameLayout(ctx).apply {
            addView(View(ctx).apply { background = round(cSub and 0x00FFFFFF or 0x66000000, dp(2).toFloat()) },
                FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        panel.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (phone) 18 else 12)))
        enableDrag(handle)

        // 标题栏：拖动移动
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        header.addView(ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher_fg); scaleType = ImageView.ScaleType.CENTER_CROP },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
        tabBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBox)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        collectBtn = TextView(ctx).apply {
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { toggleCollect() }
        }
        if (on(Feature.COLLECT)) header.addView(collectBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
        header.addView(iconButton(R.drawable.ic_gen_trash, tr("清空（保留收藏）", "Clear (keeps favourites)")) { ClipStore.clearUnpinned() })
        header.addView(iconButton(R.drawable.ic_gen_open, tr("打开主界面", "Open ClipDock")) {
            dismiss()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(iconButton(R.drawable.ic_gen_close, tr("关闭", "Close")) { dismiss() })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        enableDrag(header)

        // 搜索框：直接打字即可；点一下可用输入法（中文）
        search = EditText(ctx).apply {
            hint = tr("直接打字搜索", "Type to search")
            setHintTextColor(cSub); setTextColor(cText); textSize = 13f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = round(cField, dp(10).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
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
        panel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
            topMargin = dp(2); bottomMargin = dp(2)
        })

        scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 多选操作条（按钮多，放不下时可以左右滑）
        selBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        selLabel = TextView(ctx).apply { setTextColor(cText); textSize = 13f }
        selBar.addView(selLabel)
        selBar.addView(pillButton(tr("取消", "Cancel"), false) { picked.clear(); refresh() })
        selBar.addView(pillButton(tr("复制", "Copy"), false) { actOnSelection(MODE_COPY) })
        if (on(Feature.PASTE_QUEUE)) selBar.addView(pillButton(tr("依次粘贴", "One by one"), false) { startQueue() })
        if (on(Feature.SPLIT_MERGE)) selBar.addView(pillButton(tr("合并", "Merge"), false) { mergePicked() })
        if (on(Feature.TRANSFORM)) selBar.addView(pillButton(tr("变换", "Transform"), false) { transformSelection() })
        selBar.addView(pillButton(tr("粘贴", "Paste"), true) { actOnSelection(MODE_PASTE) })
        val selWrap = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            background = round(cSelBg, dp(10).toFloat())
            visibility = View.GONE
            addView(selBar)
        }
        panel.addView(selWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); marginEnd = dp(if (phone) 30 else 18)
        })

        buildPickBox()
        val grip = ResizeGrip(ctx, cSub)
        root.addView(grip, FrameLayout.LayoutParams(dp(if (phone) 36 else 28), dp(if (phone) 36 else 28), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(shadow), dp(shadow))
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
            // 手机默认贴近底部（留出导航栏），平板默认居中
            y = if (sy >= 0) sy else if (phone) dm.heightPixels - height - dp(56) else (dm.heightPixels - height) / 2
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
            v.setTextColor(if (on) cAccent else cSub)
            v.paint.isFakeBoldText = on
            v.background = if (on) round(0x1F4C8DF6, dp(8).toFloat()) else null
            v.invalidate()
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

    private fun updateCollect() {
        val c = ClipStore.collect.value
        collectBtn.text = if (c == null) tr("收集", "Collect") else tr("收集中 %d · 结束", "Collecting %d · stop", c.parts)
        collectBtn.setTextColor(if (c == null) cSub else Color.WHITE)
        collectBtn.background = round(if (c == null) cField else cAccent, dp(12).toFloat())
    }

    // ---------------- 列表 ----------------

    private fun refresh() {
        if (!shown) return
        updateCollect()
        val base = base()
        val q = query.trim()
        items = if (q.isEmpty()) base else {
            val terms = Search.terms(q)
            val pinyin = on(Feature.PINYIN_SEARCH)
            base.filter { it.matches(terms, pinyin) }
        }
        picked.retainAll(base.map { it.id }.toSet())
        selected = selected.coerceIn(0, max(0, items.size - 1))
        list.removeAllViews()

        (selBar.parent as View).visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        selLabel.text = tr("已选 %d 条", "%d selected", picked.size)

        if (items.isEmpty()) {
            list.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_empty)
                imageTintList = ColorStateList.valueOf(cSub)
                alpha = 0.5f
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
        items.take(maxRows).forEachIndexed { i, item -> list.addView(itemView(i, item)) }
        ensureVisible()
    }

    private fun itemView(index: Int, item: ClipItem): View {
        val cursor = index == selected
        val isPicked = item.id in picked
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(2), dp(8))
            background = RippleDrawable(
                ColorStateList.valueOf(0x334C8DF6),
                round(if (isPicked) cSelBg else cCard, dp(10).toFloat(), if (cursor) cAccent else Color.TRANSPARENT, dp(2)),
                null,
            )
            setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchMeta = ev.metaState; false }
            setOnClickListener { onRowClick(index) }
            setOnLongClickListener { togglePick(index); true }
        }
        val badge = when {
            isPicked -> "✓"
            index < 9 && query.isEmpty() -> "${index + 1}"
            else -> ""
        }
        row.addView(TextView(ctx).apply {
            text = badge
            setTextColor(if (cursor || isPicked) cAccent else cSub); textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })

        val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(ctx).apply {
            text = if (item.hidden) "🔒 " + tr("已隐藏的内容", "Hidden item") else item.preview.replace(newlines, " ⏎ ")
            setTextColor(if (item.hidden) cSub else cText); textSize = 13.5f
            maxLines = prefs.previewLines; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        })
        // 第二行小字：备注、类型、来源应用（相应功能打开时）
        val meta = buildList {
            if (on(Feature.NOTE) && item.note != null) add("✎ " + item.note)
            if (on(Feature.CONTENT_KIND) && item.kind != ContentKind.TEXT) add(Kinds.label(item.kind))
            if (on(Feature.SOURCE_APP) && item.sourcePkg != null) add(AppInfo.label(ctx, item.sourcePkg))
        }
        if (meta.isNotEmpty()) textCol.addView(TextView(ctx).apply {
            text = meta.joinToString(" · ")
            setTextColor(cSub); textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!item.hidden && item.preview.length >= 12) {
            row.addView(iconButton(R.drawable.ic_gen_pick, tr("拆开挑选（→）", "Pick part (→)"), small = true) { selected = index; openPicker(item) })
        }
        if (!item.phrase) {
            row.addView(iconButton(if (item.pinned) R.drawable.ic_gen_star_filled else R.drawable.ic_gen_star,
                if (item.pinned) tr("取消收藏", "Unstar") else tr("收藏", "Star"), small = true,
                tint = if (item.pinned) cAccent else cSub) { ClipStore.togglePin(item.id) })
            row.addView(iconButton(R.drawable.ic_gen_trash, tr("删除", "Delete"), small = true) { deleteWithUndo(listOf(item.id)) })
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(5) }
        return row
    }

    // ---------------- 选择与动作 ----------------

    private fun visibleCount() = minOf(items.size, maxRows)

    private fun onRowClick(index: Int) {
        val ctrl = ctrlHeld || (touchMeta and KeyEvent.META_CTRL_ON) != 0
        val shift = shiftHeld || (touchMeta and KeyEvent.META_SHIFT_ON) != 0
        when {
            ctrl -> togglePick(index)
            shift -> selectRange(index)
            else -> items.getOrNull(index)?.let { picked.clear(); selected = index; actOn(listOf(it), MODE_DEFAULT) }
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

    /** 依次粘贴：先贴第一条，之后每点一次“下一条”贴下一条 */
    /** 删除并给出 5 秒撤销（回收站开着时） */
    private fun deleteWithUndo(ids: Collection<String>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        ClipStore.deleteAll(list.toSet())
        Undo.offer(ctx, list)
    }

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

    // ---------------- 粘贴前变换 ----------------

    private fun transformSelection() {
        val chosen = targets()
        if (chosen.isEmpty()) return
        if (chosen.any { it.hidden }) { Toast.makeText(ctx, tr("隐藏的内容不能变换", "Hidden items can't be transformed"), Toast.LENGTH_SHORT).show(); return }
        transformFor = TextTools.join(chosen.map { it.text }, prefs.mergeMode)
        search.visibility = View.GONE
        (selBar.parent as View).visibility = View.GONE
        list.removeAllViews()
        list.addView(pillButton(tr("← 返回", "← Back"), false) { closeTransform() }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
        Transform.entries.forEach { t ->
            list.addView(TextView(ctx).apply {
                text = t.label
                setTextColor(cText); textSize = 14f
                background = RippleDrawable(ColorStateList.valueOf(0x334C8DF6), round(cCard, dp(10).toFloat()), null)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { applyTransform(t) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun applyTransform(t: Transform) {
        val src = transformFor ?: return
        val out = runCatching { TextTransforms.apply(t, src) }.getOrDefault(src)
        if (out.isEmpty()) { Toast.makeText(ctx, tr("变换后是空的", "The result is empty"), Toast.LENGTH_SHORT).show(); return }
        closeTransform()
        picked.clear()
        onAct(out, MODE_PASTE) { emptyList() }
        if (shown && prefs.closeOnSelect) dismiss()
    }

    private fun closeTransform() {
        if (transformFor == null) return
        transformFor = null
        search.visibility = View.VISIBLE
        refresh()
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
        if (picker != null) return handlePickKey(e)
        if (transformFor != null) {
            if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_DEL) { closeTransform(); return true }
            return e.unicodeChar != 0
        }
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
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(pillButton(tr("← 返回", "← Back"), false) { closePicker() }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(pillButton(tr("全选", "Select all"), false) { picker?.selectAll() })
        pickBox.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        pickScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true; isFocusable = false
            background = round(cCard, dp(10).toFloat())
        }
        pickBox.addView(pickScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, 0)
        }
        pickLabel = TextView(ctx).apply { setTextColor(cSub); textSize = 12f; maxLines = 2 }
        bottom.addView(pickLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(pillButton(tr("复制", "Copy"), false) { actPiece(MODE_COPY) })
        bottom.addView(pillButton(tr("粘贴", "Paste"), true) { actPiece(MODE_PASTE) })
        pickBox.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(if (phone) 30 else 18) })
        panel.addView(pickBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openPicker(item: ClipItem) {
        if (item.hidden) return
        if (editing) stopEditing()
        val pk = PiecePicker(
            ctx, item.text, cAccent, cCard,
            mods = { (if (ctrlHeld) KeyEvent.META_CTRL_ON else 0) or (if (shiftHeld) KeyEvent.META_SHIFT_ON else 0) },
            onChange = { updatePickUi() },
        ).apply {
            setTextColor(cText)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        picker = pk
        picked.clear()
        pickScroll.removeAllViews()
        pickScroll.addView(pk)
        pickScroll.scrollTo(0, 0)
        search.visibility = View.GONE; scroll.visibility = View.GONE; (selBar.parent as View).visibility = View.GONE
        pickBox.visibility = View.VISIBLE
        updatePickUi()
    }

    private fun closePicker() {
        if (picker == null) return
        picker = null
        pickScroll.removeAllViews()
        pickBox.visibility = View.GONE
        search.visibility = View.VISIBLE; scroll.visibility = View.VISIBLE
        refresh()
    }

    private var pickHint: String? = null

    private fun updatePickUi() {
        val pk = picker ?: return
        pickLabel.setTextColor(if (pickHint != null) cAccent else cSub)
        pickLabel.text = pickHint ?: if (pk.hasPick()) tr("已选 %d 字 · Enter 粘贴 · Ctrl+C 复制", "%d chars selected · Enter pastes · Ctrl+C copies", pk.selectedChars())
        else tr("拖动选择，双击选词，三击选段；Ctrl+单击加选一句\nShift+方向键扩选，Ctrl+Shift+←→ 按词扩选",
            "Drag to select, double-tap a word, triple-tap a paragraph; Ctrl+click adds a sentence\nShift+arrows extend, Ctrl+Shift+←→ by word")
        pickHint = null
    }

    private fun actPiece(mode: Int) {
        val pk = picker ?: return
        if (!pk.hasPick()) { pickHint = tr("先选中要粘贴的文字（Ctrl+A 全选）", "Select the text to paste first (Ctrl+A selects all)"); updatePickUi(); return }
        val text = pk.result()
        if (text.isBlank()) return
        onAct(text, mode) {
            val bmp = pk.chip(text, cCard, cAccent, cText, maxW = pk.width.coerceAtLeast(dp(120)))
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
            // 手机：没设置过就几乎铺满宽度、占屏幕一半多一点高
            if (wDp <= 0) wDp = toDp(dm.widthPixels) - shadow * 2 - 8
            if (hDp <= 0) hDp = toDp((dm.heightPixels * 0.55f).toInt())
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
        setPadding(dp(10), dp(5), dp(10), dp(5))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(2) }
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f
        maxLines = 1
        setTextColor(if (primary) Color.WHITE else cText)
        background = round(if (primary) cAccent else Color.TRANSPARENT, dp(14).toFloat())
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(4) }
    }

    private fun iconButton(res: Int, desc: String, small: Boolean = false, tint: Int = cSub, onClick: () -> Unit) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = desc
            tooltipText = desc
            val p = if (small) dp(7) else dp(8)
            setPadding(p, p, p, p)
            background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, round(Color.WHITE, dp(18).toFloat()))
            isFocusable = false
            setOnClickListener { onClick() }
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
        strokeCap = android.graphics.Paint.Cap.ROUND; alpha = 160
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        for (i in 1..3) {
            val o = i * 5f * d
            c.drawLine(w - 6 * d - o + 4 * d, h - 6 * d, w - 6 * d, h - 6 * d - o + 4 * d, paint)
        }
    }
}
