package com.kidsmonitor.parent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kidsmonitor.shared.protocol.EventType

class EventTypeConverter {
    @TypeConverter fun toName(type: EventType): String = type.name

    // An unrecognized name means a newer hub sent an event type this app does
    // not know about. Keep the row rather than crashing the query.
    @TypeConverter fun fromName(name: String): EventType =
        runCatching { EventType.valueOf(name) }.getOrDefault(EventType.LOUD_NOISE)
}

@Database(entities = [EventEntity::class, NoiseEntity::class, NoiseAlertEntity::class], version = 4, exportSchema = false)
@TypeConverters(EventTypeConverter::class)
abstract class ParentDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun noise(): NoiseDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS noise_samples (
                    monitorId TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL,
                    levelDb REAL NOT NULL, peakDb REAL NOT NULL, thresholdDb REAL,
                    PRIMARY KEY(monitorId, startMs))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS noise_alerts (
                    id INTEGER NOT NULL, monitorId TEXT NOT NULL, type TEXT NOT NULL,
                    tsMs INTEGER NOT NULL, peakDb REAL, durationMs INTEGER, PRIMARY KEY(id))""")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild for Android versions whose SQLite cannot DROP COLUMN.
                db.execSQL("""CREATE TABLE events_without_read_state (
                    id INTEGER NOT NULL, monitorId TEXT NOT NULL, type TEXT NOT NULL,
                    tsMs INTEGER NOT NULL, peakDb REAL, durationMs INTEGER, PRIMARY KEY(id))""")
                db.execSQL("""INSERT INTO events_without_read_state
                    SELECT id, monitorId, type, tsMs, peakDb, durationMs FROM events""")
                db.execSQL("DROP TABLE events")
                db.execSQL("ALTER TABLE events_without_read_state RENAME TO events")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_monitorId_id ON events(monitorId, id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_type_id ON events(type, id)")
            }
        }
        @Volatile private var instance: ParentDatabase? = null

        fun open(context: Context): ParentDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ParentDatabase::class.java, "parent.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
