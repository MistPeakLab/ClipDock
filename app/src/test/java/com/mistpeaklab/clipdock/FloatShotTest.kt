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
        // 长按 → 控制台，手指滑向第二项（暂停记录）
        val v = ball() ?: error("没找到悬浮球窗口")
        val lp = v.layoutParams as WindowManager.LayoutParams
        val cx = lp.x + v.width / 2f
        val cy = lp.y + v.height / 2f
        val t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, cx, cy, t0)
        idle(450)
        val dir = if (side == 0) 1 else -1
        val d = 110 * ctx.resources.displayMetrics.density
        touch(v, MotionEvent.ACTION_MOVE, cx + dir * d * 0.97f, cy - d * 0.26f, t0)
        idle(300)
        capture("$prefix-radial")
        touch(v, MotionEvent.ACTION_UP, cx, cy, t0)
        idle(300)
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
