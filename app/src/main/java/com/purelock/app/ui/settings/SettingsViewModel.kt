package com.purelock.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.BackupManager
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.data.prefs.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> =
        Graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val historyCount: StateFlow<Int> =
        Graph.db.locationDao().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** One-shot messages for the UI (string resource ids). */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private fun say(@StringRes res: Int) { _messages.tryEmit(res) }

    fun setUninstallProtection(enabled: Boolean) = viewModelScope.launch {
        if (!enabled && Graph.lockManager.isActive()) { say(R.string.msg_locked_cannot_change); return@launch }
        Graph.settings.set(SettingsKeys.UNINSTALL_PROTECTION, enabled)
    }

    fun setKeepFilterAfterLock(enabled: Boolean) = viewModelScope.launch {
        Graph.settings.set(SettingsKeys.KEEP_FILTER_AFTER_LOCK, enabled)
    }

    fun setInterruptionAlerts(enabled: Boolean) = viewModelScope.launch {
        Graph.settings.set(SettingsKeys.INTERRUPTION_ALERTS, enabled)
    }

    fun setStoreLocationHistory(enabled: Boolean) = viewModelScope.launch {
        Graph.settings.set(SettingsKeys.STORE_LOCATION_HISTORY, enabled)
    }

    fun deleteLocationHistory() = viewModelScope.launch {
        Graph.db.locationDao().deleteAll()
        say(R.string.msg_history_deleted)
    }

    fun saveTrustedContact(name: String, phone: String) {
        Graph.secure.trustedName = name
        Graph.secure.trustedPhone = phone.filter { it.isDigit() || it == '+' }
        say(R.string.msg_contact_saved)
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val json = BackupManager.export()
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open file")
            }.isSuccess
        }
        say(if (ok) R.string.msg_export_ok else R.string.msg_export_failed)
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        if (Graph.lockManager.isActive()) { say(R.string.msg_locked_cannot_change); return@launch }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_BACKUP_BYTES) { "Backup is too large" }
                        out.write(buffer, 0, n)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                } ?: error("Cannot open file")
                BackupManager.import(text).getOrThrow()
            }.isSuccess
        }
        say(if (ok) R.string.msg_import_ok else R.string.msg_import_failed)
    }

    /** Wipes everything stored on this device. Refused while a lock is active. */
    fun clearAllData(onDone: () -> Unit) = viewModelScope.launch {
        if (Graph.lockManager.isActive()) { say(R.string.msg_locked_cannot_change); return@launch }
        withContext(Dispatchers.IO) {
            Graph.db.clearAllTables()
            Graph.settings.clear()
            Graph.secure.clear()
            Graph.blocklist.deleteDownloadedList()
            Graph.blocklist.reload()
            Graph.geofences.sync(null, emptyList())
        }
        say(R.string.msg_data_cleared)
        onDone()
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}
