package com.purelock.app.data

import com.purelock.app.data.db.SecurityEvent
import com.purelock.app.data.db.SecurityEventDao

/** Append-only audit trail of security-relevant events (shown honestly to the user). */
class EventLog(private val dao: SecurityEventDao) {

    suspend fun log(type: String, detail: String = "", now: Long = System.currentTimeMillis()) {
        dao.insert(SecurityEvent(epoch = now, type = type, detail = detail))
        // Keep ~90 days only.
        dao.deleteOlderThan(now - 90L * 86_400_000L)
    }
}
