package com.purelock.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LockDao {
    @Query("SELECT * FROM lock_configuration WHERE id = 1")
    suspend fun get(): LockConfiguration?

    @Query("SELECT * FROM lock_configuration WHERE id = 1")
    fun observe(): Flow<LockConfiguration?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: LockConfiguration)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ProtectionSession): Long

    @Update
    suspend fun update(session: ProtectionSession)

    @Query("SELECT * FROM protection_session WHERE id = :id")
    suspend fun get(id: Long): ProtectionSession?

    @Query("SELECT * FROM protection_session ORDER BY startEpoch DESC")
    fun observeAll(): Flow<List<ProtectionSession>>

    @Query("SELECT COUNT(*) FROM protection_session WHERE endReason = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>
}

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_application ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<BlockedApplication>>

    @Query("SELECT * FROM blocked_application")
    suspend fun getAll(): List<BlockedApplication>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: BlockedApplication)

    @Query("DELETE FROM blocked_application WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface AllowedAppDao {
    @Query("SELECT * FROM allowed_application ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<AllowedApplication>>

    @Query("SELECT * FROM allowed_application")
    suspend fun getAll(): List<AllowedApplication>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: AllowedApplication)

    @Query("DELETE FROM allowed_application WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface BlockedDomainDao {
    @Query("SELECT * FROM blocked_domain ORDER BY domain")
    fun observeAll(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domain")
    suspend fun getAll(): List<BlockedDomain>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(domain: BlockedDomain)

    @Query("DELETE FROM blocked_domain WHERE domain = :domain")
    suspend fun delete(domain: String)
}

@Dao
interface AllowedDomainDao {
    @Query("SELECT * FROM allowed_domain ORDER BY domain")
    fun observeAll(): Flow<List<AllowedDomain>>

    @Query("SELECT * FROM allowed_domain")
    suspend fun getAll(): List<AllowedDomain>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(domain: AllowedDomain)

    @Query("DELETE FROM allowed_domain WHERE domain = :domain")
    suspend fun delete(domain: String)
}

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(record: LocationRecord)

    @Query("SELECT * FROM location_record ORDER BY epoch DESC LIMIT 1")
    fun observeLatest(): Flow<LocationRecord?>

    @Query("SELECT * FROM location_record ORDER BY epoch DESC LIMIT 1")
    suspend fun latest(): LocationRecord?

    @Query("SELECT COUNT(*) FROM location_record")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM location_record")
    suspend fun deleteAll()

    /** Keeps the table small: location history is a convenience, not an archive. */
    @Query("DELETE FROM location_record WHERE epoch < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM focus_statistics ORDER BY dateKey")
    fun observeAll(): Flow<List<FocusStatistics>>

    @Query("SELECT * FROM focus_statistics")
    suspend fun getAll(): List<FocusStatistics>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureRow(row: FocusStatistics)

    @Query("UPDATE focus_statistics SET protectedMs = protectedMs + :ms WHERE dateKey = :dateKey")
    suspend fun addProtectedMs(dateKey: String, ms: Long)

    @Query("UPDATE focus_statistics SET websiteBlocks = websiteBlocks + :n WHERE dateKey = :dateKey")
    suspend fun addWebsiteBlocks(dateKey: String, n: Int)

    @Query("UPDATE focus_statistics SET appBlocks = appBlocks + :n WHERE dateKey = :dateKey")
    suspend fun addAppBlocks(dateKey: String, n: Int)

    @Query("UPDATE focus_statistics SET interruptions = interruptions + :n WHERE dateKey = :dateKey")
    suspend fun addInterruptions(dateKey: String, n: Int)

    @Query("UPDATE focus_statistics SET emergencyOverrides = emergencyOverrides + :n WHERE dateKey = :dateKey")
    suspend fun addEmergencyOverrides(dateKey: String, n: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FocusStatistics)

    @Query("DELETE FROM focus_statistics")
    suspend fun deleteAll()
}

@Dao
interface SecurityEventDao {
    @Insert
    suspend fun insert(event: SecurityEvent)

    @Query("SELECT * FROM security_event ORDER BY epoch DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SecurityEvent>>

    @Query("DELETE FROM security_event WHERE epoch < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Dao
interface PrefDao {
    @Query("SELECT value FROM user_preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(pref: UserPreferences)
}

@Dao
interface SafeZoneDao {
    @Query("SELECT * FROM safe_zone ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SafeZone>>

    @Query("SELECT * FROM safe_zone")
    suspend fun getAll(): List<SafeZone>

    @Query("SELECT * FROM safe_zone WHERE id = :id")
    suspend fun get(id: Long): SafeZone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: SafeZone): Long

    @Query("DELETE FROM safe_zone WHERE id = :id")
    suspend fun delete(id: Long)
}
