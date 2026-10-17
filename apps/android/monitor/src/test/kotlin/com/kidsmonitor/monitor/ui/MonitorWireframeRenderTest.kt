package com.kidsmonitor.monitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import com.kidsmonitor.shared.ui.KidMonitorTheme
import org.junit.Assert.assertEquals
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitorWireframeRenderTest {
    @Test @Config(qualifiers = "w390dp-h900dp-mdpi")
    fun `renders portrait monitor diagnostics`() { render(390, 900, "kidsmonitor-monitor-portrait.png") }

    @Test @Config(qualifiers = "w844dp-h390dp-mdpi")
    fun `renders landscape monitor with camera notice`() { render(844, 390, "kidsmonitor-monitor-landscape.png") }

    private fun render(width: Int, height: Int, name: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(controller.get()).apply {
            setContent {
                KidMonitorTheme(dark = true) {
                    Surface {
                        MonitorDashboard(true, true, -49.0, -58.0, 12.0, null, false, true, "Nursery", "https://192.168.1.20:8444", "charging · 100%", "31°C", {}, {})
                    }
                }
            }
        }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(0, 0))
        controller.pause().stop().destroy()
    }
}
