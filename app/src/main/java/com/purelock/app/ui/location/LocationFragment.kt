package com.purelock.app.ui.location

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.R
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.db.SafeZone
import com.purelock.app.databinding.DialogZoneBinding
import com.purelock.app.databinding.FragmentLocationBinding
import com.purelock.app.domain.LockMode
import com.purelock.app.location.Fix
import com.purelock.app.location.LocationHelper
import com.purelock.app.ui.UiAnim
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class LocationFragment : Fragment(R.layout.fragment_location) {

    private var _binding: FragmentLocationBinding? = null
    private val b get() = _binding!!
    private val vm: LocationViewModel by viewModels()

    private var lastFix: Fix? = null
    private var ui = LocationUi(null, emptyList(), 0)

    private val foregroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                // Background location has to be requested on its own, after foreground access is granted.
                if (!LocationHelper.hasBackgroundPermission(requireContext())) askBackground()
            } else {
                Toast.makeText(requireContext(), R.string.err_location_denied, Toast.LENGTH_LONG).show()
            }
            render()
        }

    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLocationBinding.bind(view)
        UiAnim.cardEntrance(listOf(b.cardCurrent, b.cardDistance, b.cardZones, b.cardPrivacy))

        b.btnPermission.setOnClickListener {
            val ctx = requireContext()
            if (!LocationHelper.hasForegroundPermission(ctx)) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.perm_location_title)
                    .setMessage(R.string.perm_location_message)
                    .setPositiveButton(R.string.action_continue) { _, _ ->
                        foregroundPermission.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else askBackground()
        }
        b.btnGps.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        b.btnAddZone.setOnClickListener { showAddZoneDialog() }
        b.btnViewMap.setOnClickListener { startActivity(Intent(requireContext(), MapActivity::class.java)) }
        b.btnDeleteHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.loc_delete_history)
                .setMessage(R.string.loc_delete_confirm)
                .setPositiveButton(R.string.action_delete) { _, _ -> vm.deleteHistory() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ui.collect { ui = it; render() } }
                // Live updates only while this screen is visible (cheap, and stops on leaving).
                launch {
                    LocationHelper.updates(requireContext(), 10_000).collect { fix ->
                        lastFix = fix
                        vm.feed(fix)
                        render()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) render()
    }

    private fun askBackground() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.perm_background_title)
            .setMessage(R.string.perm_background_message)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------

    private fun render() {
        if (_binding == null) return
        val ctx = requireContext()
        val hasFine = LocationHelper.hasForegroundPermission(ctx)
        val hasBg = LocationHelper.hasBackgroundPermission(ctx)
        val gps = LocationHelper.isLocationEnabled(ctx)

        // ----- permission status (real values) --------------------------------------------------
        b.tvPermission.text = getString(
            when {
                !hasFine -> R.string.loc_perm_none
                !hasBg -> R.string.loc_perm_foreground
                else -> R.string.loc_perm_all
            }
        )
        b.btnPermission.isVisible = !hasFine || !hasBg
        b.btnPermission.setText(if (!hasFine) R.string.loc_grant_button else R.string.loc_grant_background)
        b.btnGps.isVisible = hasFine && !gps

        // ----- current location -------------------------------------------------------------------
        val fix = lastFix
        when {
            !hasFine -> {
                b.tvCurrent.setText(R.string.loc_unavailable_permission)
                b.tvAccuracy.text = ""
            }
            !gps -> {
                b.tvCurrent.setText(R.string.err_gps_disabled)
                b.tvAccuracy.text = ""
            }
            fix == null -> {
                b.tvCurrent.setText(R.string.loc_waiting_fix)
                b.tvAccuracy.text = ""
            }
            else -> {
                b.tvCurrent.text = "%.5f, %.5f".format(fix.latitude, fix.longitude)
                b.tvAccuracy.text = accuracyText(fix.accuracyM)
            }
        }

        renderDistance(ui.lock, hasFine, hasBg, gps)
        renderZones()
        b.tvHistory.text = resources.getQuantityString(R.plurals.history_count, ui.historyCount, ui.historyCount)
        b.btnDeleteHistory.isEnabled = ui.historyCount > 0
    }

    private fun accuracyText(accuracyM: Float): String {
        val level = when {
            accuracyM <= 20f -> R.string.acc_high
            accuracyM <= 100f -> R.string.acc_medium
            else -> R.string.acc_low
        }
        return getString(R.string.loc_accuracy, accuracyM.toInt(), getString(level))
    }

    private fun renderDistance(cfg: LockConfiguration?, hasFine: Boolean, hasBg: Boolean, gps: Boolean) {
        val usesDistance = cfg?.active == true && LockMode.fromName(cfg.mode).usesDistance
        if (!usesDistance || cfg == null) {
            b.tvDistanceInfo.setText(R.string.loc_no_distance_lock)
            b.progressDistance.isVisible = false
            b.tvDistanceNote.text = ""
            return
        }
        val travelled = cfg.lastDistanceM
        val limit = cfg.distanceLimitM.toFloat()
        val started = if (cfg.startLat != null && cfg.startLng != null)
            "%.5f, %.5f".format(cfg.startLat, cfg.startLng) else "-"
        val last = if (cfg.lastLocationEpoch > 0)
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cfg.lastLocationEpoch))
        else getString(R.string.loc_no_fix_yet)

        b.tvDistanceInfo.text = getString(
            R.string.loc_distance_info,
            started,
            DurationFormat.distance(travelled),
            DurationFormat.distance(limit),
            last
        )
        b.progressDistance.isVisible = true
        b.progressDistance.setProgressCompat(((travelled / limit).coerceIn(0f, 1f) * 1000).toInt(), true)

        // Honest status: say plainly if tracking cannot be running right now.
        val notes = ArrayList<String>()
        if (!hasFine) notes += getString(R.string.loc_note_no_permission)
        else {
            if (!hasBg) notes += getString(R.string.loc_note_no_background)
            if (!gps) notes += getString(R.string.loc_note_gps_off)
        }
        if (notes.isEmpty()) notes += getString(R.string.loc_note_ok)
        b.tvDistanceNote.text = notes.joinToString("\n")
    }

    private fun renderZones() {
        b.tvZonesEmpty.isVisible = ui.zones.isEmpty()
        b.listZones.removeAllViews()
        for (zone in ui.zones) {
            val row = layoutInflater.inflate(R.layout.item_zone, b.listZones, false)
            row.findViewById<TextView>(R.id.tvZoneName).text = zone.name
            row.findViewById<TextView>(R.id.tvZoneDetail).text = zoneDetail(zone)
            row.findViewById<View>(R.id.btnZoneDelete).setOnClickListener { confirmDelete(zone) }
            b.listZones.addView(row)
        }
    }

    private fun zoneDetail(zone: SafeZone): String {
        val base = getString(R.string.zone_detail_radius, zone.radiusM)
        return if (zone.autoLockMinutes > 0) {
            base + " · " + getString(R.string.zone_detail_auto, zone.autoLockMinutes)
        } else base
    }

    private fun confirmDelete(zone: SafeZone) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(zone.name)
            .setMessage(R.string.zone_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ -> vm.deleteZone(zone.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showAddZoneDialog() {
        val ctx = requireContext()
        if (!LocationHelper.hasForegroundPermission(ctx)) {
            Toast.makeText(ctx, R.string.err_location_denied, Toast.LENGTH_LONG).show()
            return
        }
        val fix = lastFix
        if (fix == null) {
            Toast.makeText(ctx, if (!LocationHelper.isLocationEnabled(ctx)) R.string.err_gps_disabled else R.string.err_no_fix, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = DialogZoneBinding.inflate(LayoutInflater.from(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.zones_add_button)
            .setView(dialog.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = dialog.etZoneName.text?.toString().orEmpty().trim()
                val radius = dialog.etZoneRadius.text?.toString()?.toIntOrNull() ?: 200
                val auto = dialog.etZoneLock.text?.toString()?.toIntOrNull() ?: 0
                if (name.isEmpty() || radius !in 100..5_000 || auto !in 0..(7 * 24 * 60)) {
                    Toast.makeText(ctx, R.string.zone_invalid, Toast.LENGTH_LONG).show()
                } else {
                    vm.addZone(name, fix, radius, auto) {
                        if (auto > 0 && !LocationHelper.hasBackgroundPermission(ctx)) {
                            Toast.makeText(ctx, R.string.zone_needs_background, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
