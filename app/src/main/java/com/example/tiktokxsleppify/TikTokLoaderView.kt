package com.example.tiktokxsleppify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Loader estilo TikTok: dos puntos (rojo y cian) que intercambian posiciones cruzándose
 * en el centro. Se ALEJAN despacio y se ACERCAN rápido. Cada punto conserva su color
 * (sin morado): al cruzarse el rojo simplemente queda por encima del cian.
 */
class TikTokLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val red = Color.parseColor("#FE2C55")
    private val cyan = Color.parseColor("#25F4EE")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f // 0 .. 1 (un ciclo completo)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    // Suavizado (smoothstep) para redondear los extremos de cada tramo
    private fun smooth(u: Float): Float {
        val c = u.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = height * 0.26f // un poco más pequeños
        val amplitude = radius * 1.7f // separación moderada (no se van a los bordes)

        // Movimiento asimétrico con cruce de lados:
        //  - se ALEJAN despacio (tramos del 35% del ciclo)
        //  - se ACERCAN rápido (tramos del 15%)
        val t = phase
        val x: Float = when {
            t < 0.35f -> smooth(t / 0.35f)                   // 0 -> +1 despacio
            t < 0.50f -> 1f - smooth((t - 0.35f) / 0.15f)    // +1 -> 0 rápido (cruce)
            t < 0.85f -> -smooth((t - 0.50f) / 0.35f)        // 0 -> -1 despacio
            else -> -(1f - smooth((t - 0.85f) / 0.15f))      // -1 -> 0 rápido (cruce)
        }

        val redX = cx + amplitude * x
        val cyanX = cx - amplitude * x

        // Sin morado: cada punto conserva su color; al cruzarse el rojo queda encima del cian.
        paint.color = cyan
        canvas.drawCircle(cyanX, cy, radius, paint)
        paint.color = red
        canvas.drawCircle(redX, cy, radius, paint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
