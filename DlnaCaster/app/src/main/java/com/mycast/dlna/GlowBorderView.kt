package com.mycast.dlna

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** إضاءة ملوّنة تدور حول حواف الشاشة (مثل إضاءة الألعاب). لا تلتقط اللمس. */
class GlowBorderView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val colors = intArrayOf(
        0xFF8B7CFF.toInt(), 0xFF35C3FF.toInt(), 0xFF35D399.toInt(),
        0xFFFF6BD6.toInt(), 0xFF8B7CFF.toInt()
    )
    private val m = Matrix()
    private val rect = RectF()
    private var shader: SweepGradient? = null
    private var angle = 0f
    private var anim: ValueAnimator? = null

    // (سماكة الخط بالـdp، الشفافية) من الهالة الخارجية الباهتة إلى الخط الساطع
    private val layers = arrayOf(
        floatArrayOf(16f, 0.08f), floatArrayOf(11f, 0.16f),
        floatArrayOf(6f, 0.32f), floatArrayOf(2.5f, 1f)
    )

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = SweepGradient(w / 2f, h / 2f, colors, null)
        val inset = 4f * d
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        val sh = shader ?: return
        m.setRotate(angle, width / 2f, height / 2f)
        sh.setLocalMatrix(m)
        paint.shader = sh
        val r = 26f * d
        for (l in layers) {
            paint.strokeWidth = l[0] * d
            paint.alpha = (l[1] * 255).toInt()
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    private fun update() {
        if (isAttachedToWindow && isShown) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 5000
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            anim?.cancel()
            anim = null
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); update() }
    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility); update()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); update()
    }
}
