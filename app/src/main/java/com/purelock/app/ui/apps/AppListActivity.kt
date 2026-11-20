package com.purelock.app.ui.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.viewModels
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.databinding.ActivityAppListBinding
import com.purelock.app.databinding.DialogAppRuleBinding
import com.purelock.app.databinding.ItemAppBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

class AppListActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppListBinding
    private val vm: AppListViewModel by viewModels()
    private val adapter = AppAdapter { showRuleDialog(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter
        b.etSearch.doAfterTextChanged { vm.setQuery(it?.toString().orEmpty()) }
        b.tvUsageNote.setText(
            if (Graph.usage.hasAccess()) R.string.apps_usage_note_on else R.string.apps_usage_note_off
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.items.collect { adapter.submitList(it) } }
                launch { vm.loading.collect { b.progress.isVisible = it } }
            }
        }
    }

    private fun showRuleDialog(item: AppItem) {
        val d = DialogAppRuleBinding.inflate(LayoutInflater.from(this))
        val rule = item.rule

        if (item.alwaysAvailable) {
            d.tvRuleNote.isVisible = true
            d.tvRuleNote.setText(R.string.rule_always_available)
            d.switchBlock.isEnabled = false
            d.switchDuringLock.isEnabled = false
            d.etLimit.isEnabled = false
        }
        d.switchBlock.isChecked = rule != null
        d.switchDuringLock.isChecked = rule?.blockDuringLock ?: true
        d.etLimit.setText((rule?.dailyLimitMinutes ?: 0).toString())
        d.switchAllow.isChecked = item.allowed
        d.tvLimitHint.setText(
            if (Graph.usage.hasAccess()) R.string.rule_limit_hint else R.string.rule_limit_needs_access
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(item.label)
            .setView(d.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val limit = (d.etLimit.text?.toString()?.toIntOrNull() ?: 0).coerceIn(0, 1_440)
                lifecycleScope.launch {
                    val ok = vm.saveRule(
                        item = item,
                        block = d.switchBlock.isChecked,
                        blockDuringLock = d.switchDuringLock.isChecked,
                        limitMinutes = limit,
                        allowed = d.switchAllow.isChecked
                    )
                    if (!ok) Toast.makeText(this@AppListActivity, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class AppAdapter(private val onClick: (AppItem) -> Unit) :
        ListAdapter<AppItem, AppAdapter.Holder>(object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a == b
        }) {

        inner class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val bind = holder.binding
            bind.tvLabel.text = item.label
            bind.ivIcon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(item.packageName) }.getOrNull()
            )

            val details = ArrayList<String>()
            if (item.usageTodayMs > 0) details += getString(R.string.app_usage_today, DurationFormat.compact(item.usageTodayMs))
            item.rule?.let { r ->
                if (r.dailyLimitMinutes > 0) details += getString(R.string.app_limit, r.dailyLimitMinutes)
            }
            bind.tvDetail.text = details.joinToString(" · ")
            bind.tvDetail.isVisible = details.isNotEmpty()

            val (stateText, attr) = when {
                item.alwaysAvailable -> R.string.app_state_always to R.attr.plTextSecondary
                item.rule != null -> R.string.app_state_blocked to R.attr.plDanger
                item.allowed -> R.string.app_state_allowed to R.attr.plSuccess
                else -> 0 to R.attr.plTextSecondary
            }
            bind.tvState.text = if (stateText != 0) getString(stateText) else ""
            bind.tvState.setTextColor(MaterialColors.getColor(bind.root, attr, 0))
            bind.root.setOnClickListener { onClick(item) }
        }
    }
}
