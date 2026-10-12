// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the tag text, wrapped over up to three lines, filling the space it is given.
 *
 * If the text is too long for the space, the lines that fit stay still and the LAST visible
 * line scrolls right to left (pausing briefly at the start, then looping), instead of being
 * cut off with "...".
 */
class TagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_LINES = 3
        private const val PAUSE_MS = 1500L
        private const val GAP_DP = 48f
        private const val SPEED_DP_PER_SEC = 70f
    }

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 24f * resources.displayMetrics.scaledDensity
        color = 0xFFFFFFFF.toInt()
    }

    private var head: StaticLayout? = null // the lines that stay still
    private var headLines = 0
    private var tail = "" // the text of the last line, which scrolls
    private var tailWidth = 0f
    private var scrolling = false
    private var totalLines = 1
    private var lineH = 1
    private var startTime = SystemClock.uptimeMillis()

    var text: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            contentDescription = value
            startTime = SystemClock.uptimeMillis()
            rebuild()
        }

    fun setTextColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setTextSizePx(px: Float) {
        paint.textSize = px
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    @Suppress("DEPRECATION")
    private fun layoutFor(s: String, width: Int) =
        StaticLayout(s, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)

    private fun rebuild() {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val str = text.toString()
        val full = layoutFor(str, w)
        lineH = max(1, full.getLineBottom(0) - full.getLineTop(0))
        val fit = max(1, min(MAX_LINES, h / lineH))

        if (full.lineCount <= fit) {
            // Everything fits: plain wrapped text.
            head = full
            headLines = full.lineCount
            tail = ""
            scrolling = false
            totalLines = headLines
        } else {
            // Too long: keep the first (fit - 1) lines still, scroll everything left over on one line.
            val stillLines = fit - 1
            head = if (stillLines > 0) {
                layoutFor(str.substring(0, full.getLineStart(stillLines)).trimEnd(), w)
            } else {
                null
            }
            headLines = head?.lineCount ?: 0
            tail = str.substring(full.getLineStart(stillLines)).trim()
            tailWidth = paint.measureText(tail)
            scrolling = true
            totalLines = headLines + 1
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val contentH = height - paddingTop - paddingBottom
        val top = paddingTop + (contentH - lineH * totalLines) / 2f

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), top)
        head?.draw(canvas)
        canvas.restore()

        if (!scrolling) return

        val lineTop = top + headLines * lineH
        val baseline = lineTop - paint.ascent()
        val avail = width - paddingLeft - paddingRight
        val gap = GAP_DP * density

        canvas.save()
        canvas.clipRect(
            paddingLeft.toFloat(), lineTop,
            (width - paddingRight).toFloat(), lineTop + lineH
        )
        if (tailWidth <= avail) {
            canvas.drawText(tail, paddingLeft.toFloat(), baseline, paint)
        } else {
            val period = tailWidth + gap
            val moving = max(0L, SystemClock.uptimeMillis() - startTime - PAUSE_MS)
            val offset = (moving / 1000f * SPEED_DP_PER_SEC * density) % period
            val x = paddingLeft - offset
            canvas.drawText(tail, x, baseline, paint)
            canvas.drawText(tail, x + period, baseline, paint) // second copy so the loop is seamless
        }
        canvas.restore()

        postInvalidateOnAnimation()
    }
}
