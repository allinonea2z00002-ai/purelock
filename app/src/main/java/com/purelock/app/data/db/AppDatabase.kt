package com.purelock.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProtectionSession::class,
        LockConfiguration::class,
        BlockedApplication::class,
        AllowedApplication::class,
        BlockedDomain::class,
        AllowedDomain::class,
        LocationRecord::class,
        FocusStatistics::class,
        SecurityEvent::class,
        UserPreferences::class,
        SafeZone::class,
        FocusProfile::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockDao(): LockDao
    abstract fun sessionDao(): SessionDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun allowedAppDao(): AllowedAppDao
    abstract fun blockedDomainDao(): BlockedDomainDao
    abstract fun allowedDomainDao(): AllowedDomainDao
    abstract fun locationDao(): LocationDao
    abstract fun statsDao(): StatsDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun prefDao(): PrefDao
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun profileDao(): FocusProfileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "purelock.db"
                )
                    .addMigrations(*Migrations.ALL)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * Schema migrations. Version 1 is the first release, so the list is empty.
 * To change the schema: bump [AppDatabase] version, add a Migration here (never use
 * fallbackToDestructiveMigration: it would wipe an active lock), and commit the new schema JSON.
 */
object Migrations {
    val ALL: Array<Migration> = arrayOf(
        /** Version 2 adds the test-mode marker: a 60-second trial lock is a real lock row, but must
         * never be counted as a real lock in statistics. Existing rows default to "not a test". */
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lock_configuration ADD COLUMN isTest INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE protection_session ADD COLUMN isTest INTEGER NOT NULL DEFAULT 0")
            }
        },
        /**
         * Version 3 adds the per-app blocking-condition columns to blocked_application.
         * The column list below must match [BlockedApplication] exactly (names, types, defaults);
         * every new column defaults to "condition not configured" so old rules keep working.
         */
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN conditionType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN hourlyLimitMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN intervalStartMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN intervalEndMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN fullDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN quickBlockUntilEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN maxLaunchesPerDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN maxLaunchesPerHour INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN waitAfterMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN waitForMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN waitUntilEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN goalAppPackage TEXT")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN goalMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN locationZoneId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN locationMode TEXT NOT NULL DEFAULT 'AT'")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN blockIfLocationUnavailable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN blockLaunch INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE blocked_application ADD COLUMN blockNotifications INTEGER NOT NULL DEFAULT 0")
            }
        },
        /**
         * Version 4 adds the focus_profile table (SPEC §2 profiles). New table, so the CREATE
         * TABLE must match [FocusProfile] exactly (names, types, defaults).
         */
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focus_profile` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL DEFAULT 'BLOCKLIST', " +
                        "`conditionType` TEXT NOT NULL DEFAULT 'NONE', " +
                        "`dailyLimitMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`hourlyLimitMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`intervalDays` INTEGER NOT NULL DEFAULT 0, " +
                        "`intervalStartMin` INTEGER NOT NULL DEFAULT 0, " +
                        "`intervalEndMin` INTEGER NOT NULL DEFAULT 0, " +
                        "`fullDay` INTEGER NOT NULL DEFAULT 0, " +
                        "`quickBlockMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`maxLaunchesPerDay` INTEGER NOT NULL DEFAULT 0, " +
                        "`maxLaunchesPerHour` INTEGER NOT NULL DEFAULT 0, " +
                        "`waitAfterMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`waitForMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`goalAppPackage` TEXT, " +
                        "`goalMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`locationZoneId` INTEGER NOT NULL DEFAULT 0, " +
                        "`locationMode` TEXT NOT NULL DEFAULT 'AT', " +
                        "`blockIfLocationUnavailable` INTEGER NOT NULL DEFAULT 0, " +
                        "`blockLaunch` INTEGER NOT NULL DEFAULT 1, " +
                        "`blockNotifications` INTEGER NOT NULL DEFAULT 0, " +
                        "`motivationalText` TEXT NOT NULL DEFAULT '', " +
                        "`appPackages` TEXT NOT NULL DEFAULT '', " +
                        "`siteDomains` TEXT NOT NULL DEFAULT '', " +
                        "`keywords` TEXT NOT NULL DEFAULT '', " +
                        "`active` INTEGER NOT NULL DEFAULT 0, " +
                        "`paused` INTEGER NOT NULL DEFAULT 0, " +
                        "`template` TEXT, " +
                        "`createdEpoch` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }
    )
}
