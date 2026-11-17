package com.mistpeaklab.clipdock.floatball

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.mistpeaklab.clipdock.R

/**
 * 看板娘「卡卡」的动作。素材是 res/raw 里的动画 WebP（16 帧，每帧 70 ms），
 * 由 docs/design/mascot/v2/export.py 统一了画布、大小和脚底基线。
 * 素材按停靠在左边画的：探头从左边探出、抛出朝右；停靠右边时整张镜像。
 */
enum class Mascot(val res: Int, val loop: Boolean) {
    IDLE(R.raw.mascot_idle, true),
    WAVE(R.raw.mascot_wave, false),
    POKE(R.raw.mascot_poke, false),
    CATCH(R.raw.mascot_catch, false),
    THROW(R.raw.mascot_throw, false),
    HAPPY(R.raw.mascot_happy, false),
    SHY(R.raw.mascot_shy, false),
    PEEK(R.raw.mascot_peek, false),
    DRAG(R.raw.mascot_drag, true),
    LAND(R.raw.mascot_land, false),
    SLEEP(R.raw.mascot_sleep, true),
    BORED(R.raw.mascot_bored, false),
}

/**
 * 播放卡卡动画的 View。用系统的 AnimatedImageDrawable 逐帧解码播放，不把整套帧展开到内存里。
 * 一次性的动作每次重新解码一份（从头播、结束回调可靠）；循环动作没换就不重新解码。
 */
class SpriteView(ctx: Context) : View(ctx) {
    var mirrored = false
        set(v) { field = v; invalidate() }

    var current: Mascot? = null
        private set

    private var drawable: AnimatedImageDrawable? = null
    private var onEnd: (() -> Unit)? = null
    private var token = 0

    /** 播放一个动作；一次性动作播完调用 [then]（被别的动作打断时不调用） */
    fun play(m: Mascot, then: (() -> Unit)? = null) {
        if (m.loop && m == current && drawable?.isRunning == true) { onEnd = null; return }
        stopCurrent()
        val d = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, m.res)) as? AnimatedImageDrawable
        }.getOrNull()
        current = m
        onEnd = then
        drawable = d
        val my = ++token
        if (d == null) { if (then != null) post { if (token == my) finish() }; return }
        d.callback = this
        d.setBounds(0, 0, width, height)
        d.repeatCount = if (m.loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
        d.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) { if (token == my) finish() }
        })
        // 系统关闭了动画：只显示第一帧，一次性动作当作立刻播完
        if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) d.start()
        else if (!m.loop) post { if (token == my) finish() }
        invalidate()
    }

    /** 停在当前帧（收起后探头播完、窗口隐藏时用） */
    fun pause() { drawable?.stop() }

    private fun finish() {
        val f = onEnd
        onEnd = null
        f?.invoke()
    }

    private fun stopCurrent() {
        token++
        drawable?.let { it.clearAnimationCallbacks(); it.stop(); it.callback = null }
        drawable = null
        onEnd = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        drawable?.setBounds(0, 0, w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val d = drawable ?: return
        if (!d.isRunning && ValueAnimator.areAnimatorsEnabled()) d.start()
    }

    override fun onDetachedFromWindow() {
        stopCurrent()
        current = null
        super.onDetachedFromWindow()
    }

    override fun verifyDrawable(who: Drawable) = who === drawable || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return
        if (mirrored) {
            canvas.save()
            canvas.scale(-1f, 1f, width / 2f, 0f)
            d.draw(canvas)
            canvas.restore()
        } else d.draw(canvas)
    }
}
