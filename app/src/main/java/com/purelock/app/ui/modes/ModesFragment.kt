package com.purelock.app.ui.modes

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.PureLockAdminReceiver
import com.purelock.app.data.prefs.AppMode
import com.purelock.app.databinding.FragmentModesBinding
import com.purelock.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Modes — Select Mode (SPEC S04). Three mode cards (Normal / Lock / Strict) with
 * Active/Activate pills and 1/2/3 red strictness padlocks. The mode lives in
 * [Graph.settings] ([AppMode]); the Dashboard mode card reads the same value.
 * The Strict card also shows whether Device Admin is active.
 */
class ModesFragment : Fragment(R.layout.fragment_modes) {

    private var _binding: FragmentModesBinding? = null
    private val b get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentModesBinding.bind(view)

        b.btnMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        b.btnBell.setOnClickListener { openNotifications() }

        b.cardNormal.setOnClickListener { switchToNormal() }
        b.pillNormal.setOnClickListener { switchToNormal() }
        b.cardLock.setOnClickListener { openLockWizard() }
        b.pillLock.setOnClickListener { openLockWizard() }
        b.cardStrict.setOnClickListener { openStrictWizard() }
        b.pillStrict.setOnClickListener { openStrictWizard() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.settings.observeMode().collect { mode -> render(mode) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Device Admin state can change while a wizard is open; refresh the Strict card.
        renderAdminStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(mode: String) {
        val activeBg = R.drawable.bg_sf_mode_selected
        val inactiveBg = R.drawable.bg_sf_card
        val activePill = R.drawable.bg_sf_mode_pill_active
        val inactivePill = R.drawable.bg_sf_mode_pill_activate

        b.cardNormal.setBackgroundResource(if (mode == AppMode.NORMAL) activeBg else inactiveBg)
        b.cardLock.setBackgroundResource(if (mode == AppMode.LOCK) activeBg else inactiveBg)
        b.cardStrict.setBackgroundResource(if (mode == AppMode.STRICT) activeBg else inactiveBg)

        b.pillNormal.setBackgroundResource(if (mode == AppMode.NORMAL) activePill else inactivePill)
        b.pillLock.setBackgroundResource(if (mode == AppMode.LOCK) activePill else inactivePill)
        b.pillStrict.setBackgroundResource(if (mode == AppMode.STRICT) activePill else inactivePill)

        b.pillNormal.setText(if (mode == AppMode.NORMAL) R.string.sf_modes_active else R.string.sf_modes_activate)
        b.pillLock.setText(if (mode == AppMode.LOCK) R.string.sf_modes_active else R.string.sf_modes_activate)
        b.pillStrict.setText(if (mode == AppMode.STRICT) R.string.sf_modes_active else R.string.sf_modes_activate)

        renderAdminStatus()
    }

    private fun renderAdminStatus() {
        val context = context ?: return
        val admin = ComponentName(context, PureLockAdminReceiver::class.java)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val active = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        b.tvStrictAdminStatus.setText(
            if (active) R.string.sf_modes_admin_active else R.string.sf_modes_admin_inactive
        )
        b.tvStrictAdminStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (active) R.color.sf_green else R.color.sf_muted
            )
        )
    }

    private fun switchToNormal() {
        // No deactivation PIN prompt is specified in S04–S06; tapping the Normal
        // card switches straight back. (A PIN gate on leaving Lock/Strict mode
        // would need a deactivation screen the spec does not define.)
        viewLifecycleOwner.lifecycleScope.launch {
            Graph.settings.setMode(AppMode.NORMAL)
        }
    }

    private fun openLockWizard() {
        startActivity(Intent(requireContext(), LockModeWizardActivity::class.java))
    }

    private fun openStrictWizard() {
        startActivity(Intent(requireContext(), StrictModeWizardActivity::class.java))
    }

    /**
     * Bell → NotificationsActivity (foundation decision 4). That activity is owned
     * by the account area and may not exist yet, so it is opened by class name and
     * a missing activity is silently ignored.
     */
    private fun openNotifications() {
        runCatching {
            val intent = Intent().setClassName(
                requireContext(),
                "com.purelock.app.ui.account.NotificationsActivity"
            )
            startActivity(intent)
        }
    }
}
