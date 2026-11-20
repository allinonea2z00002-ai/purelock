package com.purelock.app.ui.drawer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.purelock.app.R
import com.purelock.app.databinding.ActivityHelpBinding
import com.purelock.app.ui.ThemeManager

/** Help: static FAQ screen (Stay Focused-like questions) + a Send feedback row. */
class HelpActivity : AppCompatActivity() {

    private lateinit var b: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerHelp.tvSfTitle.setText(R.string.sf_set_help_title)
        b.headerHelp.btnSfBack.setOnClickListener { finish() }

        RowBinder.navRow(
            b.rowHelpFeedback, this, R.drawable.ic_sf_flag,
            R.color.sf_pink, R.color.sf_red,
            R.string.sf_acct_feedback
        ) { AppActions.sendFeedback(this) }
    }
}
