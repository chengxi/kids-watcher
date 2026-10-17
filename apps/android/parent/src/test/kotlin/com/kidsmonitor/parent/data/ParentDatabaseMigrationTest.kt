package com.kidsmonitor.parent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.protocol.EventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentDatabaseMigrationTest {
    @Test fun `version two upgrade preserves all history and removes the read column`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "parent-v2-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE events (id INTEGER NOT NULL, monitorId TEXT NOT NULL,
                type TEXT NOT NULL, tsMs INTEGER NOT NULL, peakDb REAL, durationMs INTEGER,
                acked INTEGER NOT NULL, PRIMARY KEY(id))""")
            old.execSQL("INSERT INTO events VALUES (1, 'm1', 'LOUD_NOISE', 20000, -20, 1500, 1)")
            old.execSQL("INSERT INTO events VALUES (2, 'm1', 'MONITOR_LOST', 40000, NULL, NULL, 0)")
            old.execSQL("""CREATE TABLE noise_samples (
                monitorId TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL,
                levelDb REAL NOT NULL, peakDb REAL NOT NULL, thresholdDb REAL,
                PRIMARY KEY(monitorId, startMs))""")
            old.execSQL("INSERT INTO noise_samples VALUES ('m1', 10000, 25000, -50, -20, -38)")
            old.execSQL("""CREATE TABLE noise_alerts (
                id INTEGER NOT NULL, monitorId TEXT NOT NULL, type TEXT NOT NULL,
                tsMs INTEGER NOT NULL, peakDb REAL, durationMs INTEGER, PRIMARY KEY(id))""")
            old.execSQL("INSERT INTO noise_alerts VALUES (100, 'm1', 'LOUD_NOISE', 20000, -20, 1500)")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, ParentDatabase::class.java, name)
            .addMigrations(ParentDatabase.MIGRATION_2_3, ParentDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(listOf(
                EventEntity(2, "m1", EventType.MONITOR_LOST, 40_000, null, null),
                EventEntity(1, "m1", EventType.LOUD_NOISE, 20_000, -20.0, 1500),
            ), db.events().observeRecent(100).first())
            assertEquals(2L, db.events().maxEventId())
            assertEquals(25_000L, db.noise().latestEnd("m1"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM noise_alerts").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.openHelper.readableDatabase.query("PRAGMA table_info(events)").use {
                val names = mutableListOf<String>()
                while (it.moveToNext()) names += it.getString(it.getColumnIndexOrThrow("name"))
                assertFalse(names.contains("acked"))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
