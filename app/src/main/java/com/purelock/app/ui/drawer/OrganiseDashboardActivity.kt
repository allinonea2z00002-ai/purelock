package com.purelock.app.ui.drawer

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.purelock.app.R
import com.purelock.app.databinding.ActivityOrganiseDashboardBinding
import com.purelock.app.ui.ThemeManager

/**
 * Organise Dashboard (FOUNDATION §1 decision 14): show/hide checkboxes per
 * Dashboard section, persisted in [DashSections] — SharedPreferences
 * `"purelock_ui"`, key `"dash_sections"`, a comma-separated string of the
 * VISIBLE section keys (e.g. `"analytics,take_break,mode,quick_actions,
 * profiles,templates"`; absent = all visible, empty = none visible).
 * The Dashboard (Area A) reads it via [DashSections.visibleSections].
 * Changes save immediately; "Show all sections" re-checks every box.
 */
class OrganiseDashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrganiseDashboardBinding
    private val boxes = mutableListOf<Pair<CheckBox, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityOrganiseDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerOrganise.tvSfTitle.setText(R.string.sf_set_organise_title)
        b.headerOrganise.btnSfBack.setOnClickListener { finish() }

        boxes += b.cbOrganiseAnalytics to DashSections.ANALYTICS
        boxes += b.cbOrganiseTakeBreak to DashSections.TAKE_BREAK
        boxes += b.cbOrganiseMode to DashSections.MODE
        boxes += b.cbOrganiseQuick to DashSections.QUICK_ACTIONS
        boxes += b.cbOrganiseProfiles to DashSections.PROFILES
        boxes += b.cbOrganiseTemplates to DashSections.TEMPLATES

        val visible = DashSections.visibleSections(this)
        for ((box, key) in boxes) {
            box.isChecked = key in visible
            box.setOnCheckedChangeListener { _, _ -> save() }
        }

        b.btnOrganiseShowAll.setOnClickListener {
            for ((box, _) in boxes) box.isChecked = true
        }
    }

    private fun save() {
        DashSections.setVisibleSections(
            this,
            boxes.filter { (box, _) -> box.isChecked }.map { (_, key) -> key }.toSet()
        )
    }
}
