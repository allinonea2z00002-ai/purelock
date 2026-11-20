package com.purelock.app.ui.apps

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
import com.purelock.app.R
import com.purelock.app.databinding.ActivityAllSitesBinding
import com.purelock.app.databinding.ItemSfSiteBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

/**
 * All Sites (SPEC S09): same layout as All Apps. The search field also ADDS a website:
 * typing a domain and confirming adds it to the blocklist. Rows show a domain initial
 * (no favicon store exists — see below), the domain, and "Spent: … | … visits".
 *
 * Honest limits: there is no per-domain visit or usage store in the app (browsing stats
 * are per browser app only), so every row shows "Spent: 00 sec | 0 visits" until a
 * per-domain visit tracker is added. Favicons are likewise not stored or fetched, so
 * rows show the domain's first letter in the grey circle instead of a brand favicon.
 */
class AllSitesActivity : AppCompatActivity() {

    private lateinit var b: ActivityAllSitesBinding
    private val vm: AllSitesViewModel by viewModels()
    private val adapter = SiteAdapter(
        onLockClick = { toggleLock(it) },
        onLongClick = { togglePin(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAllSitesBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }
        b.rvSites.layoutManager = LinearLayoutManager(this)
        b.rvSites.adapter = adapter
        b.etSearch.doAfterTextChanged { vm.setQuery(it?.toString().orEmpty()) }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitDomain()
                true
            } else false
        }
        b.btnSort.setOnClickListener { showSortPopup() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.items.collect {
                        adapter.submitList(it)
                        b.tvEmpty.isVisible = it.isEmpty()
                    }
                }
                launch {
                    vm.sortOrder.collect { order ->
                        b.tvSortLabel.setText(
                            when (order) {
                                SiteSort.WEBSITE -> R.string.sf_apps_sort_website
                                SiteSort.BLOCKED -> R.string.sf_apps_sort_blocked
                                SiteSort.VISITS -> R.string.sf_apps_sort_site_visit
                                SiteSort.USAGE -> R.string.sf_apps_sort_usage
                                SiteSort.PINNED -> R.string.sf_apps_sort_pinned
                            }
                        )
                    }
                }
            }
        }
    }

    private fun showSortPopup() {
        val popup = PopupMenu(this, b.btnSort)
        popup.menu.add(Menu.NONE, SiteSort.WEBSITE.ordinal, Menu.NONE, R.string.sf_apps_sort_website)
        popup.menu.add(Menu.NONE, SiteSort.BLOCKED.ordinal, Menu.NONE, R.string.sf_apps_sort_blocked)
        popup.menu.add(Menu.NONE, SiteSort.VISITS.ordinal, Menu.NONE, R.string.sf_apps_sort_site_visit)
        popup.menu.add(Menu.NONE, SiteSort.USAGE.ordinal, Menu.NONE, R.string.sf_apps_sort_usage)
        popup.menu.add(Menu.NONE, SiteSort.PINNED.ordinal, Menu.NONE, R.string.sf_apps_sort_pinned)
        popup.setOnMenuItemClickListener { menuItem ->
            vm.setSort(SiteSort.entries[menuItem.itemId])
            true
        }
        popup.show()
    }

    private fun submitDomain() {
        val text = b.etSearch.text?.toString().orEmpty()
        if (text.isBlank()) return
        vm.addDomain(text) { result ->
            when (result) {
                AddDomainResult.ADDED -> {
                    b.etSearch.text?.clear()
                    Toast.makeText(this, R.string.sf_apps_site_added, Toast.LENGTH_SHORT).show()
                }
                AddDomainResult.INVALID -> Toast.makeText(this, R.string.domain_invalid, Toast.LENGTH_LONG).show()
                AddDomainResult.EXISTS -> Toast.makeText(this, R.string.sf_apps_site_exists, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleLock(item: SiteItem) {
        vm.toggleBlock(item) { ok ->
            if (!ok) {
                Toast.makeText(this, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    if (item.blocked) R.string.sf_apps_site_unblocked else R.string.sf_apps_site_blocked,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun togglePin(item: SiteItem) {
        val pinned = vm.togglePin(item.domain)
        Toast.makeText(
            this,
            if (pinned) R.string.sf_apps_pinned else R.string.sf_apps_unpinned,
            Toast.LENGTH_SHORT
        ).show()
    }

    private inner class SiteAdapter(
        private val onLockClick: (SiteItem) -> Unit,
        private val onLongClick: (SiteItem) -> Unit
    ) : ListAdapter<SiteItem, SiteAdapter.Holder>(object : DiffUtil.ItemCallback<SiteItem>() {
        override fun areItemsTheSame(a: SiteItem, b: SiteItem) = a.domain == b.domain
        override fun areContentsTheSame(a: SiteItem, b: SiteItem) = a == b
    }) {

        inner class Holder(val binding: ItemSfSiteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSfSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val bind = holder.binding
            bind.tvFaviconLetter.text = item.domain.firstOrNull()?.uppercase().orEmpty()
            bind.tvDomain.text = item.domain
            bind.tvDetail.text = getString(
                R.string.sf_apps_site_spent,
                DurationFormat.formatMinsSecs(item.usageMs),
                if (item.visits == 1) getString(R.string.sf_apps_visits_one)
                else getString(R.string.sf_apps_visits_many, item.visits)
            )
            bind.btnLock.setImageResource(
                if (item.blocked) R.drawable.ic_sf_lock_closed else R.drawable.ic_sf_lock_open
            )
            bind.btnLock.imageTintList = ColorStateList.valueOf(
                getColor(if (item.blocked) R.color.sf_red else R.color.sf_text)
            )
            bind.btnLock.contentDescription = getString(
                if (item.blocked) R.string.sf_apps_cd_lock_blocked else R.string.sf_apps_cd_lock_unblocked
            )
            bind.viewDivider.isVisible = position < itemCount - 1
            bind.root.setOnLongClickListener { onLongClick(item); true }
            bind.btnLock.setOnClickListener { onLockClick(item) }
        }
    }
}
