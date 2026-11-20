package com.purelock.app.ui.drawer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.purelock.app.BuildConfig
import com.purelock.app.R
import com.purelock.app.databinding.ActivityAboutBinding
import com.purelock.app.ui.ThemeManager

/** About: app name, version, package. */
class AboutActivity : AppCompatActivity() {

    private lateinit var b: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerAbout.tvSfTitle.setText(R.string.sf_set_about_title)
        b.headerAbout.btnSfBack.setOnClickListener { finish() }

        b.tvAboutVersion.text = getString(R.string.sf_set_about_version, BuildConfig.VERSION_NAME)
        b.tvAboutPackage.text = getString(R.string.sf_set_about_package, packageName)
    }
}
