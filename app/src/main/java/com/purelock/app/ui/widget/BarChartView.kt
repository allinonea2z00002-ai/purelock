package com.purelock.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.purelock.app.R
import com.purelock.app.util.DurationFormat

/** A simple bar chart of real values (used for the last 7 days of protection time). */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val valueMs: Long)

    private var bars: List<Bar> = emptyList()
    /** Draw every nth x-axis label (24 hourly bars use 4: 12 AM, 04 AM, 08 AM, 12 PM, 04 PM, 08 PM). */
    var labelStep: Int = 1
    /** False for crowded charts (hourly bars): the bar heights speak for themselves. */
    var showValues: Boolean = true
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun setBars(bars: List<Bar>) {
        this.bars = bars
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (180 * density).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val accent = MaterialColors.getColor(this, R.attr.plSuccess, 0xFF39FF88.toInt())
        val track = MaterialColors.getColor(this, R.attr.plCardHigh, 0xFF1B262B.toInt())
        val secondary = MaterialColors.getColor(this, R.attr.plTextSecondary, 0xFF8FA3A0.toInt())

        val labelH = 22 * density
        val valueH = 18 * density
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        val slot = width.toFloat() / bars.size
        val barW = slot * 0.5f
        val max = (bars.maxOf { it.valueMs }).coerceAtLeast(1L)

        textPaint.textSize = 11 * density
        barPaint.color = accent
        trackPaint.color = track

        bars.forEachIndexed { i, bar ->
            val cx = slot * i + slot / 2f
            rect.set(cx - barW / 2, chartTop, cx + barW / 2, chartBottom)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, trackPaint)

            if (bar.valueMs > 0 && showValues) {
                val h = (chartH * bar.valueMs / max).coerceAtLeast(6 * density)
                rect.set(cx - barW / 2, chartBottom - h, cx + barW / 2, chartBottom)
                canvas.drawRoundRect(rect, 6 * density, 6 * density, barPaint)
                textPaint.color = secondary
                canvas.drawText(DurationFormat.compact(bar.valueMs), cx, chartBottom - h - 4 * density, textPaint)
            } else if (bar.valueMs > 0) {
                val h = (chartH * bar.valueMs / max).coerceAtLeast(6 * density)
                rect.set(cx - barW / 2, chartBottom - h, cx + barW / 2, chartBottom)
                canvas.drawRoundRect(rect, 6 * density, 6 * density, barPaint)
            }
            if (i % labelStep == 0) {
                textPaint.color = secondary
                canvas.drawText(bar.label, cx, height - 5 * density, textPaint)
            }
        }
    }
}
