package com.kidsmonitor.monitor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsmonitor.monitor.audio.SILENCE_DBFS
import com.kidsmonitor.monitor.service.MonitorState
import com.kidsmonitor.shared.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorStatusScreen(onStart: () -> Unit, onStop: () -> Unit, monitorName: String = "Room monitor", hubAddress: String = "Home hub") {
    val listening by MonitorState.listening.collectAsStateWithLifecycle()
    val connected by MonitorState.connected.collectAsStateWithLifecycle()
    val levelDb by MonitorState.levelDb.collectAsStateWithLifecycle()
    val floorDb by MonitorState.floorDb.collectAsStateWithLifecycle()
    val thresholdDb by MonitorState.thresholdDb.collectAsStateWithLifecycle()
    val lastEventMs by MonitorState.lastEventMs.collectAsStateWithLifecycle()
    val calibrating by MonitorState.calibrating.collectAsStateWithLifecycle()
    val watching by MonitorState.watching.collectAsStateWithLifecycle()
    val parentTalking by MonitorState.parentTalking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { battery = intent }
        }
        battery = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val percent = battery?.let { intent ->
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) level * 100 / scale else null
    }
    val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
    val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.let { "%.1f°C".format(it / 10.0) }
    MonitorDashboard(listening, connected, levelDb, floorDb, thresholdDb, lastEventMs, calibrating, watching,
        monitorName, hubAddress, percent?.let { "${if (charging) "charging" else "unplugged"} · $it%" } ?: "unavailable", temperature ?: "unavailable", onStart, onStop, parentTalking)
}

@Composable
internal fun MonitorDashboard(
    listening: Boolean, connected: Boolean, levelDb: Double, floorDb: Double, thresholdDb: Double,
    lastEventMs: Long?, calibrating: Boolean, watching: Boolean, monitorName: String, hubAddress: String,
    power: String, temperature: String, onStart: () -> Unit, onStop: () -> Unit,
    parentTalking: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val landscape = maxWidth > maxHeight
        val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = if (listening) onStop else onStart, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (listening) "Stop monitoring" else "Start monitoring", fontSize = 17.sp)
                }
                Text("Start this phone by hand after every restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val stateContent: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(monitorName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = CircleShape, border = BorderStroke(2.dp, if (listening && connected) MaterialTheme.colorScheme.onSurface else Unknown), modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(if (listening && connected) "✓" else "—", fontSize = 30.sp) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (listening) "Monitoring" else "Ready to monitor", fontSize = 26.sp, style = MaterialTheme.typography.headlineSmall)
                        Text(when { !listening -> "Tap Start to listen to this room"; connected -> "Connected via home hub"; else -> "Hub disconnected · alerts unavailable" }, style = MaterialTheme.typography.bodySmall, color = if (listening && !connected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (parentTalking) Text("Parent speaking · speaker on", style = MaterialTheme.typography.titleSmall, color = LiveOrange)
                if (watching) {
                    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, LiveOrange)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusDot(LiveOrange)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Camera on · a parent is watching", style = MaterialTheme.typography.titleSmall)
                                Text("Live video and sound are being shared", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (calibrating) Text("Calibrating — keep the room as it normally is…", style = MaterialTheme.typography.bodyMedium)
                if (!landscape) LevelMeter(levelDb, floorDb, thresholdDb, listening)
                if (landscape) controls()
            }
        }
        val diagnostics: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (landscape) LevelMeter(levelDb, floorDb, thresholdDb, listening)
                Text("DIAGNOSTICS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = Color.Black), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        DiagnosticRow("Home hub", if (connected) "connected" else if (listening) "disconnected" else "stopped", if (connected) Healthy else if (listening) Problem else Unknown)
                        DiagnosticRow("Microphone", if (listening) "listening" else "stopped", if (listening) Healthy else Unknown)
                        DiagnosticRow("Camera", if (watching) "parent watching" else "idle", if (watching) LiveOrange else Unknown)
                        DiagnosticRow("Power", power, if (power == "unavailable") Unknown else Healthy)
                        DiagnosticRow("Temperature", temperature, Unknown)
                    }
                }
                Text(hubAddress, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lastEventMs?.let { "Last alert: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}" } ?: "No alerts yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(Modifier.weight(1f)) { stateContent() }
                Box(Modifier.weight(1f)) { diagnostics() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                stateContent()
                diagnostics()
                controls()
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LevelMeter(levelDb: Double, floorDb: Double, thresholdDb: Double, listening: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Hearing now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (listening) format(levelDb) else "—", fontSize = 30.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val level = if (listening) normalize(levelDb) else 0f
            val threshold = if (floorDb > SILENCE_DBFS) normalize(floorDb + thresholdDb) else 1f
            repeat(20) { index ->
                val position = (index + 1) / 20f
                Box(Modifier.weight(1f).fillMaxHeight().background(when {
                    position <= level -> if (position >= threshold) LiveOrange else MaterialTheme.colorScheme.onSurface
                    position >= threshold && listening -> Color(0xFF4A2416)
                    else -> Color(0xFF262626)
                }))
            }
        }
        Text(triggerText(floorDb, thresholdDb), style = MaterialTheme.typography.bodySmall, color = LiveOrange)
        Text(floorText(floorDb, thresholdDb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val NOT_MEASURED = "not measured yet"
internal fun triggerText(floorDb: Double, thresholdDb: Double): String =
    if (floorDb <= SILENCE_DBFS) "alerts above: $NOT_MEASURED"
    else "alerts above ${format(floorDb + thresholdDb)}"
internal fun floorText(floorDb: Double, thresholdDb: Double): String {
    val floor = if (floorDb <= SILENCE_DBFS) NOT_MEASURED else format(floorDb)
    return "Room floor $floor · threshold +%.0f dB".format(thresholdDb)
}
private fun format(dbfs: Double): String = if (dbfs <= SILENCE_DBFS) "silent" else "%.1f dBFS".format(dbfs)
private fun normalize(dbfs: Double): Float = ((dbfs + 80.0) / 80.0).coerceIn(0.0, 1.0).toFloat()
