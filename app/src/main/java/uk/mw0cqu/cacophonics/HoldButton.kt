// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A button that has to be pressed and HELD. While it is held it fills with red from left to right;
 * when it is full [onConfirmed] is called. Letting go early empties it again and nothing happens.
 */
class HoldButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onConfirmed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val red = Color.rgb(200, 16, 46)

    /** 0 = empty, 1 = full. */
    private var fill = 0f
    private var done = false
    private var anim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = 19f * density
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val box = RectF()

    init {
        isClickable = true
        contentDescription = "Press and hold to delete all stream data"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 2f * density
        val radius = 14f * density
        box.set(inset, inset, w - inset, h - inset)

        // empty button: white with a red outline
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(box, radius, radius, paint)

        val line1 = "PRESS AND HOLD"
        val line2 = when {
            done -> "deleting…"
            fill > 0f -> "keep holding…"
            else -> "to delete everything"
        }
        val y1 = h / 2 - 2f * density
        val y2 = h / 2 + 22f * density
        val edge = w * fill

        // the part not yet filled: red words on white
        canvas.save()
        canvas.clipRect(edge, 0f, w, h)
        title.color = red
        hint.color = red
        canvas.drawText(line1, w / 2, y1, title)
        canvas.drawText(line2, w / 2, y2, hint)
        canvas.restore()

        // the filled part: white words on red
        if (fill > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, edge, h)
            paint.color = red
            canvas.drawRoundRect(box, radius, radius, paint)
            title.color = Color.WHITE
            hint.color = Color.WHITE
            canvas.drawText(line1, w / 2, y1, title)
            canvas.drawText(line2, w / 2, y2, hint)
            canvas.restore()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = red
        canvas.drawRoundRect(box, radius, radius, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!done) animateTo(1f, ((1f - fill) * HOLD_MS).toLong())
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!done) animateTo(0f, (fill * 350f).toLong())
            }
        }
        return true
    }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        var cancelled = false
        anim = ValueAnimator.ofFloat(fill, target).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener {
                fill = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && target >= 1f && !done) {
                        done = true
                        fill = 1f
                        invalidate()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onConfirmed?.invoke()
                    }
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** How long the button has to be held. */
        const val HOLD_MS = 1500L
    }
}
