package com.kidsmonitor.parent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.protocol.EventType
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoiseHistoryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `incremental fetch retains earlier coverage and markers without advancing the alert cursor`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ParentDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        val repository = NoiseHistoryRepository(db, HubApi(client, server.url("/").toString().trimEnd('/'), "tok"))
        try {
            db.events().upsertAll(listOf(EventEntity(1, "m1", EventType.LOUD_NOISE, 20_000, -20.0, 1500)))
            fun response(start: Long) = """{"windowStartMs":0,"toMs":100000,"samples":[{"startMs":$start,"endMs":${start + 15000},"levelDb":-50,"peakDb":-20,"thresholdDb":-38}],"events":[{"id":1001,"monitorId":"m1","type":"LOUD_NOISE","tsMs":30000,"metadata":{"peakDb":-20,"durationMs":1500}}]}"""
            server.enqueue(MockResponse.Builder().body(response(10_000)).build())
            repository.refresh("m1", 100_000)
            assertEquals("/monitors/m1/noise-history", server.takeRequest().target)
            server.enqueue(MockResponse.Builder().body(response(70_000)).build())
            val (history, truncated) = repository.refresh("m1", 100_000)
            assertEquals("/monitors/m1/noise-history?fromMs=10000", server.takeRequest().target)
            assertEquals(listOf(10_000L, 70_000L), history.samples.map { it.startMs })
            assertEquals(listOf(1L, 1001L), history.events.map { it.id })
            assertEquals(1L, db.events().maxEventId()) // chart history must not move the list's backfill cursor
            assertFalse(truncated)
            assertEquals(history, repository.cached("m1", 100_000))
        } finally {
            db.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    fun `version one migration preserves stored alerts while removing read state`() = runBlocking {
        val name = "noise-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE events (id INTEGER NOT NULL, monitorId TEXT NOT NULL,
                type TEXT NOT NULL, tsMs INTEGER NOT NULL, peakDb REAL, durationMs INTEGER,
                acked INTEGER NOT NULL, PRIMARY KEY(id))""")
            old.execSQL("INSERT INTO events VALUES (1, 'm1', 'LOUD_NOISE', 20000, -20, 1500, 1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, ParentDatabase::class.java, name)
            .addMigrations(ParentDatabase.MIGRATION_1_2, ParentDatabase.MIGRATION_2_3, ParentDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(EventEntity(1, "m1", EventType.LOUD_NOISE, 20_000, -20.0, 1500), db.events().inWindow("m1", 0).single())
            db.noise().upsert(listOf(NoiseEntity("m1", 10_000, 25_000, -50.0, -20.0, -38.0)))
            assertEquals(25_000L, db.noise().latestEnd("m1"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
