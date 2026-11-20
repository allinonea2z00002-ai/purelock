package com.purelock.app

import android.app.Application
import com.purelock.app.service.WorkScheduler
import kotlinx.coroutines.launch

class PureLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.notifier.createChannels()
        WorkScheduler.schedulePeriodic(this)

        Graph.appScope.launch {
            // Load the filter lists and bring the lock state up to date after any process restart.
            Graph.blocklist.reload()
            Graph.lockManager.checkpoint()
        }
    }
}
