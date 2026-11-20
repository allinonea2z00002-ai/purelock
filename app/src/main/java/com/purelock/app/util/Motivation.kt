package com.purelock.app.util

import android.content.Context
import com.purelock.app.R
import java.time.LocalDate

object Motivation {

    /** The same message all day (changes at midnight), in the app's current language. */
    fun daily(context: Context): String {
        val messages = context.resources.getStringArray(R.array.motivation_messages)
        return messages[LocalDate.now().dayOfYear % messages.size]
    }

    /** A random message, e.g. for the block screen. */
    fun random(context: Context): String {
        val messages = context.resources.getStringArray(R.array.motivation_messages)
        return messages.random()
    }
}
