package com.kidsmonitor.parent.ui.live

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kidsmonitor.parent.live.CameraUiState
import com.kidsmonitor.parent.R
import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.parent.live.TalkUiState
import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.ui.*

/**
 * Foreground-only by decision: leaving this screen (another app, locking the
 * phone) stops the stream and the monitor's camera. ON_STOP, not onDispose
 * alone, because a locked phone does not dispose the composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewScreen(
    monitorId: String,
    monitorName: String,
    monitor: MonitorStatusDto?,
    hubConnected: Boolean,
    onBack: () -> Unit,
) {
    val viewModel: LiveViewModel = viewModel(key = "live-$monitorId") { LiveViewModel(monitorId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val talkState by viewModel.talkState.collectAsStateWithLifecycle()
    val cameraState by viewModel.cameraState.collectAsStateWithLifecycle()
    val photoState by viewModel.photoState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startTalking() else viewModel.microphoneDenied()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.start()
                Lifecycle.Event.ON_STOP -> viewModel.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousAppearance = bars?.isAppearanceLightStatusBars
        val previousNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            view.keepScreenOn = false
            previousAppearance?.let { bars.isAppearanceLightStatusBars = it }
            previousNavigation?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }

    BackHandler(onBack = onBack)

    KidMonitorTheme(dark = true) {
        LiveViewContent(monitorName, monitor, hubConnected, state, frame,
            onBack = onBack, onRetry = { viewModel.retry() }, talkState = talkState,
            cameraState = cameraState, onSwitchCamera = viewModel::switchCamera,
            photoState = photoState,
            onPhoto = { rotation -> viewModel.savePhoto(context.applicationContext.contentResolver, rotation) },
            onPhotoMessageShown = viewModel::dismissPhotoMessage,
            onTalk = {
                if (talkState.active || talkState.starting) viewModel.stopTalking()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.startTalking()
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            })
    }
}

@Composable
internal fun LiveViewContent(
    monitorName: String,
    monitor: MonitorStatusDto?,
    hubConnected: Boolean,
    state: LiveState,
    frame: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    talkState: TalkUiState = TalkUiState(),
    onTalk: () -> Unit = {},
    cameraState: CameraUiState = CameraUiState(),
    onSwitchCamera: () -> Unit = {},
    photoState: PhotoUiState = PhotoUiState(),
    onPhoto: (Float) -> Unit = {},
    onPhotoMessageShown: () -> Unit = {},
) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(photoState.message) {
        photoState.message?.let { snackbar.showSnackbar(it); onPhotoMessageShown() }
    }
    if (showInfo) {
        val current = monitor?.takeIf { hubConnected && it.online }
        AlertDialog(onDismissRequest = { showInfo = false },
            title = { Text("$monitorName · device info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Battery temperature: " + (current?.batteryTemperatureC?.let { String.format(java.util.Locale.getDefault(), "%.1f °C", it) } ?: "Unavailable"))
                    Text("Battery: " + (current?.batteryPct?.let { "$it%" } ?: "Unavailable"))
                    Text("Power: " + when (current?.charging) { true -> if (current.batteryPct == 100) "Fully charged · plugged in" else "Charging"; false -> "Not charging"; null -> "Unavailable" })
                    Text("Speaker volume: " + (current?.speakerVolumePct?.let { "$it%" } ?: "Unavailable"))
                    Text("Device readings refresh about every 30 seconds.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } })
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var rotation by rememberSaveable { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun boundedPan(value: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset {
        val x = viewport.width * (zoom - 1f) / 2
        val y = viewport.height * (zoom - 1f) / 2
        return androidx.compose.ui.geometry.Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    val transform = rememberTransformableState { scale, offset, _ ->
        zoom = (zoom * scale).coerceIn(1f, 4f)
        pan = boundedPan(pan + offset)
    }
    val healthy = !cameraState.switching && liveFeedHealthy(state, frame != null, hubConnected)
    val canSavePhoto = !cameraState.switching && !photoState.saving && hubConnected && frame != null && state is LiveState.Playing && state.video != null
    val videoFormat = (state as? LiveState.Playing)?.video
    val canSwitch = hubConnected && !cameraState.switching && videoFormat?.facing != null &&
        CameraFacing.BACK in videoFormat.availableCameras && CameraFacing.FRONT in videoFormat.availableCameras
    val switchDescription = when {
        cameraState.switching -> "Switching camera"
        videoFormat?.facing == CameraFacing.FRONT -> "Switch to back camera"
        else -> "Switch to front camera"
    }
    Surface(color = Color(0xFF111111), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxWidth > maxHeight
            val video: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Color(0xFF2A2A2A)).clipToBounds()
                    .onSizeChanged { viewport = it; pan = boundedPan(pan) }
                    .transformable(transform), contentAlignment = Alignment.Center) {
                    if (frame != null && (state as? LiveState.Playing)?.video != null) {
                        Image(frame.asImageBitmap(), "Live view of $monitorName", Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y; rotationZ = rotation
                        }, contentScale = ContentScale.Fit)
                    } else {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val playing = state as? LiveState.Playing
                            if (state !is LiveState.Ended && (playing == null || playing.video != null)) CircularProgressIndicator()
                            Text(when { cameraState.switching -> "Switching camera…"; playing?.video != null -> "Waiting for the picture…"; playing != null -> "The camera is unavailable. Live sound continues."; state is LiveState.Ended -> "Video unavailable"; else -> "Connecting automatically…" })
                        }
                    }
                    val battery = monitor?.batteryPct?.takeIf { hubConnected && monitor.online && it in 0..100 }
                    Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .65f), contentColor = Color.White) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            BatteryIcon(battery, Modifier.size(30.dp, 18.dp))
                            if (battery != null && monitor?.charging == true) MonitorSymbol(MonitorIcon.CHARGING, Color.White,
                                Modifier.size(18.dp).semantics { contentDescription = "Monitor charging" })
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onPhoto(rotation) }, enabled = canSavePhoto,
                        modifier = Modifier.semantics { contentDescription = if (photoState.saving) "Saving photo" else "Save photo to gallery" }) {
                        if (photoState.saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else MonitorSymbol(MonitorIcon.PHOTO, LocalContentColor.current)
                    }
                    IconButton(onClick = onSwitchCamera, enabled = canSwitch) {
                        if (cameraState.switching) CircularProgressIndicator(Modifier.size(22.dp)
                            .semantics { contentDescription = switchDescription }, strokeWidth = 2.dp)
                        else Icon(painterResource(R.drawable.ic_switch_camera), switchDescription, Modifier.size(24.dp))
                    }
                    IconButton(onClick = { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero; rotation = 0f },
                        modifier = Modifier.semantics { contentDescription = "Reset video view" }) {
                        MonitorSymbol(MonitorIcon.RESET, LocalContentColor.current)
                    }
                    IconButton(onClick = { rotation = (rotation + 90) % 360; pan = androidx.compose.ui.geometry.Offset.Zero },
                        modifier = Modifier.semantics { contentDescription = "Rotate video 90 degrees" }) {
                        MonitorSymbol(MonitorIcon.ROTATE, LocalContentColor.current)
                    }
                }
            }
            val details: @Composable () -> Unit = {
                if (cameraState.switching) Text("Switching camera…", style = MaterialTheme.typography.bodySmall)
                videoFormat?.facing?.let { Text(if (it == CameraFacing.FRONT) "Front camera" else "Back camera", style = MaterialTheme.typography.bodySmall) }
                cameraState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                Text("Pinch to zoom · drag to pan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!healthy) Text(liveStatusText(state, frame != null), style = MaterialTheme.typography.bodyMedium)
                if (!cameraState.switching && (state is LiveState.Ended || state is LiveState.Playing && state.video == null)) OutlinedButton(onClick = onRetry) { Text("Retry video") }
            }
            val talk: @Composable () -> Unit = {
                OutlinedButton(onClick = onTalk,
                    enabled = talkState.active || talkState.starting || (hubConnected && state is LiveState.Playing),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (talkState.active) LiveOrange else MaterialTheme.colorScheme.onSurface)) {
                    Text(when { talkState.active -> "Stop talking"; talkState.starting -> "Cancel voice connection"; else -> "Talk to monitor" })
                }
                if (talkState.active) Text("Microphone on · monitor speaker on. Room audio is paused while you speak.", style = MaterialTheme.typography.bodySmall, color = LiveOrange)
                if (talkState.starting) Text("Connecting to the monitor speaker…", style = MaterialTheme.typography.bodySmall)
                talkState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to home" }) { MonitorSymbol(MonitorIcon.BACK) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monitorName, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(when { healthy -> Healthy; state is LiveState.Ended -> Problem; state is LiveState.Playing -> LiveOrange; else -> Unknown })
                            Text(when (state) { is LiveState.Playing -> "LIVE"; is LiveState.Ended -> "Stopped"; else -> "Connecting" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.semantics { contentDescription = "Monitor device information" }) { MonitorSymbol(MonitorIcon.INFO) }
                }
                if (compact) {
                    Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            video(Modifier.weight(1f).fillMaxWidth())
                            controls()
                        }
                        Column(Modifier.width(190.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { details(); talk() }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            video(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                            controls()
                        }
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { details() }
                    }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        talk()
                        Text("Watching stops when you leave this screen", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

internal fun liveFeedHealthy(state: LiveState, hasPicture: Boolean, hubConnected: Boolean): Boolean =
    state is LiveState.Playing && state.video != null && state.audio != null && hasPicture && hubConnected

@Composable
private fun BatteryIcon(percent: Int?, modifier: Modifier) {
    val color = if (percent != null && percent <= 15) Color(0xFFFF8A80) else Color.White
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = percent?.let { "Monitor battery: $it percent" } ?: "Monitor battery unavailable" }) {
        val bodyWidth = size.width * .86f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()), androidx.compose.ui.geometry.Size(bodyWidth - 2.dp.toPx(), size.height - 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        drawRect(color, androidx.compose.ui.geometry.Offset(bodyWidth + 1.dp.toPx(), size.height * .3f), androidx.compose.ui.geometry.Size(size.width - bodyWidth - 1.dp.toPx(), size.height * .4f))
        if (percent != null) {
            drawRect(color, androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.Size((bodyWidth - 8.dp.toPx()) * percent / 100f, size.height - 8.dp.toPx()))
        } else {
            drawLine(color, androidx.compose.ui.geometry.Offset(bodyWidth * .35f, size.height / 2), androidx.compose.ui.geometry.Offset(bodyWidth * .65f, size.height / 2), 1.5.dp.toPx())
        }
    }
}
