package com.purelock.app.ui.drawer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.purelock.app.R

/**
 * Shared outward intents used by the drawer, the Account tab and Help
 * (FOUNDATION §1 decisions 14–15): feedback email, Play Store rating,
 * share sheet, opening a URL, and opening an activity by class name.
 * The by-name form is used for screens owned by other areas
 * (Take a Break, All Sites) so this file compiles even before they land;
 * a missing target shows a toast instead of crashing.
 */
object AppActions {

    /** Feedback email: ACTION_SENDTO mailto:, subject "PureLock feedback", no preset address. */
    fun sendFeedback(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.sf_acct_feedback_subject))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.sf_acct_no_email, Toast.LENGTH_SHORT).show()
        }
    }

    /** Play Store rating page; falls back to the web Store URL, then a toast. */
    fun rateApp(context: Context) {
        val pkg = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            )
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    )
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.sf_acct_no_store, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** System share sheet with the Play Store link. */
    fun shareApp(context: Context) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.sf_acct_share_text))
        context.startActivity(Intent.createChooser(intent, null))
    }

    /** Opens a URL in the browser; toasts when nothing can handle it. */
    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.sf_set_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** Starts an activity in this app by fully-qualified class name. False when it cannot be resolved. */
    fun openActivity(context: Context, className: String): Boolean {
        val intent = Intent().setClassName(context.packageName, className)
        if (intent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, R.string.sf_set_not_available, Toast.LENGTH_SHORT).show()
            return false
        }
        context.startActivity(intent)
        return true
    }
}
