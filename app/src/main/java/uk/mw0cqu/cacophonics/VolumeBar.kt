// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A simple horizontal volume slider, 0..100, drawn by hand so its colours are exactly what the
 * panel asks for (the stock Android slider takes its colours from the theme and was unreadable
 * on the light grey "live" panel).
 */
class VolumeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called continuously while the finger drags. */
    var onChange: ((Int) -> Unit)? = null

    /** Called once when the finger lifts, with the final value. */
    var onRelease: ((Int) -> Unit)? = null

    /** Text shown above the knob while dragging. */
    var labeler: (Int) -> String = { "$it%" }

    var dragging = false
        private set(value) {
            field = value
            invalidate()
        }

    var progress = 100
        set(value) {
            val v = value.coerceIn(0, 100)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private val sidePad = 14f * d
    private val trackH = 8f * d
    private val thumbR = 11f * d

    init {
        setColors(0xFF4FC3F7.toInt(), 0x809AA4B2.toInt(), 0xFF1B1F27.toInt())
    }

    /** fill = filled part and knob; track = empty part; ring = thin outline round the knob. */
    fun setColors(fill: Int, track: Int, ring: Int) {
        fillPaint.color = fill
        trackPaint.color = track
        ringPaint.color = ring
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    private fun left() = sidePad
    private fun right() = width - sidePad

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val x = left() + (right() - left()) * progress / 100f

        // empty track
        rect.set(left(), cy - trackH / 2, right(), cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)

        // filled part
        rect.set(left(), cy - trackH / 2, x, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, fillPaint)

        // knob, with an outline so it stands out on any background
        canvas.drawCircle(x, cy, thumbR, fillPaint)
        canvas.drawCircle(x, cy, thumbR, ringPaint)

        // while dragging, show the value above the knob
        if (dragging) {
            textPaint.color = fillPaint.color
            val tx = min(max(x, left()), right())
            canvas.drawText(labeler(progress), tx, cy - thumbR - 3f * d, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                update(event.x)
            }

            MotionEvent.ACTION_MOVE -> update(event.x)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                update(event.x)
                dragging = false
                onRelease?.invoke(progress)
            }
        }
        return true
    }

    private fun update(x: Float) {
        val span = max(1f, right() - left())
        val p = (((x - left()) / span) * 100f).roundToInt()
        val v = min(100, max(0, p))
        if (v != progress) {
            progress = v
            onChange?.invoke(v)
        }
    }
}
