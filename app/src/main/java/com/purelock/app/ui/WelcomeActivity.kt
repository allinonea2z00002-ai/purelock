package com.purelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.purelock.app.databinding.ActivityWelcomeBinding

/**
 * Mockup splash / welcome screen. Shown only until the user taps "Get Started";
 * afterwards the app opens straight into [MainActivity].
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEN, false)) {
            openMain()
            return
        }
        val b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        UiAnim.pressEffect(b.btnGetStarted)
        b.btnGetStarted.setOnClickListener {
            UiAnim.haptic(it)
            prefs.edit().putBoolean(KEY_SEEN, true).apply()
            openMain()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        const val PREFS = "purelock_ui"
        const val KEY_SEEN = "welcome_seen"
    }
}
