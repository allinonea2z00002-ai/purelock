package com.purelock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.purelock.app.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores protection after a reboot or app update, and reacts to wall-clock / time-zone changes. */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        Graph.blocklist.reload()
                        Graph.lockManager.recoverAfterRestart()
                    }
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> Graph.lockManager.onSystemTimeChanged()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
