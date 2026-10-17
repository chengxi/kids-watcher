package com.kidsmonitor.monitor.stream

import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.MonitorToHub
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a camera may take to start (CameraX provider plus bind) before the
 * stream goes ahead without it.
 */
const val VIDEO_START_TIMEOUT_MS = 5_000L

/**
 * How long after a successful bind the first frame may take. Binding returns
 * before the camera has opened; a camera that fails to open (in use, policy,
 * background access refused) never produces a frame.
 *
 * With [VIDEO_START_TIMEOUT_MS] this stays under the parent's 10 s START_TIMEOUT_MS,
 * so the hub always has an answer before the parent gives up.
 */
const val FIRST_FRAME_TIMEOUT_MS = 3_000L

/** A camera that produces JPEG frames. The real one is CameraJpegSource. */
interface VideoSource {
    /**
     * Starts capture and returns the format produced, or null if the camera
     * could not start. [onError] reports a failure the camera only discovers
     * after start returns (CameraX reports it on CameraState).
     * [onJpeg] includes that frame's clockwise rotation, independent of the display.
     */
    suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat?
    suspend fun stop()
    /** Check availability and select the lens for the next start, without opening it. */
    suspend fun selectFacing(facing: CameraFacing): Boolean = false
}

/** Where stream traffic goes. The service implements it over MonitorSocket. */
interface StreamOutput {
    fun sendControl(message: MonitorToHub)
    /** Returns false if the frame was dropped. Dropping is normal under backpressure. */
    fun sendMedia(frame: MediaFrame): Boolean
}

/**
 * The monitor's side of one live stream (spec §4.3–4.4).
 *
 * At most one stream at a time, shared by every viewer on the hub side. The
 * camera runs only between start and the first of: stop_stream, losing the
 * hub, or the service stopping. Audio is not captured here: the service feeds
 * the windows it already reads for detection into [onAudioWindow], so a stream
 * never opens a second microphone and detection never pauses for one.
 *
 * Only what is really being captured is advertised. Video counts once the
 * camera has delivered a frame (see [FIRST_FRAME_TIMEOUT_MS]); audio only while
 * [audioAvailable] says the service's recorder is running. A parent shown
 * "Live" over a dead camera or a silent microphone is told something false.
 *
 * Called from three threads - the socket's collector, the capture loop and the
 * camera's executor - so state changes are serialised and the hot paths read
 * volatile fields.
 */
class StreamController(
    private val video: VideoSource,
    private val output: StreamOutput,
    private val sampleRate: Int,
    /** Whether the service's microphone is capturing right now. */
    private val audioAvailable: () -> Boolean,
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
    private val onStreamingChanged: (Boolean) -> Unit = {},
) {
    private val lock = Mutex()
    // Serialises format announcements with the camera executor's frames.
    private val mediaLock = Any()
    private var cameraGeneration = 0
    private var latestRotation: Int? = null
    private var announcedVideo: VideoFormat? = null
    private var announcedAudio: AudioFormat? = null

    @Volatile private var streamId: Int? = null
    @Volatile private var audioWanted = false
    @Volatile private var startedAtUs = 0L
    /** Media goes out only after stream_started, so the hub and parent know the stream. */
    @Volatile private var announced = false
    private var videoRunning = false

    val activeStreamId: Int? get() = streamId

    suspend fun onStartStream(message: HubToMonitor.StartStream) = lock.withLock {
        if (streamId == message.streamId) return@withLock
        stopLocked()

        val id = message.streamId
        startedAtUs = nowUs()
        synchronized(mediaLock) {
            latestRotation = null
            streamId = id // set first, so the camera's first frame can prove it is running
        }

        val videoFormat = if (message.video) startVideo(id) else null
        val micCapturing = message.audio && audioAvailable()
        val audioFormat = if (micCapturing) AudioFormat(sampleRate = sampleRate, channels = 1) else null

        if (videoFormat == null && audioFormat == null) {
            streamId = null
            val reasons = buildList {
                if (message.video) add("the camera could not be started")
                if (message.audio) add("the microphone is not available")
            }
            output.sendControl(
                MonitorToHub.StreamStopped(
                    streamId = id,
                    reason = reasons.joinToString(" and ").ifEmpty { "neither video nor audio was requested" },
                ),
            )
            return@withLock
        }

        audioWanted = audioFormat != null
        synchronized(mediaLock) {
            announcedVideo = videoFormat?.let { it.copy(rotationDegrees = latestRotation ?: it.rotationDegrees) }
            announcedAudio = audioFormat
            announce(id)
            announced = true
        }
        onStreamingChanged(true)
    }

    /**
     * Starts the camera for [id] and waits for proof it runs: a first frame
     * within [FIRST_FRAME_TIMEOUT_MS]. A start that throws, hangs past
     * [VIDEO_START_TIMEOUT_MS], returns null, reports an error, or never
     * delivers a frame is handled the same way - the camera is stopped
     * best-effort and the caller falls back to audio-only (or declines). The
     * hub is owed a reply either way.
     *
     * A start cancelled from outside (the service being torn down) instead
     * cleans up in a non-cancellable section and rethrows, since there is no
     * longer anyone to hear a reply.
     */
    private suspend fun startVideo(id: Int): VideoFormat? {
        val generation = synchronized(mediaLock) { latestRotation = null; ++cameraGeneration }
        val proven = CompletableDeferred<Boolean>() // true: a frame arrived; false: the camera reported an error
        try {
            // withTimeoutOrNull consumes its own timeout; any CancellationException
            // that escapes it is this coroutine being cancelled.
            val format = withTimeoutOrNull(VIDEO_START_TIMEOUT_MS) {
                video.start(
                    onJpeg = { jpeg, rotation ->
                        synchronized(mediaLock) {
                            if (streamId == id && cameraGeneration == generation) {
                                proven.complete(true)
                                latestRotation = rotation
                                val format = announcedVideo
                                if (announced && format != null && format.rotationDegrees != rotation) {
                                    announcedVideo = format.copy(rotationDegrees = rotation)
                                    // The existing hub relays repeated stream_started as stream_ready.
                                    // Send the new rotation before the first frame using it.
                                    announce(id)
                                }
                                send(id, MediaKind.JPEG, jpeg)
                            }
                        }
                    },
                    onError = { synchronized(mediaLock) {
                        if (cameraGeneration == generation) proven.complete(false)
                    } },
                )
            }
            videoRunning = true // anything started must be stopped, even if it produced nothing
            val running = format != null &&
                (withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { proven.await() } ?: false)
            if (running) return format
            stopVideoQuietly()
            return null
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { video.stop() }
                videoRunning = false
                streamId = null
            }
            throw e
        } catch (e: Exception) {
            stopVideoQuietly()
            return null
        }
    }

    private suspend fun stopVideoQuietly() {
        synchronized(mediaLock) { cameraGeneration++ }
        withContext(NonCancellable) { runCatching { video.stop() } }
        videoRunning = false
    }

    private suspend fun selectFacing(facing: CameraFacing): Boolean = withTimeoutOrNull(VIDEO_START_TIMEOUT_MS) {
        try { video.selectFacing(facing) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    } == true

    /** A lens change keeps the stream, microphone and voice session alive. */
    suspend fun onSelectCamera(message: HubToMonitor.SelectCamera) = lock.withLock {
        val id = streamId
        val previous = announcedVideo?.facing
        fun reply(reason: String? = null) = output.sendControl(
            MonitorToHub.CameraResult(message.streamId, message.requestId, reason),
        )
        if (id != message.streamId || !announced || previous == null) {
            reply("Camera switching is unavailable for this live view")
            return@withLock
        }
        if (previous == message.facing) { reply(); return@withLock }
        val supported = selectFacing(message.facing)
        if (!supported) { reply("The requested camera is unavailable"); return@withLock }
        synchronized(mediaLock) {
            cameraGeneration++ // invalidate any queued callback from the old lens
            announcedVideo = null
            latestRotation = null
            announce(id)
        }
        stopVideoQuietly()
        var format = startVideo(id)
        val failed = format == null
        if (failed) {
            val restored = selectFacing(previous)
            if (restored) format = startVideo(id)
        }
        if (format == null && announcedAudio == null) {
            stopLocked()
            output.sendControl(MonitorToHub.StreamStopped(id, "Neither camera could be opened"))
            return@withLock
        }
        synchronized(mediaLock) {
            announcedVideo = format?.copy(rotationDegrees = latestRotation ?: format.rotationDegrees)
            announce(id)
        }
        reply(if (failed) {
            if (format != null) "Couldn't open that camera. The previous camera was restored."
            else "Couldn't open either camera. Retry video."
        } else null)
    }

    fun onAudioWindow(samples: ShortArray, count: Int) {
        val id = streamId ?: return
        if (!audioWanted || count <= 0) return
        send(id, MediaKind.PCM16, PcmFrames.littleEndian(samples, count))
    }

    suspend fun onStopStream(message: HubToMonitor.StopStream) = lock.withLock {
        if (streamId == message.streamId) stopLocked()
    }

    /** The hub already ended the stream when this socket closed; nothing to send. */
    suspend fun onDisconnected() = lock.withLock { stopLocked() }

    suspend fun shutdown() = lock.withLock {
        val id = streamId ?: return@withLock
        stopLocked()
        output.sendControl(MonitorToHub.StreamStopped(streamId = id, reason = "the monitor was stopped"))
    }

    private fun announce(id: Int) {
        output.sendControl(MonitorToHub.StreamStarted(id, announcedVideo, announcedAudio))
    }

    private fun send(id: Int, kind: MediaKind, payload: ByteArray) {
        synchronized(mediaLock) {
            if (streamId != id) return // a late frame from a stream that has ended
            if (!announced) return // the camera's proving frame, before stream_started
            if (kind == MediaKind.JPEG && announcedVideo == null) return
            output.sendMedia(MediaFrame(kind, id, (nowUs() - startedAtUs).coerceAtLeast(0), payload))
        }
    }

    private suspend fun stopLocked() {
        val wasStreaming = streamId != null
        synchronized(mediaLock) {
            cameraGeneration++
            streamId = null
            announced = false
            announcedVideo = null
            announcedAudio = null
        }
        audioWanted = false
        try {
            if (videoRunning) {
                videoRunning = false
                // Best-effort: a failing stop must not block a replacement start_stream
                // or leave the hub without a stream_stopped reply. Non-cancellable:
                // shutdown() runs while the service is torn down (and under a
                // timeout), and a cancelled stop would be swallowed here with the
                // camera still open.
                withContext(NonCancellable) { runCatching { video.stop() } }
            }
        } finally {
            if (wasStreaming) onStreamingChanged(false)
        }
    }
}
