package com.kidsmonitor.parent.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.MonitorStatusDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("monitor_names_test", Context.MODE_PRIVATE)

    @Test
    fun `first alert resolves names from the hubs existing monitors endpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"monitors":[
                {"id":"m1","name":"Pixel 6","online":true},
                {"id":"m2","name":"Pixel 3","online":false}
            ]}""").build())
            server.start()
            val api = HubApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), "test-token")
            val names = MonitorNames(preferences, api::monitors)
            assertEquals("Pixel 6", names.nameFor("m1"))
            assertEquals("Pixel 3", names.nameFor("m2"))
            assertEquals("/monitors", server.takeRequest().target)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `names survive service restart and failed reconnect refresh`() = runTest {
        MonitorNames(preferences) { listOf(MonitorStatusDto("m1", "Nursery", true)) }.refresh()
        val restarted = MonitorNames(preferences) { throw IOException("offline") }
        restarted.refresh()
        assertEquals("Nursery", restarted.nameFor("m1"))
    }

    @Test
    fun `newly paired monitor is resolved without a reconnect`() = runTest {
        var monitors = listOf(MonitorStatusDto("m1", "Nursery", true))
        val names = MonitorNames(preferences) { monitors }
        names.refresh()
        monitors = monitors + MonitorStatusDto("m2", "Playroom", true)
        assertEquals("Playroom", names.nameFor("m2"))
    }

    @Test
    fun `cached alerts do not wait for a slow reconnect refresh`() = runTest {
        val response = CompletableDeferred<List<MonitorStatusDto>>()
        val names = MonitorNames(preferences) { response.await() }
        names.remember("m1", "Nursery")
        val refresh = launch { names.refresh() }
        runCurrent()
        val name = async { names.nameFor("m1") }
        runCurrent()
        assertTrue(name.isCompleted)
        assertEquals("Nursery", name.await())
        refresh.cancel()
    }

    @Test
    fun `unavailable names identify the device rather than using a generic monitor`() = runTest {
        val names = MonitorNames(preferences) { throw IOException("offline") }
        assertEquals("Monitor 12345678", names.nameFor("12345678-abcd"))
        assertEquals("Monitor 87654321", names.nameFor("87654321-abcd"))
    }

    @Test
    fun `blank names do not replace a known name`() = runTest {
        val names = MonitorNames(preferences) { listOf(MonitorStatusDto("m1", " ", true)) }
        names.remember("m1", "Nursery")
        names.refresh()
        assertEquals("Nursery", names.nameFor("m1"))
    }
}
