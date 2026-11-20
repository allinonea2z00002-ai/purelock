package com.purelock.app.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Small, cheap animations (property animators only, no per-frame work in Kotlin). */
object UiAnim {

    /** Cards slide up and fade in one after another. */
    fun cardEntrance(views: List<View>, startDelayMs: Long = 0) {
        val offset = 24f * (views.firstOrNull()?.resources?.displayMetrics?.density ?: 1f)
        views.forEachIndexed { index, v ->
            v.alpha = 0f
            v.translationY = offset
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelayMs + index * 60L)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** A gentle scale-down while a view is pressed. Does not consume touches, so clicks still work. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun hapticConfirm(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
