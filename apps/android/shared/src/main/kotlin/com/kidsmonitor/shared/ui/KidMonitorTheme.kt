package com.kidsmonitor.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val Healthy = Color(0xFF1E9E57)
val LiveOrange = Color(0xFFF06A3A)
val Problem = Color(0xFFD2382B)
val Unknown = Color(0xFFA3A39C)

@Composable
fun KidMonitorTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFF2F2EE), onPrimary = Color(0xFF111111),
        background = Color.Black, surface = Color.Black, onSurface = Color(0xFFF2F2EE),
        surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFBDBDB6),
        secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color(0xFFF2F2EE),
        tertiary = LiveOrange, outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF262626),
        error = Color(0xFFFF8A80), errorContainer = Color(0xFF3D1714),
    ) else lightColorScheme(
        primary = Color(0xFF1A1A1A), onPrimary = Color.White,
        background = Color.White, surface = Color.White, onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFF3F3F0), onSurfaceVariant = Color(0xFF5C5C58),
        secondaryContainer = Color(0xFFF3F3F0), onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFFB93E0A), outline = Color(0xFF9A9A94), outlineVariant = Color(0xFFD9D9D4),
        error = Problem, errorContainer = Color(0xFFFBE9E7),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(16.dp),
    ), content = content)
}

@Composable
fun StatusDot(color: Color, label: String? = null) {
    Box(Modifier.size(8.dp).background(color, CircleShape).then(
        if (label == null) Modifier else Modifier.semantics { contentDescription = label },
    ))
}

enum class MonitorIcon { HUB, CAMERA, MIC, BATTERY, BACK, ROTATE, RESET, EXPAND, PHOTO, INFO, CHARGING }

/** Small line icons, drawn locally so both apps use the same visual language. */
@Composable
fun MonitorSymbol(icon: MonitorIcon, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x,y), Offset(a,b), 1.8f)
            fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x,y), Size(w,h), androidx.compose.ui.geometry.CornerRadius(2f), style = stroke)
            when (icon) {
                MonitorIcon.PHOTO -> { rect(2f,6f,20f,15f); drawCircle(color,4f,Offset(12f,13f),style=stroke); line(7f,6f,9f,3f); line(9f,3f,15f,3f); line(15f,3f,17f,6f) }
                MonitorIcon.INFO -> { drawCircle(color,9f,Offset(12f,12f),style=stroke); drawCircle(color,1f,Offset(12f,7f)); line(12f,11f,12f,17f) }
                MonitorIcon.CHARGING -> { drawPath(Path().apply { moveTo(14f,2f); lineTo(5f,14f); lineTo(11f,14f); lineTo(10f,22f); lineTo(19f,10f); lineTo(13f,10f); close() }, color) }
                MonitorIcon.HUB -> { rect(3f,4f,18f,7f); rect(3f,13f,18f,7f); drawCircle(color,1f,Offset(7f,7.5f)); drawCircle(color,1f,Offset(7f,16.5f)) }
                MonitorIcon.CAMERA -> { rect(3f,6f,12f,12f); drawPath(Path().apply { moveTo(15f,10f); lineTo(21f,7f); lineTo(21f,17f); lineTo(15f,14f) },color,style=stroke) }
                MonitorIcon.MIC -> { rect(9f,3f,6f,11f); drawArc(color,0f,180f,false,Offset(5f,4f),Size(14f,14f),style=stroke); line(12f,18f,12f,22f) }
                MonitorIcon.BATTERY -> { rect(2f,7f,18f,10f); line(22f,10f,22f,14f); line(6f,10f,6f,14f); line(10f,10f,10f,14f) }
                MonitorIcon.BACK -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
                MonitorIcon.ROTATE -> { drawArc(color,45f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke); line(20f,4f,20f,9f); line(20f,9f,15f,9f) }
                MonitorIcon.RESET -> { drawCircle(color,4f,Offset(12f,12f),style=stroke); line(12f,2f,12f,6f); line(12f,18f,12f,22f); line(2f,12f,6f,12f); line(18f,12f,22f,12f) }
                MonitorIcon.EXPAND -> { line(4f,9f,4f,4f); line(4f,4f,9f,4f); line(20f,9f,20f,4f); line(20f,4f,15f,4f); line(4f,15f,4f,20f); line(4f,20f,9f,20f); line(20f,15f,20f,20f); line(20f,20f,15f,20f) }
            }
        }
    }
}
