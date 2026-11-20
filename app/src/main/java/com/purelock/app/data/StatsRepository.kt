package com.purelock.app.data

import com.purelock.app.data.db.FocusStatistics
import com.purelock.app.data.db.StatsDao
import com.purelock.app.util.DayUtils

/** Writes only real, observed events into the per-day statistics table. */
class StatsRepository(private val dao: StatsDao) {

    private suspend fun ensure(key: String) = dao.ensureRow(FocusStatistics(dateKey = key))

    /** Credits protected time, splitting it correctly across midnight. */
    suspend fun addProtected(startEpoch: Long, durationMs: Long) {
        if (durationMs <= 0) return
        for ((key, ms) in DayUtils.splitByDay(startEpoch, startEpoch + durationMs)) {
            ensure(key)
            dao.addProtectedMs(key, ms)
        }
    }

    suspend fun recordWebsiteBlock(now: Long = System.currentTimeMillis()) {
        val key = DayUtils.dateKey(now)
        ensure(key)
        dao.addWebsiteBlocks(key, 1)
    }

    suspend fun recordAppBlock(now: Long = System.currentTimeMillis()) {
        val key = DayUtils.dateKey(now)
        ensure(key)
        dao.addAppBlocks(key, 1)
    }

    suspend fun recordInterruption(now: Long = System.currentTimeMillis()) {
        val key = DayUtils.dateKey(now)
        ensure(key)
        dao.addInterruptions(key, 1)
    }

    suspend fun recordEmergencyOverride(now: Long = System.currentTimeMillis()) {
        val key = DayUtils.dateKey(now)
        ensure(key)
        dao.addEmergencyOverrides(key, 1)
    }
}
