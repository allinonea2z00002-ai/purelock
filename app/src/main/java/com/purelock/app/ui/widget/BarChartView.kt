package com.purelock.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.purelock.app.R
import com.purelock.app.util.DurationFormat

/**
 * Hourly bar chart for Analytics (Stay Focused S14/S16/S18, SPEC §1.1): vertical
 * gradient bars (light blue -> indigo), light track behind each bar, y-axis labels on
 * the left, an optional dashed horizontal reference line, and optional value labels
 * above the bars (used for the unlock counts in S18).
 *
 * Same public API as before: [setBars], [labelStep], [showValues]. New optional
 * properties: [yLabels] (values 0..max, evenly spaced), [dashedLineFraction]
 * (fraction of the max height where the dashed line is drawn), [valueFormatter].
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val valueMs: Long)

    private var bars: List<Bar> = emptyList()
    /** Draw every nth x-axis label (24 hourly bars use 4: 12 AM, 04 AM, 08 AM, 12 PM, 04 PM, 08 PM). */
    var labelStep: Int = 1
    /** False for crowded charts (hourly bars): the bar heights speak for themselves. */
    var showValues: Boolean = true
    /** Y-axis labels, evenly spaced from 0 (bottom) to the max (top), e.g. 0m/19m/39m/59m. */
    var yLabels: List<String> = emptyList()
    /** Fraction of the chart height (0..1) where the dashed reference line is drawn, or null. */
    var dashedLineFraction: Float? = null
    /** Formats the value label drawn above a bar; defaults to [DurationFormat.compact]. */
    var valueFormatter: ((Long) -> String)? = null

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val yTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
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
        val track = MaterialColors.getColor(this, R.attr.plCardHigh, 0xFFE8EAF2.toInt())
        val secondary = MaterialColors.getColor(this, R.attr.plTextSecondary, 0xFF8A8FA8.toInt())
        val barTop = ContextCompat.getColor(context, R.color.sf_bar_top)
        val barBottom = ContextCompat.getColor(context, R.color.sf_bar_bottom)

        val labelH = 22 * density
        val valueH = if (showValues) 18 * density else 6 * density
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        // Room for the y-axis labels on the left.
        val leftPad = if (yLabels.isNotEmpty()) 34 * density else 0f
        val chartW = width - leftPad
        val slot = chartW / bars.size
        val barW = slot * 0.5f
        val max = (bars.maxOf { it.valueMs }).coerceAtLeast(1L)

        barPaint.shader = LinearGradient(
            0f, chartTop, 0f, chartBottom, barTop, barBottom, Shader.TileMode.CLAMP
        )
        trackPaint.color = track
        textPaint.textSize = 11 * density
        yTextPaint.textSize = 11 * density
        yTextPaint.color = secondary
        dashedPaint.color = secondary
        dashedPaint.strokeWidth = 1.5f * density

        // Dashed horizontal reference line (SPEC §1.1).
        dashedLineFraction?.let { fraction ->
            val y = chartBottom - chartH * fraction.coerceIn(0f, 1f)
            canvas.drawLine(leftPad, y, width.toFloat(), y, dashedPaint)
        }

        // Y-axis labels, evenly spaced 0..max.
        if (yLabels.size > 1) {
            val step = chartH / (yLabels.size - 1)
            yLabels.forEachIndexed { i, label ->
                val y = chartBottom - step * i + 4 * density
                canvas.drawText(label, leftPad - 6 * density, y, yTextPaint)
            }
        }

        bars.forEachIndexed { i, bar ->
            val cx = leftPad + slot * i + slot / 2f
            rect.set(cx - barW / 2, chartTop, cx + barW / 2, chartBottom)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, trackPaint)

            if (bar.valueMs > 0) {
                val h = (chartH * bar.valueMs / max).coerceAtLeast(6 * density)
                rect.set(cx - barW / 2, chartBottom - h, cx + barW / 2, chartBottom)
                canvas.drawRoundRect(rect, 6 * density, 6 * density, barPaint)
                if (showValues) {
                    textPaint.color = secondary
                    val text = valueFormatter?.invoke(bar.valueMs) ?: DurationFormat.compact(bar.valueMs)
                    canvas.drawText(text, cx, chartBottom - h - 4 * density, textPaint)
                }
            }
            if (i % labelStep == 0) {
                textPaint.color = secondary
                canvas.drawText(bar.label, cx, height - 5 * density, textPaint)
            }
        }
        barPaint.shader = null
    }
}
