package com.purelock.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.purelock.app.R
import com.purelock.app.util.DurationFormat

/** A simple line chart of real values (last 7 days of protection time, Mon-Sun). */
class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Point(val label: String, val valueMs: Long)

    private var points: List<Point> = emptyList()
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPath = Path()
    private val linePath = Path()

    fun setPoints(points: List<Point>) {
        this.points = points
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (180 * density).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val accent = MaterialColors.getColor(this, R.attr.plSuccess, 0xFF39FF88.toInt())
        val track = MaterialColors.getColor(this, R.attr.plCardHigh, 0xFF1B262B.toInt())
        val secondary = MaterialColors.getColor(this, R.attr.plTextSecondary, 0xFF8FA3A0.toInt())
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFEAF2EE.toInt())

        val labelH = 22 * density
        val valueH = 18 * density
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        val padX = 24 * density
        val plotW = width - padX * 2
        val max = (points.maxOf { it.valueMs }).coerceAtLeast(1L)

        // grid lines
        gridPaint.color = track
        gridPaint.strokeWidth = 1f * density
        for (i in 0..2) {
            val y = chartTop + chartH * i / 2f
            canvas.drawLine(padX, y, width - padX, y, gridPaint)
        }

        fun x(i: Int) = if (points.size == 1) width / 2f else padX + plotW * i / (points.size - 1)
        fun y(v: Long) = chartBottom - chartH * (v.toFloat() / max).coerceIn(0f, 1f) * 0.85f

        linePath.reset()
        fillPath.reset()
        points.forEachIndexed { i, p ->
            val px = x(i)
            val py = y(p.valueMs)
            if (i == 0) {
                linePath.moveTo(px, py)
                fillPath.moveTo(px, chartBottom)
                fillPath.lineTo(px, py)
            } else {
                linePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(x(points.size - 1), chartBottom)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, chartTop, 0f, chartBottom,
            accent and 0x00FFFFFF or 0x4D000000, 0x0039FF88, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = accent
        linePaint.strokeWidth = 3f * density
        canvas.drawPath(linePath, linePaint)

        textPaint.textSize = 11 * density
        points.forEachIndexed { i, p ->
            val px = x(i)
            val py = y(p.valueMs)
            dotPaint.color = accent
            canvas.drawCircle(px, py, 4.5f * density, dotPaint)
            dotPaint.color = onSurface
            canvas.drawCircle(px, py, 2f * density, dotPaint)
            if (p.valueMs > 0) {
                textPaint.color = secondary
                canvas.drawText(DurationFormat.compact(p.valueMs), px, py - 8 * density, textPaint)
            }
            textPaint.color = secondary
            canvas.drawText(p.label, px, height - 5 * density, textPaint)
        }
    }
}
