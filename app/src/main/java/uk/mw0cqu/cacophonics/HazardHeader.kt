// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Yellow and black warning-tape header with the word CAUTION on a yellow plate. */
class HazardHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val amber = Color.rgb(255, 193, 7)
    private val ink = Color.rgb(20, 20, 20)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val plate = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = amber
        canvas.drawRect(0f, 0f, w, h, paint)

        // diagonal black stripes
        paint.color = ink
        val step = 44f * density
        val half = step / 2
        var x = -h
        while (x < w + h) {
            path.reset()
            path.moveTo(x, h)
            path.lineTo(x + half, h)
            path.lineTo(x + half + h, 0f)
            path.lineTo(x + h, 0f)
            path.close()
            canvas.drawPath(path, paint)
            x += step
        }

        // plate with the word on it
        plate.set(w * 0.10f, h * 0.18f, w * 0.90f, h * 0.82f)
        paint.color = amber
        canvas.drawRoundRect(plate, 8f * density, 8f * density, paint)
        text.textSize = plate.height() * 0.62f
        val limit = plate.width() * 0.92f
        val measured = text.measureText(WORD)
        if (measured > limit) text.textSize = text.textSize * limit / measured
        val baseline = plate.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(WORD, plate.centerX(), baseline, text)
    }

    private companion object {
        const val WORD = "CAUTION"
    }
}
