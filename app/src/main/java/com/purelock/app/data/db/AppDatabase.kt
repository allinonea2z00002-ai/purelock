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
        SafeZone::class
    ],
    version = 2,
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
        }
    )
}
