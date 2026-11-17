package com.mistpeaklab.clipdock.floatball

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import com.mistpeaklab.clipdock.ClipService
import com.mistpeaklab.clipdock.Feature
import com.mistpeaklab.clipdock.Features
import com.mistpeaklab.clipdock.MainActivity
import com.mistpeaklab.clipdock.PanelHost
import com.mistpeaklab.clipdock.Prefs
import com.mistpeaklab.clipdock.R
import com.mistpeaklab.clipdock.data.Vault
import com.mistpeaklab.clipdock.tr
import java.util.Calendar
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 悬浮球的总入口：决定由谁来承载（宿主），以及把各处的事件转给它。
 *
 * 宿主选择：
 * - 无障碍服务在运行 → 在 [ClipService] 里创建（无障碍悬浮层，不需要悬浮窗权限，也更不容易被清掉）；
 * - 否则有悬浮窗权限 → 启动前台服务 [FloatService] 创建（普通悬浮窗）；
 * - 都没有 → 不显示。
 * 两种宿主共用同一个 [FloatController]；中途开关无障碍时迁移到新宿主，位置不变（位置存在设置里）。
 *
 * 所有方法都可以在任意线程调用，实际工作都转到主线程做。
 */
object FloatBall {
    private val main = Handler(Looper.getMainLooper())
    private var ctrl: FloatController? = null
    private var ctrlHost: Context? = null
    private var unhide: Runnable? = null

    val isShowing get() = ctrl != null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 让卡卡做一个反应（记到新内容、粘贴、收藏……）。没开悬浮球时什么也不做 */
    fun react(m: Mascot) = onMain { ctrl?.react(m) }

    fun onPanelClosed() = onMain { ctrl?.onPanelClosed() }

    /** 按当前设置和可用能力，创建 / 迁移 / 移除悬浮球 */
    fun sync(ctx: Context) = onMain {
        val app = ctx.applicationContext ?: ctx
        val p = Prefs(app)
        val hidden = p.floatHiddenUntil > System.currentTimeMillis()
        scheduleUnhide(app, if (p.floatEnabled && hidden) p.floatHiddenUntil else 0L)
        val svc = ClipService.instance
        when {
            !p.floatEnabled || hidden -> { destroy(); FloatService.stop(app) }
            svc != null -> {
                FloatService.stop(app)
                if (ctrlHost !== svc || ctrl == null) { destroy(); create(svc, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }
            }
            Settings.canDrawOverlays(app) -> if (ctrlHost !is FloatService || ctrl == null) { destroy(); FloatService.start(app) }
            else -> { destroy(); FloatService.stop(app) }
        }
    }

    /** 设置改了样式、大小：重建 */
    fun rebuild(ctx: Context) = onMain { destroy(); FloatService.stop(ctx.applicationContext); sync(ctx) }

    /** 暂停记录切换后刷新卡卡的基础状态（睡觉 / 醒着） */
    fun refreshState() = onMain { ctrl?.refreshBase() }

    /** 今日隐藏：到明天 0 点自动恢复 */
    fun hideToday(ctx: Context) {
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        Prefs(ctx).floatHiddenUntil = c.timeInMillis
        sync(ctx)
    }

    fun unhideNow(ctx: Context) {
        Prefs(ctx).floatHiddenUntil = 0L
        sync(ctx)
    }

    internal fun attach(svc: FloatService) = onMain {
        // 无障碍宿主优先：它在的话前台服务没必要存在
        if (ClipService.instance != null) { FloatService.stop(svc); sync(svc); return@onMain }
        if (ctrlHost === svc && ctrl != null) return@onMain
        destroy()
        create(svc, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    internal fun detach(host: Context) = onMain { if (ctrlHost === host) destroy() }

    /** 无障碍服务被关掉：它承载的悬浮球随之移除，再按剩下的能力重新安排 */
    fun hostGone(svc: ClipService) = onMain {
        val app = svc.applicationContext
        if (ctrlHost === svc) destroy()
        main.post { sync(app) }
    }

    private fun create(host: Context, type: Int) {
        val c = FloatController(host, type)
        if (c.show()) { ctrl = c; ctrlHost = host }
    }

    private fun destroy() {
        ctrl?.destroy()
        ctrl = null
        ctrlHost = null
    }

    private fun scheduleUnhide(app: Context, at: Long) {
        unhide?.let(main::removeCallbacks)
        unhide = null
        if (at <= 0L) return
        val r = Runnable { unhide = null; sync(app) }
        unhide = r
        main.postDelayed(r, (at - System.currentTimeMillis()).coerceAtLeast(1000L))
    }
}

/**
 * 一个悬浮球窗口：贴边吸附、拖动、点按唤出面板、长按环形控制台，以及卡卡的动画状态。
 *
 * 状态：展开（贴在边上完整显示）⇄ 收起（3 秒无操作后：变淡，卡卡改成从边上探头；圆形图标缩进屏幕边一半）。
 */
internal class FloatController(private val host: Context, private val type: Int) {
    companion object {
        /** 悬浮球窗口在屏幕上的位置。电脑上截图（Robolectric 不给窗口排位置）时换成按 LayoutParams 算 */
        internal var screenPos: (View, WindowManager.LayoutParams) -> IntArray = { v, _ -> IntArray(2).also(v::getLocationOnScreen) }
    }

    private val wm = host.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(host)
    private val main = Handler(Looper.getMainLooper())
    private val dens = host.resources.displayMetrics.density
    private fun dp(v: Float) = (v * dens).toInt()

    private val mascot = prefs.floatStyle == 1
    private val size = dp(
        if (mascot) floatArrayOf(64f, 80f, 100f)[prefs.floatSize.coerceIn(0, 2)]
        else floatArrayOf(44f, 52f, 60f)[prefs.floatSize.coerceIn(0, 2)]
    )
    private val slop = ViewConfiguration.get(host).scaledTouchSlop
    private val longPress = 400L

    private var side = prefs.floatSide.coerceIn(0, 1)
    private var docked = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var radial: RadialMenu? = null
    private var snapAnim: ValueAnimator? = null
    /** 正在播一次性的反应动作（播完回到基础状态） */
    private var reacting = false
    private var destroyed = false

    private val sprite: SpriteView? = if (mascot) SpriteView(host) else null
    private val icon: View? = if (mascot) null else BallIcon(host)

    private val root = object : FrameLayout(host) {
        override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)
        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            // 转屏、折叠：按保存的比例重新放
            post { if (!destroyed) place() }
        }
    }

    private val lp = WindowManager.LayoutParams(
        size, size, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        windowAnimations = 0
    }

    private val dockRun = Runnable { dock() }
    private val peekRun = Runnable { if (docked && !reacting && base() == Mascot.PEEK) playBase() }
    private var lastBoredAt = System.currentTimeMillis()
    private val longRun = Runnable { openRadial() }

    fun show(): Boolean {
        (sprite ?: icon)?.let { root.addView(it, FrameLayout.LayoutParams(size, size)) }
        root.contentDescription = tr("随贴悬浮球：点按打开面板，长按打开控制台", "ClipDock bubble: tap for the panel, long-press for the menu")
        place()
        if (runCatching { wm.addView(root, lp) }.isFailure) return false
        sprite?.mirrored = side == 1
        // 出场先招手，然后按平常的节奏收起
        if (sprite != null && base() != Mascot.SLEEP) playOnce(Mascot.WAVE) else playBase()
        scheduleDock()
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        radial?.close()
        runCatching { wm.removeViewImmediate(root) }
    }

    // ---------------- 位置 ----------------

    private fun screen() = wm.currentWindowMetrics.bounds

    private fun edgeX(): Int {
        val b = screen()
        val inset = if (mascot) 0 else if (docked) -size / 2 else dp(4f)
        return if (side == 0) b.left + inset else b.right - size - inset
    }

    private fun place() {
        val b = screen()
        val margin = dp(24f)
        lp.y = (b.top + b.height() * prefs.floatY / 1000f - size / 2f).toInt().coerceIn(b.top + margin, b.bottom - size - margin)
        lp.x = edgeX()
        root.alpha = if (docked) prefs.floatAlphaIdle.coerceIn(20, 100) / 100f else 1f
        if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun savePos() {
        val b = screen()
        prefs.floatSide = side
        prefs.floatY = (((lp.y + size / 2f) - b.top) / b.height() * 1000).toInt().coerceIn(0, 1000)
    }

    // ---------------- 展开 / 收起 ----------------

    private fun scheduleDock() {
        main.removeCallbacks(dockRun)
        if (!PanelHost.isShowing) main.postDelayed(dockRun, 3000)
    }

    private fun dock() {
        if (destroyed || dragging || radial != null || PanelHost.isShowing) return
        docked = true
        place()
        if (!reacting) playBase()
    }

    private fun expand() {
        main.removeCallbacks(dockRun)
        if (!docked) return
        docked = false
        place()
        if (!reacting) playBase()
    }

    fun onPanelClosed() { if (!destroyed) scheduleDock() }

    // ---------------- 卡卡的动画 ----------------

    /** 基础状态：暂停记录时睡觉；否则收起时探头、展开时待机 */
    private fun base(): Mascot = when {
        prefs.capturePaused -> Mascot.SLEEP
        docked -> Mascot.PEEK
        else -> Mascot.IDLE
    }

    private fun playBase() {
        val s = sprite ?: return
        main.removeCallbacks(peekRun)
        reacting = false
        when (val b = base()) {
            Mascot.PEEK -> {
                // 隔一阵偶尔打个哈欠，平时探头张望一下就停住，过几秒再探
                val now = System.currentTimeMillis()
                if (now - lastBoredAt > 90_000 && Random.nextInt(3) == 0) {
                    lastBoredAt = now
                    playOnce(Mascot.BORED)
                    return
                }
                s.play(Mascot.PEEK) { s.pause(); main.postDelayed(peekRun, 4000L + Random.nextLong(4000)) }
            }
            else -> s.play(b)
        }
    }

    private fun playOnce(m: Mascot) {
        val s = sprite ?: return
        main.removeCallbacks(peekRun)
        reacting = true
        // 收起时做反应：先完整显示出来，做完再淡回去
        root.alpha = 1f
        s.play(m) {
            reacting = false
            if (!destroyed && !dragging) { place(); playBase() }
        }
    }

    fun react(m: Mascot) {
        if (destroyed || dragging || radial != null) return
        if (sprite == null) { pulse(); return }
        // 睡觉时只有“打开隐藏内容”会让她惊醒捂眼；其他动静不吵醒她
        if (prefs.capturePaused && m != Mascot.SHY) return
        playOnce(m)
    }

    fun refreshBase() { if (!destroyed && !reacting) playBase() }

    /** 圆形图标没有动画素材：轻轻弹一下表示收到 */
    private fun pulse() {
        val v = icon ?: return
        v.animate().cancel()
        v.scaleX = 1.18f; v.scaleY = 1.18f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ---------------- 触摸 ----------------

    private fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                snapAnim?.cancel()
                main.removeCallbacks(dockRun)
                if (docked) { docked = false; place() }
                startX = lp.x; startY = lp.y
                dragging = false
                main.postDelayed(longRun, longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                radial?.let { it.hover(e.rawX, e.rawY); return true }
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    main.removeCallbacks(longRun)
                    main.removeCallbacks(peekRun)
                    reacting = false
                    sprite?.play(Mascot.DRAG)
                }
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longRun)
                val r = radial
                when {
                    r != null -> r.release(e.rawX, e.rawY)
                    dragging -> { dragging = false; snap() }
                    else -> tap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longRun)
                radial?.close()
                if (dragging) { dragging = false; snap() } else scheduleDock()
            }
        }
        return true
    }

    private fun tap() {
        root.performClick()
        val opened = if (PanelHost.isShowing) { PanelHost.hide(); false } else PanelHost.show(host)
        if (!opened && !PanelHost.isShowing && !PanelHost.canShow(host)) {
            Toast.makeText(host, tr("需要悬浮窗权限或增强模式才能打开面板", "The panel needs the overlay permission or enhanced mode"), Toast.LENGTH_SHORT).show()
        }
        if (sprite != null) { if (!prefs.capturePaused) playOnce(Mascot.POKE) } else pulse()
        scheduleDock()
    }

    /** 松手后吸附到最近的左 / 右边，带一点回弹 */
    private fun snap() {
        val b = screen()
        side = if (lp.x + size / 2 < b.centerX()) 0 else 1
        sprite?.mirrored = side == 1
        val margin = dp(24f)
        lp.y = lp.y.coerceIn(b.top + margin, b.bottom - size - margin)
        val from = lp.x
        val to = edgeX()
        savePos()
        if (sprite != null && !prefs.capturePaused) playOnce(Mascot.LAND) else playBase()
        snapAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                if (destroyed) return@addUpdateListener
                lp.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(root, lp) }
            }
            start()
        }
        scheduleDock()
    }

    // ---------------- 环形控制台 ----------------

    private fun openRadial() {
        if (dragging || destroyed) return
        val o = screenPos(root, lp)
        val m = RadialMenu(host, wm, type, o[0] + size / 2f, o[1] + size / 2f, side == 0, radialItems()) {
            radial = null
            scheduleDock()
        }
        if (m.show()) radial = m
    }

    private fun radialItems(): List<RadialItem> {
        val paused = prefs.capturePaused
        val list = mutableListOf(
            RadialItem(R.drawable.ic_gen_copy, tr("打开面板", "Panel")) { if (!PanelHost.isShowing) PanelHost.show(host) },
            RadialItem(R.drawable.ic_gen_history, if (paused) tr("恢复记录", "Resume") else tr("暂停记录", "Pause")) {
                prefs.capturePaused = !paused
                Toast.makeText(host, if (paused) tr("已恢复记录", "Recording resumed") else tr("已暂停记录，复制的内容不会记下来", "Recording paused"), Toast.LENGTH_SHORT).show()
                refreshBase()
            },
        )
        if (Features.isOn(host, Feature.HIDE)) list += RadialItem(R.drawable.ic_gen_lock, tr("锁定隐藏", "Lock")) {
            Vault.lock()
            Toast.makeText(host, tr("隐藏的内容已锁定", "Hidden items locked"), Toast.LENGTH_SHORT).show()
        }
        list += RadialItem(R.drawable.ic_gen_close, tr("今日隐藏", "Hide today")) {
            Toast.makeText(host, tr("悬浮球今天先不出现了，明天自动回来；也可以在随贴设置里恢复", "Hidden until tomorrow. You can bring it back in settings."), Toast.LENGTH_LONG).show()
            FloatBall.hideToday(host)
        }
        list += RadialItem(R.drawable.ic_gen_open, tr("打开随贴", "Open app")) {
            host.startActivity(Intent(host, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return list
    }
}

/** 圆形图标样式：品牌蓝圆底 + 白色剪贴板图标 */
private class BallIcon(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RadialMenu.ACCENT }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * ctx.resources.displayMetrics.density }
    private val glyph = ctx.getDrawable(R.drawable.ic_gen_copy)?.mutate()?.apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - edge.strokeWidth
        c.drawCircle(width / 2f, height / 2f, r, fill)
        c.drawCircle(width / 2f, height / 2f, r, edge)
        glyph?.let {
            val s = (r * 1.1f).toInt()
            it.setBounds(width / 2 - s / 2, height / 2 - s / 2, width / 2 + s / 2, height / 2 + s / 2)
            it.draw(c)
        }
    }
}
