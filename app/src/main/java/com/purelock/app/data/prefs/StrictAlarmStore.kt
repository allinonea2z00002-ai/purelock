package com.purelock.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.strictAlarmDataStore by preferencesDataStore(name = "strict_alarms")

/** One strict alarm: fires daily at [hour]:[minute] until turned off or deleted. */
data class StrictAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean
)

/**
 * Strict alarms, kept as a JSON array in DataStore (no Room change — decided).
 * The JSON is a list of objects: {"id":…,"hour":…,"minute":…,"label":"…","enabled":…}.
 * Scheduling itself lives in [com.purelock.app.ui.alarm.StrictAlarmActivity],
 * which re-arms each enabled alarm with AlarmManager.setAlarmClock().
 */
class StrictAlarmStore(private val context: Context) {

    val flow: Flow<List<StrictAlarm>> =
        context.strictAlarmDataStore.data.map { prefs ->
            parse(prefs[KEY_ALARMS] ?: "[]")
        }

    suspend fun upsert(alarm: StrictAlarm) {
        val current = parse(readRaw()).filter { it.id != alarm.id } + alarm
        save(current.sortedBy { it.hour * 60 + it.minute })
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = parse(readRaw())
        save(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun delete(id: Long) {
        save(parse(readRaw()).filter { it.id != id })
    }

    private suspend fun readRaw(): String =
        context.strictAlarmDataStore.data.map { it[KEY_ALARMS] ?: "[]" }.first()

    private suspend fun save(alarms: List<StrictAlarm>) {
        val json = JSONArray().apply {
            alarms.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("hour", it.hour)
                        .put("minute", it.minute)
                        .put("label", it.label)
                        .put("enabled", it.enabled)
                )
            }
        }.toString()
        context.strictAlarmDataStore.edit { it[KEY_ALARMS] = json }
    }

    private fun parse(json: String): List<StrictAlarm> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            StrictAlarm(
                id = o.optLong("id", 0),
                hour = o.optInt("hour", 0).coerceIn(0, 23),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                label = o.optString("label", "").take(60),
                enabled = o.optBoolean("enabled", true)
            )
        }.filter { it.id != 0L }
    }.getOrDefault(emptyList())

    private companion object {
        val KEY_ALARMS = stringPreferencesKey("alarms_json")
    }
}
