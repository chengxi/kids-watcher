package com.kidsmonitor.monitor.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kidsmonitor.monitor.audio.AudioLevel
import com.kidsmonitor.monitor.audio.Calibrator
import com.kidsmonitor.monitor.audio.NoiseDetector
import com.kidsmonitor.monitor.audio.NoiseHistoryAccumulator
import com.kidsmonitor.monitor.store.MonitorName
import com.kidsmonitor.monitor.stream.CameraJpegSource
import com.kidsmonitor.monitor.stream.GravityOrientation
import com.kidsmonitor.monitor.stream.ServiceLifecycleOwner
import com.kidsmonitor.monitor.stream.StreamController
import com.kidsmonitor.monitor.stream.StreamOutput
import com.kidsmonitor.monitor.stream.TalkReceiver
import com.kidsmonitor.monitor.stream.MonitorSpeaker
import kotlinx.coroutines.delay
import com.kidsmonitor.shared.net.HubTrust
import com.kidsmonitor.shared.net.MonitorSocket
import com.kidsmonitor.shared.net.MonitorSocketEvent
import com.kidsmonitor.shared.protocol.DEFAULT_MONITOR_CONFIG
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MonitorConfig
import com.kidsmonitor.shared.protocol.MonitorToHub
import com.kidsmonitor.shared.store.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "KidsMonitor"

/** 16 kHz mono PCM16, analysed in 20 ms windows (spec §9). */
private const val SAMPLE_RATE = 16_000
private const val WINDOW_MS = 20
private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000 // 320

// Often enough to watch a room live over `adb logcat`, rare enough that an
// overnight run does not fill the buffer.
private const val LEVEL_LOG_INTERVAL_MS = 5_000L

/**
 * Listens to the room and reports events to the hub.
 *
 * Typed `camera|microphone`, which is what lets capture continue when the
 * screen locks (spec §3.1). Two conditions come with that and are not
 * negotiable:
 *
 *  - It must be started while an activity is visible. Android refuses to create
 *    a foreground service needing while-in-use permissions from the background.
 *  - There is **no** `BOOT_COMPLETED` receiver, because Android 15+ throws when
 *    one starts a camera or microphone foreground service (spec §3.2). After a
 *    reboot someone taps Start; the parent side reports the gap as
 *    `MONITOR_LOST`.
 *
 * No audio is recorded or stored. Samples are measured in a reused buffer and
 * discarded; events carry metadata only (spec §8).
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var socket: MonitorSocket? = null
    private var talkReceiver: TalkReceiver? = null
    private val cameraOwner = ServiceLifecycleOwner()
    private lateinit var orientation: GravityOrientation

    // Volatile: read by the capture loop on another thread.
    @Volatile private var stream: StreamController? = null
    private var captureJob: Job? = null
    // Volatile: StreamController reads it from the socket's collector.
    @Volatile private var record: AudioRecord? = null

    private var config: MonitorConfig = DEFAULT_MONITOR_CONFIG
    private var detector = NoiseDetector(DEFAULT_MONITOR_CONFIG)
    private val noiseHistory = NoiseHistoryAccumulator()
    private var lastLevelLogMs = 0L
    private var calibrator: Calibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MonitorNotifications.createChannels(this)
        cameraOwner.resume()
        orientation = GravityOrientation(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything that can block or fail: a service that misses the
        // startForeground window is killed outright.
        updateNotice()

        val credentials = credentialsFactory(this).load()
        if (credentials == null) {
            Log.i(TAG, "not paired; nothing to connect to")
            stopSelf()
            return START_NOT_STICKY
        }

        if (socket == null) {
            val client = HubTrust.clientFor(credentials.fingerprint)
            val hubSocket = MonitorSocket(
                client = client,
                wsUrl = credentials.wsUrl,
                token = credentials.token,
                deviceName = MonitorName(this).load(),
                appVersion = APP_VERSION,
                heartbeat = ::heartbeat,
            )
            socket = hubSocket
            stream = StreamController(
                video = CameraJpegSource(this, cameraOwner, orientation.rotation),
                output = object : StreamOutput {
                    override fun sendControl(message: MonitorToHub) = hubSocket.send(message)
                    override fun sendMedia(frame: MediaFrame): Boolean = hubSocket.sendMedia(frame)
                },
                sampleRate = SAMPLE_RATE,
                // Set only once AudioRecord is recording; null when RECORD_AUDIO
                // is denied or the mic is held elsewhere. Never advertise silence as sound.
                audioAvailable = { record != null },
                onStreamingChanged = { watching ->
                    MonitorState.mutableWatching.value = watching
                    updateNotice()
                },
            )
            talkReceiver = TalkReceiver(
                activeStream = { stream?.activeStreamId },
                openSpeaker = { MonitorSpeaker(this) { scope.launch { talkReceiver?.stop("Speaker playback failed") } } },
                send = hubSocket::send,
                onActive = { active -> MonitorState.mutableParentTalking.value = active; Log.i("KidsMonitorTalk", "speaker active=$active") },
            )
            scope.launch { hubSocket.audio.collect { talkReceiver?.offer(it) } }
            scope.launch { while (isActive) { delay(1_000); talkReceiver?.sweep() } }
            observe(hubSocket)
            hubSocket.connect(scope)
            startCapture(hubSocket)
        }

        return START_STICKY
    }

    private fun observe(hubSocket: MonitorSocket) {
        scope.launch {
            hubSocket.events.collect { event ->
                when (event) {
                    is MonitorSocketEvent.Connected -> {
                        logConnection("connected")
                        // Populate /monitors immediately, then refresh on every 15 s heartbeat.
                        hubSocket.send(heartbeat())
                        MonitorState.mutableConnected.value = true
                        updateNotice()
                    }
                    is MonitorSocketEvent.Disconnected -> {
                        talkReceiver?.stop("Hub disconnected")
                        logConnection("disconnected: ${event.reason}")
                        MonitorState.mutableConnected.value = false
                        updateNotice()
                        stream?.let { scope.launch(start = CoroutineStart.UNDISPATCHED) { it.onDisconnected() } }
                    }
                    is MonitorSocketEvent.Message -> handle(event.message, hubSocket)
                }
            }
        }
    }

    private fun logConnection(state: String) {
        val power = getSystemService(PowerManager::class.java)
        Log.i(TAG, "hub=$state idle=${power.isDeviceIdleMode} batterySaver=${power.isPowerSaveMode} exempt=${power.isIgnoringBatteryOptimizations(packageName)}")
    }

    private fun handle(message: HubToMonitor, hubSocket: MonitorSocket) {
        when (message) {
            is HubToMonitor.StartTalk -> talkReceiver?.start(message)
            is HubToMonitor.StopTalk -> talkReceiver?.stop(onlyTalkId = message.talkId)
            is HubToMonitor.Config -> {
                config = message.config
                detector.updateConfig(message.config)
                publishThresholds()
                Log.i(TAG, "config pushed: thresholdDb=${config.thresholdDb} sustainMs=${config.sustainMs}")
            }

            is HubToMonitor.Recalibrate -> {
                Log.i(TAG, "recalibrating for ${message.windowMs}ms")
                calibrator = Calibrator(message.windowMs).apply { start(System.currentTimeMillis()) }
                MonitorState.mutableCalibrating.value = true
            }

            // UNDISPATCHED: each call runs on this collector until its first
            // suspension (taking or queueing for the controller's fair, FIFO lock),
            // so start and stop keep their arrival order. A plain launch on
            // Dispatchers.Default could run a stop_stream before the start it
            // follows and leave the camera on with nobody watching. The collector
            // is not held up by a slow camera: CameraJpegSource switches to the
            // main thread, which suspends this coroutine and returns control here.
            is HubToMonitor.SelectCamera -> stream?.let {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { it.onSelectCamera(message) }
            }
            is HubToMonitor.StartStream -> stream?.let {
                socket?.send(MonitorToHub.StreamPreparing(message.streamId))
                scope.launch(start = CoroutineStart.UNDISPATCHED) { it.onStartStream(message) }
            }
            is HubToMonitor.StopStream -> {
                if (stream?.activeStreamId == message.streamId) talkReceiver?.stop("Live view stopped")
                stream?.let { scope.launch(start = CoroutineStart.UNDISPATCHED) { it.onStopStream(message) } }
            }

            else -> Unit
        }
    }

    private fun startCapture(hubSocket: MonitorSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; cannot listen")
            MonitorState.mutableListening.value = false
            updateNotice()
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord reports no usable buffer size: $minBuffer")
            return
        }

        val started = runCatching {
            @Suppress("MissingPermission")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, WINDOW_SAMPLES * 2 * 4),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialise" }
            recorder.startRecording()
            recorder
        }.onFailure {
            // A revoked permission or a mic held by another app must show in the
            // notification, not crash a service meant to run all night.
            Log.w(TAG, "could not start capture", it)
            MonitorState.mutableListening.value = false
            updateNotice()
        }.getOrNull() ?: return

        record = started
        MonitorState.mutableListening.value = true
        publishThresholds()
        updateNotice()
        Log.i(TAG, "capture started at ${SAMPLE_RATE}Hz")

        captureJob = scope.launch {
            val buffer = ShortArray(WINDOW_SAMPLES)
            while (isActive) {
                val read = started.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                onWindow(AudioLevel.rmsDbfs(buffer, read), hubSocket, maxOf(1, read * 1000 / SAMPLE_RATE))
                stream?.onAudioWindow(buffer, read)
            }
        }
    }

    private fun onWindow(dbfs: Double, hubSocket: MonitorSocket, windowMs: Int = WINDOW_MS) {
        val now = System.currentTimeMillis()
        MonitorState.mutableLevelDb.value = dbfs
        logLevelPeriodically(dbfs, now)

        val running = calibrator
        noiseHistory.onWindow(
            dbfs, now, windowMs,
            if (running?.isRunning == true) null else detector.noiseFloorDb + config.thresholdDb,
        )?.let { hubSocket.send(MonitorToHub.NoiseLevel(it)) }
        if (running != null && running.isRunning) {
            val outcome = running.onWindow(dbfs, now)
            if (outcome != null) {
                detector.setFloor(outcome.noiseFloorDb)
                calibrator = null
                MonitorState.mutableCalibrating.value = false
                publishThresholds()
                Log.i(TAG, "calibrated: floor=${outcome.noiseFloorDb} spread=${outcome.spreadDb}")
                hubSocket.send(
                    MonitorToHub.CalibrationResult(
                        noiseFloorDb = outcome.noiseFloorDb,
                        spreadDb = outcome.spreadDb,
                        sampledMs = outcome.sampledMs,
                    ),
                )
            }
            // Deliberately not detecting during calibration: the window is
            // supposed to measure the room at rest (spec §9.1).
            return
        }

        val detection = detector.onWindow(dbfs, now)
        MonitorState.mutableFloorDb.value = detector.noiseFloorDb
        if (detection == null) return

        Log.i(TAG, "LOUD_NOISE peak=${detection.peakDb} duration=${detection.durationMs}ms")
        MonitorState.mutableLastEventMs.value = now
        hubSocket.send(
            MonitorToHub.Event(
                clientEventId = UUID.randomUUID().toString(),
                type = EventType.LOUD_NOISE,
                tsMs = now,
                metadata = com.kidsmonitor.shared.protocol.EventMetadata(
                    peakDb = detection.peakDb,
                    durationMs = detection.durationMs,
                ),
            ),
        )
    }

    /**
     * "It did not fire" is not a diagnosis. Without a level reading there is no
     * way to tell a microphone that hears nothing from a room quieter than the
     * trigger point, and the two need completely different fixes. The screen is
     * usually off and the meter unreadable when it matters, so this goes to the
     * log.
     *
     * Throttled: capture produces 50 windows a second, and one line each would
     * be unreadable and expensive.
     */
    private fun logLevelPeriodically(dbfs: Double, now: Long) {
        if (now - lastLevelLogMs < LEVEL_LOG_INTERVAL_MS) return
        lastLevelLogMs = now
        val floor = detector.noiseFloorDb
        Log.i(
            TAG,
            "level=%.1f dBFS floor=%.1f trigger=%.1f".format(
                dbfs, floor, floor + config.thresholdDb,
            ),
        )
    }

    private fun publishThresholds() {
        MonitorState.mutableFloorDb.value = detector.noiseFloorDb
        MonitorState.mutableThresholdDb.value = config.thresholdDb
    }

    private fun updateNotice() {
        ServiceCompat.startForeground(
            this,
            MonitorNotifications.ONGOING_ID,
            MonitorNotifications.buildOngoing(
                this,
                connected = MonitorState.connected.value,
                listening = MonitorState.listening.value,
                watching = MonitorState.watching.value,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    private fun batteryPercent(): Int? =
        runCatching {
            getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        }.getOrNull()

    private fun heartbeat(): MonitorToHub.Heartbeat {
        val battery = runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it != -1 }?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING ||
                (it == BatteryManager.BATTERY_STATUS_FULL && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)?.takeIf { it in -50.0..100.0 }
        val volume = runCatching {
            val audio = getSystemService(AudioManager::class.java) ?: return@runCatching null
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maximum <= 0) null else kotlin.math.round(100.0 * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maximum).toInt().coerceIn(0, 100)
        }.getOrNull()
        return MonitorToHub.Heartbeat(batteryPercent(), thermalStatus(), charging, temperature, volume)
    }

    private fun thermalStatus(): String = runCatching {
        when (getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "severe"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    override fun onDestroy() {
        talkReceiver?.stop("Monitoring stopped")
        talkReceiver = null
        // Tell the hub the stream is over while the socket still exists, and
        // release the camera. runBlocking is a new root job, so the
        // scope.cancel() below cannot abort it. The timeout bounds only the
        // cancellable part - chiefly waiting for the controller's lock behind a
        // start in progress. It does not bound the release: StreamController
        // stops the camera non-cancellably, and runBlocking waits for that to
        // finish however long it takes.
        runBlocking { withTimeoutOrNull(2_000) { stream?.shutdown() } }
        stream = null
        orientation.stop()
        cameraOwner.destroy()
        MonitorState.mutableWatching.value = false
        captureJob?.cancel()
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        socket?.disconnect()
        socket = null
        MonitorState.mutableListening.value = false
        MonitorState.mutableConnected.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val APP_VERSION = "0.1.0"

        /**
         * Test seam. Robolectric has no Android Keystore, so the encrypted
         * credential store throws when constructed in a JVM test.
         */
        internal var credentialsFactory: (Context) -> Credentials = { Credentials(it) }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
