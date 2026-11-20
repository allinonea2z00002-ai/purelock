package com.purelock.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.ImageView
import com.purelock.app.R
import com.purelock.app.data.prefs.UiPrefs

/**
 * Applies the saved theme and fades between themes without a flicker: a snapshot of the old screen is
 * laid over the recreated activity and faded out, so colours animate instead of jumping.
 */
object ThemeManager {

    private var snapshot: Bitmap? = null

    /** Call before super.onCreate() in every activity. */
    fun apply(activity: Activity) {
        val white = UiPrefs.theme(activity) == UiPrefs.THEME_WHITE
        activity.setTheme(if (white) R.style.Theme_PureLock_White else R.style.Theme_PureLock_Dark)
    }

    fun switchTheme(activity: Activity, theme: String) {
        if (UiPrefs.theme(activity) == theme) return
        snapshot = captureOrNull(activity)
        UiPrefs.setTheme(activity, theme)
        activity.recreate()
    }

    /** Call once after setContentView() in the main activity. */
    fun playTransitionIn(activity: Activity) {
        val bitmap = snapshot ?: return
        snapshot = null
        val decor = activity.window.decorView as ViewGroup
        val overlay = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = true
        }
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.animate()
            .alpha(0f)
            .setDuration(380)
            .withEndAction {
                decor.removeView(overlay)
                bitmap.recycle()
            }
            .start()
    }

    private fun captureOrNull(activity: Activity): Bitmap? = try {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) null
        else Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    } catch (e: OutOfMemoryError) {
        null
    }
}
