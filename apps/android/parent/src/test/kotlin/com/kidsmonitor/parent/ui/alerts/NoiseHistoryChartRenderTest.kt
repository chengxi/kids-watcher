package com.kidsmonitor.parent.ui.alerts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import com.kidsmonitor.shared.ui.KidMonitorTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.parent.data.NOISE_DAY_MS
import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.NoiseSample
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

/** Renders the real Compose chart for visual inspection, including data and capture gaps. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoiseHistoryChartRenderTest {
    @Test
    fun `renders populated history with explicit grey gaps`() {
        val now = 1_759_593_600_000L
        val from = now - NOISE_DAY_MS
        val samples = (0 until 5760).filter { it !in 1200..2100 && it !in 3900..4400 }.map { index ->
            val start = from + index * 15_000L
            val peak = -54.0 + kotlin.math.sin(index / 180.0) * 8 + if (index % 900 < 8) 30 else 0
            NoiseSample(start, start + 15_000, peak - 4, peak, if (index in 2800..2870) null else if (index < 2900) -38.0 else -32.0)
        }
        val events = listOf(900, 2900, 4800).mapIndexed { index, point ->
            EventEntity(index.toLong(), "m1", EventType.LOUD_NOISE, from + point * 15_000L, -15.0 - index * 5, 1500)
        }
        val bitmap = render(NoiseHistoryState("m1", NoiseHistory(samples, events), now), "kidsmonitor-noise-chart.png")
        var greyPixels = 0
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
            if (bitmap.getPixel(x, y) == 0xFFE2E2E5.toInt()) greyPixels++
        }
        assertTrue("the measured-data gaps must be drawn grey", greyPixels > 500)
    }

    @Test
    fun `a missing period keeps its elapsed width and contains no raw or smoothed trace`() {
        val now = 1_759_593_600_000L
        val from = now - NOISE_DAY_MS
        // Equal readings on either side make a false connecting line obvious.
        val samples = (0 until 5760).filter { it !in 2160 until 3600 }.map { index ->
            val start = from + index * 15_000L
            NoiseSample(start, start + 15_000, -50.0, -45.0, null)
        }
        val bitmap = render(NoiseHistoryState("m1", NoiseHistory(samples), now), "kidsmonitor-noise-chart-gap.png")
        val row = (0 until bitmap.height).maxBy { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == 0xFFE2E2E5.toInt() }
        }
        val greyColumns = (0 until bitmap.width).filter { x -> bitmap.getPixel(x, row) == 0xFFE2E2E5.toInt() }
        // Include the chart's 16dp outer padding. The missing six hours
        // occupy the middle quarter of the plot, x=62..383.
        val expectedLeft = 62 + (383 - 62) * 3 / 8
        val expectedRight = 62 + (383 - 62) * 5 / 8
        assertTrue("measured sections must retain their trend", (row until row + 176).any { y -> bitmap.getPixel(100, y) == 0xFF1A1A1A.toInt() })
        assertTrue("missing time must occupy its true time-axis position", kotlin.math.abs(greyColumns.first() - expectedLeft) <= 1)
        assertTrue("missing time must not be squeezed out", kotlin.math.abs(greyColumns.last() - expectedRight) <= 1)
        for (x in expectedLeft + 2 until expectedRight - 2) {
            for (y in row until minOf(bitmap.height, row + 176)) {
                val color = bitmap.getPixel(x, y)
                assertTrue("a dark trend stroke must not bridge the grey missing period", color != 0xFF1A1A1A.toInt())
                assertTrue("a raw trace must not bridge the grey missing period", color != 0xFFC4C4BF.toInt())
            }
        }
    }

    @Test
    fun `renders missing history as a grey plot rather than a silent level`() {
        val bitmap = render(NoiseHistoryState(monitorId = "m1", nowMs = 1_759_593_600_000L), "kidsmonitor-noise-chart-empty.png")
        var greyPixels = 0
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
            if (bitmap.getPixel(x, y) == 0xFFE2E2E5.toInt()) greyPixels++
        }
        assertTrue("missing history should grey the whole plot", greyPixels > 40_000)
    }

    private fun render(state: NoiseHistoryState, name: String): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val view = ComposeView(activity).apply {
            setContent {
                KidMonitorTheme {
                    Surface {
                        NoiseHistoryChart(state, listOf(MonitorStatusDto("m1", "Pixel 6", true)), {}, initialRangeHours = 24)
                    }
                }
            }
        }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        view.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 411, 430)
        val bitmap = Bitmap.createBitmap(411, 430, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        view.draw(Canvas(bitmap))
        val output = File(System.getProperty("java.io.tmpdir"), name)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
        return bitmap
    }
}
