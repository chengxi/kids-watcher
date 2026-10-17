package com.kidsmonitor.parent.ui.alerts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.protocol.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun NoiseHistoryChart(
    state: NoiseHistoryState,
    monitors: List<MonitorStatusDto>,
    onSelect: (String) -> Unit,
    initialRangeHours: Int = 3,
) {
    var rangeHours by rememberSaveable { mutableIntStateOf(initialRangeHours) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val model = remember(state.history, state.nowMs, rangeHours) { noiseChartModel(state.history, state.nowMs, rangeHours * 3_600_000L) }
    val trendWindowMs = noiseTrendWindowMs(rangeHours)
    val trend = remember(model.samples, trendWindowMs) { smoothedNoiseTrend(model.samples, trendWindowMs) }
    val axisThreshold = model.samples.lastOrNull()?.thresholdDb?.takeIf { it.isFinite() }
    val colors = MaterialTheme.colorScheme
    val line = colors.primary
    val rawLine = Color(0xFFC4C4BF)
    val thresholdColor = colors.tertiary
    val alertColor = colors.error
    val noDataColor = Color(0xFFE2E2E5)
    val noDataInk = Color(0xFF4C4C52)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val density = LocalDensity.current
    val leftPx = with(density) { 46.dp.toPx() }
    val rightPx = with(density) { 12.dp.toPx() }
    var selectedTime by remember(state.monitorId, rangeHours) { mutableStateOf<Long?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val fullTimeFormat = remember(locale) { SimpleDateFormat("EEE HH:mm:ss", locale) }
    val axis = remember(model, trend, axisThreshold) { noiseChartAxis(model, trend, axisThreshold) }
    val selected = selectedTime
    val selectedSample = selected?.let { t -> model.samples.findLast { t >= it.startMs && t < it.endMs } }
    val selectedEvent = selected?.let { t -> model.events.minByOrNull { abs(it.tsMs - t) }?.takeIf { abs(it.tsMs - t) < 5 * 60_000 } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Noise level", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 3, 12, 24).forEach { hours ->
                FilterChip(selected = rangeHours == hours, onClick = { rangeHours = hours }, label = { Text("${hours}h", style = MaterialTheme.typography.labelSmall) })
            }
        }
        if (monitors.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                monitors.forEach { monitor ->
                    FilterChip(selected = monitor.id == state.monitorId, onClick = { onSelect(monitor.id) }, label = { Text(monitor.name) })
                }
            }
        }
        val windowLabel = if (trendWindowMs < 3_600_000) "${trendWindowMs / 60_000} min" else "${trendWindowMs / 3_600_000} hr"
        Text("$windowLabel weighted average · relative sound level (dBFS)", style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(220.dp)
                .semantics { contentDescription = "Noise for the last $rangeHours hours. ${model.events.size} alerts. ${model.coveredMs / 60_000} minutes with measurements. Smoothed curve is a ${trendWindowMs / 60_000}-minute centered peak trend. Raw peaks are shown in grey. The Y-axis automatically fits the visible measurements. Dashed line is the latest adaptive alert level, not historical alert settings. Grey bands preserve missing time; both lines stop at gaps. No data is not silence. Tap to inspect a time." }
                .pointerInput(model, leftPx, rightPx) {
                    detectTapGestures { position ->
                        val fraction = ((position.x - leftPx) / (size.width - leftPx - rightPx)).coerceIn(0f, 1f)
                        selectedTime = model.fromMs + ((model.toMs - model.fromMs) * fraction).toLong()
                    }
                },
        ) {
            val top = 12.dp.toPx()
            val bottom = size.height - 32.dp.toPx()
            val width = size.width - leftPx - rightPx
            fun x(time: Long) = leftPx + ((time - model.fromMs).toDouble() / (model.toMs - model.fromMs) * width).toFloat()
            fun y(db: Double) = bottom - ((db - axis.bottomDb) / (axis.topDb - axis.bottomDb) * (bottom - top)).toFloat()
            drawRect(noDataColor, Offset(leftPx, top), Size(width, bottom - top))
            model.coverage.forEach { span ->
                drawRect(colors.surface, Offset(x(span.startMs), top), Size(x(span.endMs) - x(span.startMs), bottom - top))
            }
            for (tick in 0..4) {
                val db = axis.ticks[tick]
                val position = y(db)
                drawLine(colors.outlineVariant.copy(alpha = 0.65f), Offset(leftPx, position), Offset(leftPx + width, position))
                if (axisThreshold == null || abs(position - y(axisThreshold)) > 14.dp.toPx()) {
                    val label = measurer.measure(if (db % 1.0 == 0.0) db.toInt().toString() else "%.1f".format(db), labelStyle)
                    drawText(label, topLeft = Offset(leftPx - label.size.width - 6.dp.toPx(), position - label.size.height / 2))
                }
            }
            axisThreshold?.let { threshold ->
                val position = y(threshold)
                val label = measurer.measure("%.1f".format(threshold), labelStyle.copy(color = thresholdColor))
                drawText(label, topLeft = Offset(leftPx - label.size.width - 6.dp.toPx(), position - label.size.height / 2))
                drawLine(thresholdColor, Offset(leftPx - 4.dp.toPx(), position), Offset(leftPx + 6.dp.toPx(), position), 2.dp.toPx())
            }
            clipRect(leftPx, top, leftPx + width, bottom) {
                // Clip every data trace to actual measured time, independently
                // of averaging tolerance. Neither a stroke nor a cubic curve
                // may imply measurements across any recorded gap.
                val measuredArea = Path().apply {
                    model.coverage.forEach { span -> addRect(Rect(x(span.startMs), top, x(span.endMs), bottom)) }
                }
                clipPath(measuredArea) {
                    var previous: com.kidsmonitor.shared.protocol.NoiseSample? = null
                    for (sample in model.samples) {
                        val previousSample = previous
                        drawLine(rawLine, Offset(x(sample.startMs), y(sample.peakDb)), Offset(x(sample.endMs), y(sample.peakDb)), strokeWidth = 1.dp.toPx())
                        if (previousSample != null && sample.startMs <= previousSample.endMs) {
                            drawLine(rawLine, Offset(x(previousSample.endMs), y(previousSample.peakDb)), Offset(x(sample.startMs), y(sample.peakDb)), strokeWidth = 1.dp.toPx())
                        }
                        previous = sample
                    }
                    axisThreshold?.let { threshold ->
                        // This reference is the latest reported setting across the
                        // view. Historical settings stay available on tap.
                        model.coverage.forEach { span ->
                            drawLine(thresholdColor, Offset(x(span.startMs), y(threshold)), Offset(x(span.endMs), y(threshold)),
                                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                        }
                    }
                    trend.forEach { segment ->
                        val first = segment.first()
                        val tangents = noiseTrendTangents(segment)
                        val path = Path().apply {
                            moveTo(x(first.timeMs), y(first.levelDb))
                            segment.zipWithNext().forEachIndexed { i, (previous, point) ->
                                val thirdX = (x(point.timeMs) - x(previous.timeMs)) / 3
                                val thirdTime = (point.timeMs - previous.timeMs) / 3.0
                                cubicTo(x(previous.timeMs) + thirdX, y(previous.levelDb + tangents[i] * thirdTime),
                                    x(point.timeMs) - thirdX, y(point.levelDb - tangents[i + 1] * thirdTime),
                                    x(point.timeMs), y(point.levelDb))
                            }
                        }
                        drawPath(path, line, style = Stroke(2.dp.toPx()))
                    }
                }
                selected?.let { drawLine(colors.onSurface.copy(alpha = 0.5f), Offset(x(it), top), Offset(x(it), bottom), strokeWidth = 1.dp.toPx()) }
            }
            // Noise alerts use measured peaks on the Y axis. Other events have no
            // loudness measurement and sit in a separate lane above the plot.
            model.events.forEach { event ->
                val peak = event.peakDb?.takeIf { it.isFinite() && it in -120.0..0.0 }
                if (event.type == EventType.LOUD_NOISE && peak != null) {
                    drawCircle(colors.surface, 4.dp.toPx(), Offset(x(event.tsMs), y(peak)))
                    drawCircle(alertColor, 3.dp.toPx(), Offset(x(event.tsMs), y(peak)))
                } else {
                    val marker = Path().apply {
                        moveTo(x(event.tsMs) - 3.dp.toPx(), top - 8.dp.toPx())
                        lineTo(x(event.tsMs) + 3.dp.toPx(), top - 8.dp.toPx())
                        lineTo(x(event.tsMs), top - 2.dp.toPx())
                        close()
                    }
                    drawPath(marker, if (event.type == EventType.MONITOR_LOST) alertColor else thresholdColor)
                }
            }
            for (tick in 0..4) {
                val time = model.fromMs + (model.toMs - model.fromMs) * tick / 4
                val label = measurer.measure(if (tick == 4) "Now" else timeFormat.format(Date(time)), labelStyle)
                val labelX = (x(time) - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(labelX, bottom + 8.dp.toPx()))
            }
            model.gaps.forEach { span ->
                val gapWidth = x(span.endMs) - x(span.startMs)
                val style = TextStyle(fontSize = 12.sp, color = noDataInk)
                val singleLine = measurer.measure("No data", style)
                val label = if (gapWidth > singleLine.size.width + 6.dp.toPx()) singleLine else measurer.measure("No\ndata", style)
                if (gapWidth > label.size.width + 6.dp.toPx()) {
                    drawText(label, topLeft = Offset((x(span.startMs) + x(span.endMs) - label.size.width) / 2, (top + bottom - label.size.height) / 2))
                }
            }
        }
            IconButton(onClick = { showGuide = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 32.dp).size(48.dp)
                .semantics { contentDescription = "Sound level guide" }) { Text("ⓘ", style = MaterialTheme.typography.titleMedium) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("━ Smoothed", color = line, style = MaterialTheme.typography.labelSmall)
            Text("━ Raw peaks", color = rawLine, style = MaterialTheme.typography.labelSmall)
            Text("┄ Latest alert level", color = thresholdColor, style = MaterialTheme.typography.labelSmall)
            Text("● Alert  ▾ Other", color = alertColor, style = MaterialTheme.typography.labelSmall)
        }

        Text("Auto-scaled axis · higher is louder. Grey bands = no data; lines stop at gaps.", style = MaterialTheme.typography.bodySmall)
        if (selected != null) {
            val level = selectedSample?.let { "Raw peak %.1f dBFS · threshold %s".format(it.peakDb, it.thresholdDb?.let { n -> "%.1f dBFS".format(n) } ?: "unavailable") } ?: "No data"
            Text("${fullTimeFormat.format(Date(selected))} · $level", style = MaterialTheme.typography.bodySmall)
            selectedEvent?.let { Text("Nearby alert: ${it.type.name.lowercase().replace('_', ' ')} at ${timeFormat.format(Date(it.tsMs))}${it.peakDb?.let { db -> " · %.1f dBFS".format(db) }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
        }
        if (state.loading) Text("Updating noise history…", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        if (state.eventsTruncated) Text("Some older alert markers are unavailable in this view.", style = MaterialTheme.typography.bodySmall)
        if (showGuide) SoundLevelGuide(onDismiss = { showGuide = false })
    }
}

@Composable
internal fun SoundLevelGuide(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Understanding sound levels") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This chart shows the phone microphone’s relative level (dBFS). Higher numbers mean louder sound; 0 is the microphone’s digital limit. Compare readings from the same phone in the same position.")
                Text("Everyday examples", style = MaterialTheme.typography.titleSmall)
                Text("These approximate room sound levels use acoustic decibels (dBA), a different scale. They cannot be matched to this chart without sound-meter calibration.")
                listOf("Quiet bedroom" to "about 30 dBA", "Living room, TV off" to "about 40 dBA",
                    "Conversation" to "about 60 dBA", "Kitchen dishwasher" to "45–65 dBA",
                    "Kitchen garbage disposal" to "about 80 dBA", "Playground (one site)" to "61–73 dBA", "Concert" to "95–115 dBA").forEach { (place, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(place, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Examples vary with activity and distance. The playground example spans the average and peak at one measured location.")
                Text("How alerts work", style = MaterialTheme.typography.titleSmall)
                Text("The dashed horizontal line is the latest alert level. It adapts to the room’s background sound, so earlier alerts may have used a different level. Sustained microphone sound triggers alerts (1.5 seconds by default); brief peaks and the smoothed average do not trigger them. Tap a time to see the alert level reported then.")
                Text("The dark line is a weighted moving average of measured peaks. It shows the longer-term pattern; grey peaks and red alert dots preserve short events. Grey bands show periods without measurements at their actual duration. Both lines stop at those gaps.")
                Text("Examples: CDC; Puyallup and San José noise studies.", style = MaterialTheme.typography.labelSmall)
            }
        })
}
