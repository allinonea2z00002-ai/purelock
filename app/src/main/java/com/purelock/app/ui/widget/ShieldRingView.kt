package com.purelock.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import com.purelock.app.R
import kotlin.math.abs
import kotlin.math.min

/**
 * The home-screen centrepiece: a countdown ring around an animated shield and lock.
 * The pulse animation only runs while the view is visible and the protection is active,
 * so it costs no battery otherwise.
 */
class ShieldRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var active = false
    private var warning = false
    private var progress = 0f
    private var timeText = ""
    private var subText = ""
    private var pulse = 0f

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shieldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shieldStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val shieldPath = Path().apply {
        moveTo(0f, -1f)
        lineTo(0.85f, -0.65f)
        lineTo(0.85f, 0.1f)
        cubicTo(0.85f, 0.6f, 0.4f, 0.9f, 0f, 1f)
        cubicTo(-0.4f, 0.9f, -0.85f, 0.6f, -0.85f, 0.1f)
        lineTo(-0.85f, -0.65f)
        close()
    }
    private val bodyRect = RectF(-0.34f, -0.02f, 0.34f, 0.52f)
    private val shackleRect = RectF(-0.21f, -0.42f, 0.21f, 0.0f)
    private val arcRect = RectF()

    private var pulseAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)

    fun setState(active: Boolean, warning: Boolean, progress: Float, timeText: String, subText: String) {
        val activeChanged = this.active != active
        this.active = active
        this.warning = warning
        this.timeText = timeText
        this.subText = subText
        setProgressSmooth(progress.coerceIn(0f, 1f))
        if (activeChanged) updatePulse()
        invalidate()
    }

    private fun setProgressSmooth(target: Float) {
        if (abs(target - progress) < 0.002f) {
            progress = target
            return
        }
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 600
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updatePulse() {
        val shouldRun = active && isShown
        if (shouldRun && pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!shouldRun) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel(); pulseAnimator = null
        progressAnimator?.cancel(); progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updatePulse()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resources.getDimensionPixelSize(R.dimen.ring_size)
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val accent = if (warning) color(R.attr.plWarning, 0xFFFFB020.toInt()) else color(R.attr.plSuccess, 0xFF39FF88.toInt())
        val outline = color(com.google.android.material.R.attr.colorOutline, 0xFF26343A.toInt())
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFFEAF2EE.toInt())
        val secondary = color(R.attr.plTextSecondary, 0xFF8FA3A0.toInt())

        val d = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = 10f * density
        val radius = d / 2f - stroke * 1.6f

        // soft glow
        if (active) {
            glowPaint.color = accent
            glowPaint.alpha = (26 + 36 * pulse).toInt()
            canvas.drawCircle(cx, cy, radius * (0.86f + 0.1f * pulse), glowPaint)
        }

        // ring
        trackPaint.color = outline
        trackPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, trackPaint)
        if (progress > 0f) {
            arcPaint.color = accent
            arcPaint.strokeWidth = stroke
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
        }

        // shield + lock (drawn in unit coordinates, then scaled)
        val unit = d * 0.17f
        val shieldCy = cy - d * 0.10f
        canvas.save()
        canvas.translate(cx, shieldCy)
        canvas.scale(unit, unit)

        shieldFill.color = if (active) accent else outline
        shieldFill.alpha = if (active) 46 else 120
        canvas.drawPath(shieldPath, shieldFill)
        shieldStroke.color = if (active) accent else secondary
        shieldStroke.strokeWidth = 2.4f * density / unit
        canvas.drawPath(shieldPath, shieldStroke)

        lockPaint.color = if (active) accent else secondary
        canvas.drawRoundRect(bodyRect, 0.1f, 0.1f, lockPaint)
        shacklePaint.color = if (active) accent else secondary
        shacklePaint.strokeWidth = 2.6f * density / unit
        canvas.save()
        if (!active) canvas.translate(0.2f, -0.06f) // open lock when inactive
        canvas.drawArc(shackleRect, 180f, 180f, false, shacklePaint)
        canvas.drawLine(-0.21f, -0.21f, -0.21f, 0f, shacklePaint)
        canvas.drawLine(0.21f, -0.21f, 0.21f, if (active) 0f else -0.1f, shacklePaint)
        canvas.restore()
        canvas.restore()

        // text
        timePaint.color = onSurface
        timePaint.textSize = d * 0.115f
        canvas.drawText(timeText, cx, cy + d * 0.24f, timePaint)
        subPaint.color = secondary
        subPaint.textSize = d * 0.05f
        canvas.drawText(subText, cx, cy + d * 0.32f, subPaint)
    }
}
