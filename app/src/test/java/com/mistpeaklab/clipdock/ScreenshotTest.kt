package com.mistpeaklab.clipdock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 电脑上截图：不连平板，把主界面各个状态画成 PNG，用来检查样式、做 README 配图。
 *
 *   gradlew testDebugUnitTest --tests "*ScreenshotTest*" -Pshots
 *
 * 输出到 app/build/shots/<设备>-<主题>-<场景>.png。不带 -Pshots 时这些测试自动跳过。
 * 场景在 [scenes] 里加：每个场景从干净的界面开始，做几下点击，再截图（包括弹窗、Popup）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = TestEnv.ctx()
    private val out = File(System.getProperty("shots.dir") ?: "build/shots")
    private var scene by mutableIntStateOf(0)

    @Before fun setUp() {
        Assume.assumeTrue("只在 -Pshots 时截图", System.getProperty("shots.dir") != null)
        TestEnv.restart(); TestEnv.realisticClock()
        ClipStore.init(ctx); TestEnv.settle()
        Prefs(ctx).onboardingDone = true
        seed()
    }

    @org.junit.After fun tearDown() = TestEnv.restart()

    private fun seed() {
        val texts = listOf(
            "【快递通知】您好，您在 3 月 12 日购买的「机械键盘 K8 Pro」已发货，运单号 SF1428 5736 9021，预计 2 天内送达。",
            "https://github.com/MistPeakLab/ClipDock",
            "会议纪要：本周五 14:30 在 3 号会议室讨论 v0.6.1 发布计划。负责人张伟（zhangwei@example.com）。",
            "下午三点的会改到四点，地点不变。",
            "Please review the PR before Friday. Thanks!",
            "#4C8DF6",
            "收件人：王经理 138 0013 8000 上海市浦东新区世纪大道 100 号",
            "zhangwei@example.com",
        )
        texts.forEach { TestEnv.tick(60_000); ClipStore.add(it, Prefs(ctx).maxItems) }
        TestEnv.settle()
        ClipStore.history().firstOrNull { it.text.startsWith("https") }?.let { ClipStore.togglePin(it.id) }
        TestEnv.settle()
    }

    // ---------------- 场景 ----------------

    private fun text(s: String) = rule.onAllNodesWithText(s, substring = true).onFirst()
    private fun pause(ms: Long = 600) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private val scenes: List<Pair<String, () -> Unit>> = listOf(
        "home" to {},
        "dock" to { text("改到四点").performClick(); pause() },
        "wheel" to { text("改到四点").performTouchInput { longClick() }; pause() },
        "transform" to {
            text("快递通知").performClick(); pause()
            text("变换").performClick(); pause(1500)
        },
        "more" to { text("改到四点").performClick(); pause(); text("更多").performClick(); pause() },
        "detail" to {
            Prefs(ctx).recordsLayout = 1; scene++; pause()
            text("改到四点").performClick(); pause()
        },
        "favorites" to { text("收藏").performClick(); pause() },
        "me" to { text("我的").performClick(); pause() },
        // 悬浮面板（基础模式：悬浮窗权限）
        "panel" to { showPanel() },
        "panel-search" to { showPanel(); panelType("会"); pause() },
        "panel-multi" to {
            showPanel()
            panelKey(KeyEvent.KEYCODE_DPAD_DOWN); panelKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
            panelKey(KeyEvent.KEYCODE_DPAD_DOWN); panelKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
            panelKey(KeyEvent.KEYCODE_DPAD_DOWN); panelKey(KeyEvent.KEYCODE_DPAD_DOWN); panelKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
            pause()
        },
        "panel-menu" to {
            showPanel(); panelClick { it.contentDescription == "更多" }; pause()
        },
        "panel-transform" to {
            showPanel(); panelType("快递"); pause()
            panelKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON); pause()
            panelClick { it is TextView && it.text.toString() == "变换" }
            Thread.sleep(800); pause()
            panelKey(KeyEvent.KEYCODE_TAB); panelKey(KeyEvent.KEYCODE_TAB); pause()
        },
    )

    // ---------------- 悬浮面板（基础模式：悬浮窗权限） ----------------

    private fun showPanel() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        rule.runOnUiThread { PanelHost.show(rule.activity) }; pause()
    }

    private fun panelRoot(): View? = windowRoots().lastOrNull { it !== rule.activity.window.decorView && it.isShown }

    private fun findView(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findView(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    /** 点面板里第一个符合条件的控件（文字本身不可点时点它可点的父容器） */
    private fun panelClick(pred: (View) -> Boolean) = rule.runOnUiThread {
        var v = panelRoot()?.let { findView(it, pred) }
        while (v != null && !v.isClickable) v = v.parent as? View
        v?.performClick() ?: println("面板里没找到要点的控件")
    }

    private fun panelType(s: String) = rule.runOnUiThread {
        (panelRoot()?.let { findView(it) { v -> v is EditText } } as? EditText)?.setText(s)
    }

    private fun panelKey(code: Int, meta: Int = 0) = rule.runOnUiThread {
        PanelHost.handleKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
    }

    /** “我的”下面的子页：按入口的副标题找（标题和底部导航会重名） */
    private val subPages = listOf(
        "permissions" to "权限与模式",
        "features" to "逐项设置",
        "panelset" to "快捷键、关闭方式",
        "float" to "看板娘卡卡常驻屏幕边",
        "paste" to "直接粘贴、清理格式",
        "record" to "保留条数",
        "privacy" to "隐藏内容、不记录的应用",
        "backup" to "导出、导入、自动备份",
        "trash" to "删除的内容保留",
        "diag" to "复制了却没记下来",
        "look" to "主题、界面语言",
    )

    private fun runSettings(prefix: String, theme: Int) {
        Prefs(ctx).theme = theme
        Prefs(ctx).applyMode(Mode.FULL)
        // 回收站里放两条，截图才有内容
        ClipStore.history().filter { it.text.startsWith("zhangwei") || it.text.startsWith("Please review") }.forEach { ClipStore.delete(it.id) }
        TestEnv.settle()
        L.init(rule.activity)
        rule.setContent { key(scene) { AppTheme { AppRoot() } } }
        pause(300)
        for ((name, entry) in subPages) {
            scene++; pause(300)
            runCatching { text("我的").performClick(); pause(); text(entry).performClick(); pause(800); TestEnv.settle(); pause(300) }
                .onFailure { println("子页 $name 失败：$it") }
            capture("$prefix-$name")
        }
    }

    private fun run(prefix: String, theme: Int, mode: Mode = Mode.FULL) {
        Prefs(ctx).theme = theme
        Prefs(ctx).applyMode(mode)
        val ac = rule.activity
        L.init(ac)
        rule.setContent { key(scene) { AppTheme { AppRoot() } } }
        pause(300)
        for ((name, action) in scenes) {
            Prefs(ctx).recordsLayout = 0
            scene++; pause(300)
            runCatching(action).onFailure { println("场景 $name 失败：$it") }
            capture("$prefix-$name")
            // 关掉弹窗
            rule.runOnUiThread { PanelHost.hide() }
            ac.onBackPressedDispatcher.onBackPressed(); pause(300)
        }
    }

    /** 当前所有窗口（主界面、弹窗、Popup）从下到上叠在一起画出来 */
    private fun capture(name: String) {
        val roots = windowRoots().filter { it.isShown && it.width > 0 }
        val main = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (w in roots) {
            val lp = w.layoutParams as? WindowManager.LayoutParams
            if (w !== main && lp != null && lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                c.drawColor(android.graphics.Color.argb((lp.dimAmount * 255).toInt(), 0, 0, 0))
            }
            // Robolectric 不给窗口排位置：按窗口自己的 gravity + x/y 算（弹窗默认居中，Popup/悬浮面板是左上角 + 偏移）
            val pos = android.graphics.Rect()
            if (w === main || lp == null) pos.set(0, 0, w.width, w.height)
            else android.view.Gravity.apply(
                if (lp.gravity == 0) android.view.Gravity.TOP or android.view.Gravity.START else lp.gravity,
                w.width, w.height, android.graphics.Rect(0, 0, main.width, main.height), lp.x, lp.y, pos, w.layoutDirection,
            )
            c.save(); c.translate(pos.left.toFloat(), pos.top.toFloat()); w.draw(c); c.restore()
        }
        out.mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val g = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val f = g.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(g) as List<View>).toList()
    }

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun tabletLight() = run("tablet-light", 1)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun tabletSimple() = run("tablet-simple", 1, Mode.SIMPLE)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun tabletDark() = run("tablet-dark", 2)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun phoneLight() = run("phone-light", 1)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun phoneDark() = run("phone-dark", 2)

    // 设置子页：和真机一样的屏幕高度（README 用）；要看整页时把 h800dp 临时改成 h1600dp
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun settingsTabletLight() = runSettings("set-tablet-light", 1)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun settingsTabletDark() = runSettings("set-tablet-dark", 2)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun settingsPhoneLight() = runSettings("set-phone-light", 1)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun settingsPhoneDark() = runSettings("set-phone-dark", 2)
}