package com.kidsmonitor.parent.ui.alerts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.parent.ui.live.LiveViewContent
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.VideoFormat
import com.kidsmonitor.shared.protocol.NoiseSample
import com.kidsmonitor.shared.ui.KidMonitorTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParentWireframeRenderTest {
    @Test fun `renders the dashboard with two rooms and measurements`() {
        val now = 1_759_593_600_000L
        val samples = (0 until 720).filter { it !in 210..270 }.map { index ->
            val start = now - 3 * 3_600_000 + index * 15_000
            val peak = -54.0 + kotlin.math.sin(index / 20.0) * 8 + if (index % 180 < 8) 30 else 0
            NoiseSample(start, start + 15_000, peak - 4, peak, -38.0)
        }
        val events = listOf(180, 360, 540).mapIndexed { i, n -> EventEntity(i.toLong(), "m1", EventType.LOUD_NOISE, now - 3 * 3_600_000 + n * 15_000, -24.0, 3000) }
        val bitmap = render("kidsmonitor-parent-home.png", 390, 1000) {
            KidMonitorTheme {
                ParentHome(AlertsUiState(events, ConnectionState.Connected),
                    MonitorListState(listOf(MonitorStatusDto("m1", "Nursery", true, now, 87), MonitorStatusDto("m2", "Kids' room", true, now, 100)), current = true),
                    NoiseHistoryState("m1", NoiseHistory(samples, events), now), "m1", "https://192.168.1.20:8444", onRefresh = {}, onWatch = {}, onSelect = {})
            }
        }
        assertTrue(bitmap.getPixel(0, 0) == android.graphics.Color.WHITE)
    }

    @Test fun `renders dark live view with recovery and back controls`() {
        val bitmap = render("kidsmonitor-parent-live.png", 390, 844) {
            KidMonitorTheme(dark = true) {
                LiveViewContent("Nursery", MonitorStatusDto("m1", "Nursery", true, batteryPct = 87), true, LiveState.Ended("Connection interrupted"), null, {}, {})
            }
        }
        assertTrue(bitmap.getPixel(0, 0) == 0xFF111111.toInt())
    }

    @Test fun `renders healthy live feed with green status and controls below video`() {
        val frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF484848.toInt()) }
        val bitmap = render("kidsmonitor-parent-live-healthy.png", 390, 844) {
            KidMonitorTheme(dark = true) {
                LiveViewContent("Nursery", MonitorStatusDto("m1", "Nursery", true, batteryPct = 87, charging = true), true,
                    LiveState.Playing("m1", 1, VideoFormat(640, 480, 10.0, facing = CameraFacing.BACK, availableCameras = CameraFacing.entries), AudioFormat(16_000, 1)), frame, {}, {})
            }
        }
        var green = 0
        for (x in 0 until bitmap.width) for (y in 0 until 60) {
            if (bitmap.getPixel(x, y) == 0xFF1E9E57.toInt()) green++
        }
        assertTrue("the working stream needs a green status dot", green > 10)
    }

    @Test fun `renders front camera controls in landscape`() {
        val frame = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF484848.toInt()) }
        render("kidsmonitor-parent-front-landscape.png", 844, 390) {
            KidMonitorTheme(dark = true) {
                LiveViewContent("Nursery", MonitorStatusDto("m1", "Nursery", true, batteryPct = 87), true,
                    LiveState.Playing("m1", 1, VideoFormat(480, 640, 10.0, facing = CameraFacing.FRONT, availableCameras = CameraFacing.entries), AudioFormat(16_000, 1)), frame, {}, {})
            }
        }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(controller.get()).apply { setContent(content) }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
        return bitmap
    }
}
