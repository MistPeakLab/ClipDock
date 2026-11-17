package com.mistpeaklab.clipdock

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 桌面小部件的样子（电脑上截图）：gradlew testDebugUnitTest --tests "*WidgetShotTest*" -Pshots
 * 输出 app/build/shots/widget-<主题>.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetShotTest {
    private val ctx get() = TestEnv.ctx()
    private val out = File(System.getProperty("shots.dir") ?: "build/shots")

    @Before fun setUp() {
        Assume.assumeTrue("只在 -Pshots 时截图", System.getProperty("shots.dir") != null)
        TestEnv.restart(); TestEnv.realisticClock()
        ClipStore.init(ctx); TestEnv.settle()
        listOf(
            "【快递通知】您的「机械键盘 K8 Pro」已发货，运单号 SF1428 5736 9021",
            "https://github.com/MistPeakLab/ClipDock",
            "下午三点的会改到四点，地点不变。",
            "收件人：王经理 138 0013 8000 上海市浦东新区世纪大道 100 号",
            "zhangwei@example.com",
        ).forEach { TestEnv.tick(120_000); ClipStore.add(it, Prefs(ctx).maxItems) }
        TestEnv.settle()
    }

    private fun shot(name: String) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val sw = shadowOf(mgr)
        val id = sw.createWidget(ClipWidget::class.java, R.layout.widget_clips)
        ClipWidget.render(ctx, mgr, id)
        val v = sw.getViewFor(id)
        // Robolectric 不会把 RemoteCollectionItems 填进列表：按同样的行布局手动摆出来
        val list = v.findViewById<View>(R.id.widget_list)
        val box = list.parent as android.view.ViewGroup
        box.removeAllViews()
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val d = ctx.resources.displayMetrics.density
        ClipWidget.content(ctx, id).second.take(6).forEach { item ->
            col.addView(ClipWidget.row(ctx, item).apply(ctx, col), android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() })
        }
        box.addView(col, FrameLayout.LayoutParams(-1, -1))
        drawView(name, v)
    }

    private fun drawView(name: String, v: View) {
        val d = ctx.resources.displayMetrics.density
        val w = (330 * d).toInt(); val h = (300 * d).toInt()
        val frame = FrameLayout(ctx).apply { setBackgroundColor(if (name.contains("dark")) 0xFF22262E.toInt() else 0xFFB9C8DE.toInt()); setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        frame.addView(v, FrameLayout.LayoutParams(-1, -1))
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bmp))
        out.mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-xhdpi")
    fun widgetLight() = shot("widget-light")

    @Test @Config(qualifiers = "zh-rCN-w400dp-h860dp-port-night-xhdpi")
    fun widgetDark() = shot("widget-dark")
}
