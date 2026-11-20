package com.purelock.app.ui.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.R
import com.purelock.app.data.prefs.StrictAlarm
import com.purelock.app.data.prefs.StrictAlarmStore
import com.purelock.app.databinding.ActivityStrictAlarmBinding
import com.purelock.app.databinding.ItemSfAlarmBinding
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Strict Alarm (FOUNDATION §1 decision 2): a list of alarms (time, label,
 * on/off) backed by [StrictAlarmStore] (DataStore JSON, no Room change).
 * Add via a time picker (+ optional label). Firing uses
 * AlarmManager.setAlarmClock() with a getActivity PendingIntent, which may
 * start the activity from the background — no receiver and no manifest change.
 * The ringing screen has Stop only: no snooze, that is what makes it strict.
 * A fired alarm re-arms itself for the next day while it stays enabled.
 */
class StrictAlarmActivity : AppCompatActivity() {

    private lateinit var b: ActivityStrictAlarmBinding
    private val store by lazy { StrictAlarmStore(applicationContext) }
    private val adapter = AlarmAdapter(
        onToggle = { alarm, enabled -> onToggle(alarm, enabled) },
        onEdit = { alarm -> editAlarm(alarm) },
        onDelete = { alarm -> deleteAlarm(alarm) }
    )
    private var alarms: List<StrictAlarm> = emptyList()
    private var ringing = false
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityStrictAlarmBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerAlarm.tvSfTitle.setText(R.string.sf_set_alarm_title)
        b.headerAlarm.btnSfBack.setOnClickListener { finish() }
        b.rvAlarms.layoutManager = LinearLayoutManager(this)
        b.rvAlarms.adapter = adapter
        b.btnAddAlarm.setOnClickListener { addAlarm() }
        b.btnStopAlarm.setOnClickListener { stopRinging() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.flow.collect { list ->
                    alarms = list
                    adapter.submit(list)
                    b.tvAlarmEmpty.isVisible = list.isEmpty() && !ringing
                }
            }
        }

        if (intent.getBooleanExtra(EXTRA_RING, false)) {
            ringing = true
            lifecycleScope.launch {
                val alarm = store.flow.first().find {
                    it.id == intent.getLongExtra(EXTRA_ALARM_ID, 0)
                }
                showRinging(alarm)
            }
        }
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun addAlarm() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute -> labelDialog(hour, minute, null) },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false
        ).show()
    }

    private fun editAlarm(alarm: StrictAlarm) {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                lifecycleScope.launch {
                    val updated = alarm.copy(hour = hour, minute = minute)
                    store.upsert(updated)
                    if (updated.enabled) schedule(updated)
                }
            },
            alarm.hour, alarm.minute, false
        ).show()
    }

    private fun labelDialog(hour: Int, minute: Int, existing: StrictAlarm?) {
        val input = EditText(this).apply {
            setText(existing?.label.orEmpty())
            hint = getString(R.string.sf_set_alarm_label_title)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_alarm_label_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val label = input.text?.toString().orEmpty().trim()
                    .ifEmpty { getString(R.string.sf_set_alarm_label_default) }
                val alarm = existing?.copy(hour = hour, minute = minute, label = label)
                    ?: StrictAlarm(
                        id = System.currentTimeMillis(),
                        hour = hour, minute = minute, label = label, enabled = true
                    )
                lifecycleScope.launch {
                    store.upsert(alarm)
                    if (alarm.enabled) schedule(alarm)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onToggle(alarm: StrictAlarm, enabled: Boolean) {
        lifecycleScope.launch {
            store.setEnabled(alarm.id, enabled)
            if (enabled) schedule(alarm) else cancel(alarm)
        }
    }

    private fun deleteAlarm(alarm: StrictAlarm) {
        lifecycleScope.launch {
            cancel(alarm)
            store.delete(alarm.id)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun pendingIntentFor(alarm: StrictAlarm): PendingIntent {
        val intent = Intent(this, StrictAlarmActivity::class.java)
            .putExtra(EXTRA_RING, true)
            .putExtra(EXTRA_ALARM_ID, alarm.id)
        return PendingIntent.getActivity(
            this,
            (alarm.id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(alarm: StrictAlarm) {
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = nextTriggerMillis(alarm.hour, alarm.minute)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pendingIntentFor(alarm)), pendingIntentFor(alarm))
    }

    private fun cancel(alarm: StrictAlarm) {
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntentFor(alarm))
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ---------------------------------------------------------------------------------------------

    private fun showRinging(alarm: StrictAlarm?) {
        b.listAlarm.isVisible = false
        b.ringingAlarm.isVisible = true
        b.tvAlarmEmpty.isVisible = false
        val time = if (alarm != null) formatTime(alarm.hour, alarm.minute) else ""
        b.tvRingTime.text = time
        b.tvRingLabel.text = alarm?.label.orEmpty()
        startSound()
        // Re-arm for tomorrow while the alarm stays enabled.
        if (alarm != null && alarm.enabled) schedule(alarm)
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply { play() }
        } catch (_: Exception) {
            // Silent alarm is better than a crash.
        }
        try {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
            // No vibration, the sound still rings.
        }
    }

    private fun stopRinging() {
        stopSound()
        Toast.makeText(this, R.string.sf_set_alarm_stop, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun stopSound() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    // ---------------------------------------------------------------------------------------------

    private class AlarmAdapter(
        private val onToggle: (StrictAlarm, Boolean) -> Unit,
        private val onEdit: (StrictAlarm) -> Unit,
        private val onDelete: (StrictAlarm) -> Unit
    ) : RecyclerView.Adapter<AlarmAdapter.Holder>() {

        private var items: List<StrictAlarm> = emptyList()

        fun submit(list: List<StrictAlarm>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemSfAlarmBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val b: ItemSfAlarmBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(alarm: StrictAlarm) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarm.hour)
                    set(Calendar.MINUTE, alarm.minute)
                }
                b.tvAlarmTime.text =
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
                b.tvAlarmLabel.text = alarm.label
                b.switchAlarm.setOnCheckedChangeListener(null)
                b.switchAlarm.isChecked = alarm.enabled
                b.switchAlarm.setOnCheckedChangeListener { _, checked -> onToggle(alarm, checked) }
                b.root.setOnClickListener { onEdit(alarm) }
                b.btnDeleteAlarm.setOnClickListener { onDelete(alarm) }
            }
        }
    }

    companion object {
        const val EXTRA_RING = "ring"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
