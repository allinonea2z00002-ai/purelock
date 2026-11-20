package com.purelock.app.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SecurityEvent
import com.purelock.app.databinding.ActivityNotificationsBinding
import com.purelock.app.databinding.ItemSfNotificationBinding
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Notifications (bell target, FOUNDATION §1 decision 4): recent
 * `security_event` rows, newest first. Empty state: "No notifications yet".
 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var b: ActivityNotificationsBinding
    private val adapter = EventAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerNotif.tvSfTitle.setText(R.string.sf_set_notif_title)
        b.headerNotif.btnSfBack.setOnClickListener { finish() }
        b.rvNotifications.layoutManager = LinearLayoutManager(this)
        b.rvNotifications.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.db.securityEventDao().observeRecent(100).collect { events ->
                    adapter.submit(events)
                    b.tvNotifEmpty.isVisible = events.isEmpty()
                    b.rvNotifications.isVisible = events.isNotEmpty()
                }
            }
        }
    }

    private class EventAdapter : RecyclerView.Adapter<EventAdapter.Holder>() {

        private var items: List<SecurityEvent> = emptyList()

        fun submit(list: List<SecurityEvent>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemSfNotificationBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val b: ItemSfNotificationBinding) :
            RecyclerView.ViewHolder(b.root) {
            fun bind(event: SecurityEvent) {
                b.tvNotifTitle.setText(eventName(event.type))
                if (event.detail.isBlank()) {
                    b.tvNotifDetail.isVisible = false
                } else {
                    b.tvNotifDetail.text = event.detail
                    b.tvNotifDetail.isVisible = true
                }
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                b.tvNotifTime.text = format.format(Date(event.epoch))
            }

            private fun eventName(type: String): Int = when (type) {
                "LOCK_STARTED" -> R.string.ev_lock_started
                "LOCK_COMPLETED" -> R.string.ev_lock_completed
                "EMERGENCY_OVERRIDE" -> R.string.ev_emergency
                "TIME_CHANGED" -> R.string.ev_time_changed
                "REBOOT_RECOVERY" -> R.string.ev_reboot
                "INTEGRITY_ISSUE" -> R.string.ev_integrity_issue
                "INTEGRITY_RESTORED" -> R.string.ev_integrity_restored
                "GEOFENCE_ERROR" -> R.string.ev_geofence_error
                "ZONE_LOCK" -> R.string.ev_zone_lock
                else -> R.string.ev_other
            }
        }
    }
}
