// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Start / Stop button drawn as a road sign: a red STOP octagon while streams are running,
 * a green GO roundel when they are stopped.
 */
class SignView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** True = streams running, so the sign says STOP. */
    var running = false
        set(value) {
            if (field != value) {
                field = value
                contentDescription = if (value) "Stop all streams" else "Start streams"
                invalidate()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val path = Path()

    init {
        contentDescription = "Start streams"
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(
            resolveSize((56 * d).toInt(), widthMeasureSpec),
            resolveSize((56 * d).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2f
        edge.strokeWidth = r * 0.07f

        if (running) {
            // Octagon with a flat top, as on a real STOP sign.
            fill.color = Color.rgb(200, 16, 46)
            path.reset()
            for (k in 0 until 8) {
                val a = Math.toRadians(22.5 + 45.0 * k)
                val x = cx + r * cos(a).toFloat() / cos(Math.toRadians(22.5)).toFloat()
                val y = cy + r * sin(a).toFloat() / cos(Math.toRadians(22.5)).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, fill)
            // thin white border just inside the edge
            canvas.save()
            canvas.scale(0.88f, 0.88f, cx, cy)
            canvas.drawPath(path, edge)
            canvas.restore()
            drawLabel(canvas, "STOP", cx, cy, r * 0.5f, 0.6f)
        } else {
            fill.color = Color.rgb(0, 150, 64)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r * 0.88f, edge)
            drawLabel(canvas, "GO", cx, cy, r * 0.85f, 0.7f)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float, maxWidth: Float) {
        text.textSize = size
        // Shrink if the word is wider than the sign allows.
        val limit = min(width, height) * maxWidth
        val w = text.measureText(label)
        if (w > limit) text.textSize = size * limit / w
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, cx, baseline, text)
    }
}
