package com.purelock.app.ui.profiles

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.ProfileRepository
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.databinding.ActivityPickAppsBinding
import com.purelock.app.databinding.ItemSfPickAppBinding
import com.purelock.app.databinding.ItemSfPickSiteBinding
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick Apps & Sites (profile create flow, last step — not in the screenshots,
 * FOUNDATION decision 8): two-tab picker (Apps | Sites) with checkable lists.
 * Apps come from the launcher list; sites from a domain add field plus the
 * domains already in the global site blocklist. Save stores the profile via
 * [ProfileRepository] (and the Combined flag) and returns to the Dashboard.
 *
 * NOTE: this activity still needs a manifest entry (`.ui.profiles.PickAppsSitesActivity`,
 * parent `.ui.MainActivity`) — the manifest is coordinator-owned; see the Area C report.
 */
class PickAppsSitesActivity : AppCompatActivity() {

    private lateinit var b: ActivityPickAppsBinding
    private val repository by lazy { ProfileRepository(this) }

    private var draft: FocusProfile = ProfileFlow.freshDraft()
    private var combined = false

    private var appsTab = true
    private var allApps: List<AppRow> = emptyList()
    private var shownApps: List<AppRow> = emptyList()
    private val selectedApps = HashSet<String>()
    private var siteRows: List<SiteRow> = emptyList()
    private val selectedSites = HashSet<String>()

    private data class AppRow(val packageName: String, val label: String)
    private data class SiteRow(val domain: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPickAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }

        val (incomingDraft, incomingCombined) = ProfileFlow.draftFrom(intent)
        draft = incomingDraft
        combined = incomingCombined
        selectedApps.addAll(draft.appPackageList())
        selectedSites.addAll(draft.siteDomainList())
        b.tvPickSubtitle.setText(
            if (draft.type == "WHITELIST") R.string.sf_profile_pick_subtitle_whitelist
            else R.string.sf_profile_pick_subtitle_blocklist
        )

        b.listPick.layoutManager = LinearLayoutManager(this)
        b.tvTabApps.setOnClickListener { showTab(true) }
        b.tvTabSites.setOnClickListener { showTab(false) }
        b.etSearch.doAfterTextChanged { filterApps(it?.toString().orEmpty()) }
        b.btnAddSite.setOnClickListener { addSite() }
        b.btnSave.setOnClickListener { save() }

        showTab(true)
        loadApps()
        loadSites()
    }

    // ------------------------------------------------------------------ tabs

    private fun showTab(apps: Boolean) {
        appsTab = apps
        b.tvTabApps.setBackgroundResource(if (apps) R.drawable.bg_sf_segment_selected else 0)
        b.tvTabSites.setBackgroundResource(if (!apps) R.drawable.bg_sf_segment_selected else 0)
        b.tvTabApps.setTextColor(
            ContextCompat.getColor(this, if (apps) R.color.sf_primary else R.color.sf_text)
        )
        b.tvTabSites.setTextColor(
            ContextCompat.getColor(this, if (!apps) R.color.sf_primary else R.color.sf_text)
        )
        b.tvTabApps.setTypeface(null, if (apps) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.tvTabSites.setTypeface(null, if (!apps) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.etSearch.isVisible = apps
        b.rowAddSite.isVisible = !apps
        refreshList()
    }

    // ------------------------------------------------------------------ apps

    private fun loadApps() {
        lifecycleScope.launch {
            val pm = packageManager
            allApps = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
                    .map { AppRow(it.first, it.second) }
            }
            filterApps(b.etSearch.text?.toString().orEmpty())
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        shownApps = if (q.isEmpty()) allApps
        else allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        if (appsTab) refreshList()
    }

    // ------------------------------------------------------------------ sites

    private fun loadSites() {
        lifecycleScope.launch {
            val known = withContext(Dispatchers.IO) { Graph.db.blockedDomainDao().getAll().map { it.domain } }
            val domains = (draft.siteDomainList() + known).distinct().sorted()
            siteRows = domains.map { SiteRow(it) }
            if (!appsTab) refreshList()
        }
    }

    private fun addSite() {
        val raw = b.etSiteDomain.text?.toString().orEmpty()
        val domain = normalizeDomain(raw)
        if (domain == null) {
            Toast.makeText(this, R.string.sf_profile_invalid_domain, Toast.LENGTH_LONG).show()
            return
        }
        if (siteRows.none { it.domain == domain }) {
            siteRows = (siteRows + SiteRow(domain)).sortedBy { it.domain }
        }
        selectedSites.add(domain)
        b.etSiteDomain.text?.clear()
        refreshList()
    }

    /** Strips scheme / www / path; null when the result is not a plausible domain. */
    private fun normalizeDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        d = d.substringBefore('/').substringBefore('?').trim()
        if (d.isEmpty() || d.contains(' ') || !d.contains('.')) return null
        if (!d.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        if (d.startsWith('.') || d.startsWith('-') || d.endsWith('.') || d.endsWith('-')) return null
        return d
    }

    // ------------------------------------------------------------------ list

    private fun refreshList() {
        if (appsTab) {
            b.listPick.adapter = AppAdapter(shownApps) { row ->
                if (selectedApps.contains(row.packageName)) selectedApps.remove(row.packageName)
                else selectedApps.add(row.packageName)
                b.listPick.adapter?.notifyDataSetChanged()
            }
            b.tvEmpty.isVisible = shownApps.isEmpty()
            b.listPick.isVisible = shownApps.isNotEmpty()
        } else {
            b.listPick.adapter = SiteAdapter(siteRows) { row ->
                if (selectedSites.contains(row.domain)) selectedSites.remove(row.domain)
                else selectedSites.add(row.domain)
                b.listPick.adapter?.notifyDataSetChanged()
            }
            b.tvEmpty.isVisible = siteRows.isEmpty()
            b.tvEmpty.setText(R.string.sf_profile_no_sites)
            b.listPick.isVisible = siteRows.isNotEmpty()
        }
    }

    private inner class AppAdapter(
        private val rows: List<AppRow>,
        private val onToggle: (AppRow) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {
        inner class Holder(val binding: ItemSfPickAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSfPickAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val bind = holder.binding
            bind.tvPickLabel.text = row.label
            bind.tvPickPackage.text = row.packageName
            bind.ivPickIcon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(row.packageName) }.getOrNull()
            )
            bind.cbPick.isChecked = selectedApps.contains(row.packageName)
            bind.root.setOnClickListener { onToggle(row) }
        }
    }

    private inner class SiteAdapter(
        private val rows: List<SiteRow>,
        private val onToggle: (SiteRow) -> Unit
    ) : RecyclerView.Adapter<SiteAdapter.Holder>() {
        inner class Holder(val binding: ItemSfPickSiteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSfPickSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val bind = holder.binding
            bind.tvPickDomain.text = row.domain
            bind.tvPickLetter.text = row.domain.firstOrNull()?.uppercase().orEmpty()
            bind.cbPick.isChecked = selectedSites.contains(row.domain)
            bind.root.setOnClickListener { onToggle(row) }
        }
    }

    // ------------------------------------------------------------------ save

    private fun save() {
        val toSave = draft.copy(
            appPackages = selectedApps.sorted().joinToString(","),
            siteDomains = selectedSites.sorted().joinToString(",")
        )
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { repository.upsert(toSave) }
            repository.setCombined(id, combined)
            Toast.makeText(this@PickAppsSitesActivity, R.string.sf_profile_saved, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this@PickAppsSitesActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }
}
