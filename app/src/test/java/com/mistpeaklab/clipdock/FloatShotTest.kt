package com.mistpeaklab.clipdock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mistpeaklab.clipdock.floatball.FloatController
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 悬浮球与环形控制台的截图（电脑上，Robolectric）。和 ScreenshotTest 一样只在 -Pshots 时运行：
 *
 *   gradlew testDebugUnitTest --tests "*FloatShotTest*" -Pshots
 *
 * 输出 app/build/shots/float-<设备>-<场景>.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = TestEnv.ctx()
    private val out = File(System.getProperty("shots.dir") ?: "build/shots")

    @Before fun setUp() {
        Assume.assumeTrue("只在 -Pshots 时截图", System.getProperty("shots.dir") != null)
        TestEnv.restart()
        ClipStore.init(ctx); TestEnv.settle()
        Prefs(ctx).onboardingDone = true
        // Robolectric 不给窗口排位置：球的屏幕位置按 LayoutParams 算
        FloatController.screenPos = { _, lp -> intArrayOf(lp.x, lp.y) }
    }

    @org.junit.After fun tearDown() = TestEnv.restart()

    private fun idle(ms: Long) { ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS); rule.waitForIdle() }

    private fun ball(): View? = windowRoots().lastOrNull { it.contentDescription?.startsWith("随贴悬浮球") == true }

    private fun touch(v: View, action: Int, x: Float, y: Float, down: Long) = rule.runOnUiThread {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun run(prefix: String, style: Int, side: Int) {
        val p = Prefs(ctx)
        p.theme = 1; p.floatEnabled = true; p.floatStyle = style; p.floatSide = side; p.floatY = 550
        listOf("下午三点的会改到四点，地点不变。", "SF1428 5736 9021", "https://github.com/MistPeakLab/ClipDock",
            "收件人：王经理 138 0013 8000 上海市浦东新区世纪大道 100 号", "Please review the PR before Friday. Thanks!")
            .forEach { TestEnv.tick(60_000); ClipStore.add(it, p.maxItems) }
        TestEnv.settle()
        L.init(rule.activity)
        rule.setContent { AppTheme { AppRoot() } }
        idle(300)
        lateinit var c: FloatController
        rule.runOnUiThread {
            c = FloatController(rule.activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            c.show()
        }
        idle(400)
        capture("$prefix-expanded")
        idle(3200)
        capture("$prefix-docked")
        // 长按 → 控制台：卡卡走到中间，手指先滑到“暂停记录”，再滑到“最近”并伸到第二圈
        val v = ball() ?: error("没找到悬浮球窗口")
        val lp = v.layoutParams as WindowManager.LayoutParams
        val dn = ctx.resources.displayMetrics.density
        val bx = lp.x + v.width / 2f
        val by = lp.y + v.height / 2f
        val b = rule.activity.windowManager.currentWindowMetrics.bounds
        val big = maxOf(v.width * 1.6f, 120 * dn).toInt()
        val rad = (96 * dn + big * 0.28f) / dn
        val mx = if (side == 0) 6 * dn + big / 2f else b.right - 6 * dn - big / 2f
        val my = by.coerceIn(rad * dn * 0.97f + 50 * dn, b.bottom - rad * dn * 0.97f - 50 * dn)
        fun at(i: Int, r: Float): Pair<Float, Float> {
            val a = Math.toRadians(-75.0 + 37.5 * i)
            val ang = if (side == 0) a else Math.PI - a
            return (mx + r * dn * Math.cos(ang)).toFloat() to (my + r * dn * Math.sin(ang)).toFloat()
        }
        val t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, bx, by, t0)
        idle(450)
        idle(300)
        at(2, rad).let { (x, y) -> touch(v, MotionEvent.ACTION_MOVE, x, y, t0) }
        idle(400)
        capture("$prefix-radial")
        at(0, rad).let { (x, y) -> touch(v, MotionEvent.ACTION_MOVE, x, y, t0) }
        idle(300)
        // 第二圈竖排在内圈外侧，以“最近”的高度为中心：伸到那一列上
        touch(v, MotionEvent.ACTION_MOVE, mx + (if (side == 0) 1 else -1) * (rad + 120) * dn, my + rad * dn * Math.sin(Math.toRadians(-75.0)).toFloat() + 44 * dn, t0)
        idle(300)
        capture("$prefix-radial-recent")
        touch(v, MotionEvent.ACTION_UP, bx + 2000, by, t0)
        idle(400)
        capture("$prefix-after")
        rule.runOnUiThread { c.destroy() }
    }

    private fun capture(name: String) {
        val roots = windowRoots().filter { it.isShown && it.width > 0 }
        val main = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (w in roots) {
            val lp = w.layoutParams as? WindowManager.LayoutParams
            val x = if (w === main || lp == null) 0 else lp.x
            val y = if (w === main || lp == null) 0 else lp.y
            c.save(); c.translate(x.toFloat(), y.toFloat())
            if (w !== main) c.saveLayerAlpha(null, (w.alpha * 255).toInt())
            w.draw(c)
            c.restore()
            if (w !== main) c.restore()
        }
        out.mkdirs()
        File(out, "float-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val g = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val f = g.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(g) as List<View>).toList()
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun phoneMascotRight() = run("phone-kaka-right", 1, 1)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-land-xhdpi")
    fun tabletMascotLeft() = run("tablet-kaka-left", 1, 0)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun phoneIconLeft() = run("phone-icon-left", 0, 0)
}
