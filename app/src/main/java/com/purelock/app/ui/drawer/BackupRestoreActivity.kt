package com.purelock.app.ui.drawer

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.BackupManager
import com.purelock.app.databinding.ActivityBackupRestoreBinding
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & Restore (FOUNDATION §1 decision 14): reuses [BackupManager]'s JSON
 * export/import. Export saves `purelock-backup.json` wherever the user chooses;
 * Import picks a file and restores it. Import is refused while a lock is active
 * (a backup must not be usable to undo a lock).
 */
class BackupRestoreActivity : AppCompatActivity() {

    private lateinit var b: ActivityBackupRestoreBinding

    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) exportTo(uri)
        }
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityBackupRestoreBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerBackup.tvSfTitle.setText(R.string.sf_set_backup_title)
        b.headerBackup.btnSfBack.setOnClickListener { finish() }
        b.btnBackupExport.setOnClickListener { exportFile.launch("purelock-backup.json") }
        b.btnBackupImport.setOnClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun exportTo(uri: Uri) = lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val json = BackupManager.export()
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open file")
            }.isSuccess
        }
        Toast.makeText(
            this@BackupRestoreActivity,
            if (ok) R.string.msg_export_ok else R.string.msg_export_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun importFrom(uri: Uri) = lifecycleScope.launch {
        if (Graph.lockManager.isActive()) {
            Toast.makeText(
                this@BackupRestoreActivity,
                R.string.msg_locked_cannot_change,
                Toast.LENGTH_LONG
            ).show()
            return@launch
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
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
        Toast.makeText(
            this@BackupRestoreActivity,
            if (ok) R.string.msg_import_ok else R.string.msg_import_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}
