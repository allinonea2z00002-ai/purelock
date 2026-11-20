package com.purelock.app.ui.apps

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.databinding.ActivityAppRuleBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

/**
 * Mockup screen 5: App Blocker detail as a full screen. Same data (icon, label,
 * package, rule state, today's usage, limit) and the same save logic
 * ([AppListViewModel.saveRule]) as the old rule dialog; only the presentation
 * changed. Mockup radios map to the old fields:
 * Block = block + not during-lock-only; Block During Lock = block + during-lock;
 * Always Allow = allowed.
 */
class AppRuleActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppRuleBinding
    private val vm: AppListViewModel by viewModels()
    private var item: AppItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAppRuleBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        b.toolbarRule.setNavigationOnClickListener { finish() }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()

        b.sbLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                b.tvLimitValue.text = getString(R.string.app_rule_limit_value, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        b.btnSaveRule.setOnClickListener {
            val current = item ?: return@setOnClickListener
            val mode = when (b.rgRuleMode.checkedRadioButtonId) {
                R.id.rbModeDuring -> Mode.DURING
                R.id.rbModeAllow -> Mode.ALLOW
                else -> Mode.BLOCK
            }
            lifecycleScope.launch {
                val ok = vm.saveRule(
                    item = current,
                    block = mode != Mode.ALLOW,
                    blockDuringLock = mode == Mode.DURING,
                    limitMinutes = b.sbLimit.progress,
                    allowed = mode == Mode.ALLOW
                )
                if (ok) finish()
                else Toast.makeText(this@AppRuleActivity, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.items.collect { items ->
                    val found = items.firstOrNull { it.packageName == packageName }
                    if (found != null && found != item) render(found)
                }
            }
        }
    }

    private fun render(app: AppItem) {
        item = app
        b.ivRuleIcon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                ?: getDrawable(R.drawable.ic_nav_shield)
        )
        b.tvRuleLabel.text = app.label
        b.tvRulePackage.text = app.packageName
        // The "Today's Usage" label is a separate view; here only the value.
        b.tvRuleUsage.text = DurationFormat.compact(app.usageTodayMs)

        // Badge: Blocked (red) / Allowed (green) / no rule yet (grey).
        val (badgeText, badgeAttr) = when {
            app.alwaysAvailable -> R.string.app_state_always to R.attr.plTextSecondary
            app.rule != null -> R.string.app_rule_blocked_badge to R.attr.plDanger
            app.allowed -> R.string.app_rule_allowed_badge to R.attr.plSuccess
            else -> R.string.app_rule_no_rule to R.attr.plTextSecondary
        }
        val badgeColor = MaterialColors.getColor(b.root, badgeAttr, 0)
        b.tvRuleBadge.setText(badgeText)
        b.tvRuleBadge.setTextColor(badgeColor)
        b.tvRuleBadge.compoundDrawableTintList = ColorStateList.valueOf(badgeColor)

        // Radio preselection from the stored rule.
        b.rgRuleMode.check(
            when {
                app.allowed -> R.id.rbModeAllow
                app.rule != null && app.rule.blockDuringLock -> R.id.rbModeDuring
                app.rule != null -> R.id.rbModeBlock
                else -> R.id.rbModeDuring
            }
        )
        val limit = app.rule?.dailyLimitMinutes ?: 0
        b.sbLimit.progress = limit
        b.tvLimitValue.text = getString(R.string.app_rule_limit_value, limit)
        b.tvLimitHint.setText(
            if (Graph.usage.hasAccess()) R.string.rule_limit_hint else R.string.rule_limit_needs_access
        )

        if (app.alwaysAvailable) {
            b.tvRuleNote.isVisible = true
            b.rgRuleMode.isEnabled = false
            b.rbModeBlock.isEnabled = false
            b.rbModeDuring.isEnabled = false
            b.rbModeAllow.isEnabled = false
            b.sbLimit.isEnabled = false
        }
    }

    private enum class Mode { BLOCK, DURING, ALLOW }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
