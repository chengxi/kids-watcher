package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

const val START_TIMEOUT_MS = 15_000L
const val MAX_START_ATTEMPTS = 3
const val FIRST_PICTURE_TIMEOUT_MS = 12_000L

enum class RequestPhase { HUB, MONITOR, VIDEO }
enum class LiveFailure(val description: String) {
    HUB_UNREACHABLE("The hub did not respond. Check this phone's network and the home hub."),
    MONITOR_OFFLINE("The hub reports that the monitor is offline. Check the monitor phone and its network."),
    MONITOR_UNRESPONSIVE("The hub was reached, but the monitor did not respond."),
    VIDEO_DELAY("The monitor responded, but its video feed did not become available in time."),
    CAMERA_UNAVAILABLE("The monitor responded, but its camera is not available."),
    STREAM_INTERRUPTED("The live feed was interrupted."),
}

sealed interface LiveState {
    data object Idle : LiveState
    data class WaitingForHub(val monitorId: String, val connection: ConnectionState) : LiveState
    data class Requesting(
        val monitorId: String,
        val attempt: Int = 1,
        val phase: RequestPhase = RequestPhase.HUB,
        val streamId: Int? = null,
    ) : LiveState
    data class Retrying(val monitorId: String, val nextAttempt: Int, val delayMs: Long, val failure: LiveFailure) : LiveState
    data class Playing(
        val monitorId: String,
        val streamId: Int,
        val video: VideoFormat?,
        val audio: AudioFormat?,
    ) : LiveState
    data class Ended(val reason: String) : LiveState
}

interface StreamGateway {
    fun requestStream(monitorId: String)
    fun requestStream(monitorId: String, requestId: String) = requestStream(monitorId)
    fun cancelRequest(monitorId: String, requestId: String) {}
    fun stopStream(streamId: Int)
    /** Retire a socket that claimed to be connected but failed to answer a request. */
    fun reconnectHub() {}
}

/** Foreground-owned viewing intent survives recovery; stop cancels every retry and releases the camera. */
class LiveSession(
    private val gateway: StreamGateway,
    private val scope: CoroutineScope,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val maxAttempts: Int = MAX_START_ATTEMPTS,
    initialConnection: ConnectionState = ConnectionState.Connected,
) {
    private val mutableState = MutableStateFlow<LiveState>(LiveState.Idle)
    val state: StateFlow<LiveState> = mutableState.asStateFlow()
    private var timer: Job? = null
    private var wanted: String? = null
    private var attempts = 0
    private var requestId: String? = null
    private var connection = initialConnection
    // Legacy hubs do not echo request ids. Release their late ready response after leaving.
    private val abandoned = mutableSetOf<String>()
    private val retiredStreams = mutableSetOf<Int>()

    @Synchronized
    fun start(monitorId: String) {
        if (wanted == monitorId && mutableState.value !is LiveState.Ended) return
        stop()
        wanted = monitorId
        attempts = 0
        abandoned.remove(monitorId)
        beginAttempt()
    }

    private fun beginAttempt() {
        val monitorId = wanted ?: return
        cancelTimer()
        if (connection != ConnectionState.Connected) {
            mutableState.value = LiveState.WaitingForHub(monitorId, connection)
            return
        }
        attempts++
        val id = UUID.randomUUID().toString()
        requestId = id
        mutableState.value = LiveState.Requesting(monitorId, attempts)
        gateway.requestStream(monitorId, id)
        armStartTimeout(id)
    }

    private fun armStartTimeout(id: String) {
        cancelTimer()
        timer = scope.launch {
            delay(startTimeoutMs)
            synchronized(this@LiveSession) {
                val current = mutableState.value as? LiveState.Requesting ?: return@synchronized
                if (requestId != id) return@synchronized
                val failure = when (current.phase) {
                    RequestPhase.HUB -> LiveFailure.HUB_UNREACHABLE
                    RequestPhase.MONITOR -> LiveFailure.MONITOR_UNRESPONSIVE
                    RequestPhase.VIDEO -> LiveFailure.VIDEO_DELAY
                }
                retry(failure)
                if (failure == LiveFailure.HUB_UNREACHABLE) gateway.reconnectHub()
            }
        }
    }

    @Synchronized
    fun stop() {
        cancelTimer()
        releaseCurrent()
        wanted = null
        requestId = null
        mutableState.value = LiveState.Idle
    }

    @Synchronized
    fun stop(monitorId: String) {
        if (wanted == null || wanted == monitorId) stop()
    }

    private fun releaseCurrent() {
        when (val current = mutableState.value) {
            is LiveState.Requesting -> {
                abandoned += current.monitorId
                current.streamId?.let { retiredStreams += it }
                requestId?.let { gateway.cancelRequest(current.monitorId, it) }
            }
            is LiveState.Playing -> {
                retiredStreams += current.streamId
                gateway.stopStream(current.streamId)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun onConnectionChanged(state: ConnectionState) {
        connection = state
        val monitorId = wanted ?: return
        if (mutableState.value is LiveState.Ended) return
        if (state == ConnectionState.Connected) {
            if (mutableState.value is LiveState.WaitingForHub) {
                if (attempts >= maxAttempts) finish(LiveFailure.HUB_UNREACHABLE) else beginAttempt()
            }
        } else {
            val current = mutableState.value
            if (current is LiveState.Playing) attempts = 0 // a new outage after a working feed
            cancelTimer()
            requestId = null
            abandoned.clear() // the hub releases all viewers on socket closure
            retiredStreams.clear()
            mutableState.value = LiveState.WaitingForHub(monitorId, state)
        }
    }

    @Synchronized
    fun onDisconnected() = onConnectionChanged(ConnectionState.Reconnecting)

    private fun matches(id: String?): Boolean = id == null || id == requestId

    @Synchronized
    fun onStreamWaiting(msg: HubToParent.StreamWaiting) {
        val current = mutableState.value as? LiveState.Requesting ?: return
        if (current.monitorId != msg.monitorId || !matches(msg.requestId) || msg.streamId in retiredStreams) return
        val phase = when (msg.phase) {
            "contacting_monitor" -> RequestPhase.MONITOR
            "preparing_video" -> RequestPhase.VIDEO
            else -> return
        }
        // Repeated progress must not extend a stalled startup forever.
        val advanced = phase.ordinal > current.phase.ordinal
        mutableState.value = current.copy(phase = if (advanced) phase else current.phase, streamId = msg.streamId)
        if (advanced) requestId?.let(::armStartTimeout)
    }

    @Synchronized
    fun onStreamReady(msg: HubToParent.StreamReady) {
        val current = mutableState.value
        if (current is LiveState.Requesting && current.monitorId == msg.monitorId && matches(msg.requestId) && msg.streamId !in retiredStreams) {
            cancelTimer()
            abandoned.remove(msg.monitorId)
            mutableState.value = LiveState.Playing(msg.monitorId, msg.streamId, msg.video, msg.audio)
            if (msg.video == null && (attempts < maxAttempts || msg.audio == null)) {
                retry(LiveFailure.CAMERA_UNAVAILABLE)
            } else if (msg.video != null) {
                timer = scope.launch {
                    delay(FIRST_PICTURE_TIMEOUT_MS)
                    synchronized(this@LiveSession) {
                        val playing = mutableState.value as? LiveState.Playing
                        if (playing?.streamId == msg.streamId) retry(LiveFailure.VIDEO_DELAY)
                    }
                }
            }
        } else if (current is LiveState.Playing && current.monitorId == msg.monitorId && current.streamId == msg.streamId && matches(msg.requestId)) {
            mutableState.value = current.copy(video = msg.video, audio = msg.audio)
        } else if (msg.requestId == null && abandoned.remove(msg.monitorId)) {
            gateway.stopStream(msg.streamId)
        }
    }

    /** Receiving JPEG bytes is insufficient: the viewer must successfully decode a picture. */
    @Synchronized
    fun onPictureDecoded(streamId: Int) {
        val playing = mutableState.value as? LiveState.Playing ?: return
        if (playing.streamId != streamId || playing.video == null) return
        cancelTimer()
        attempts = 0
    }

    @Synchronized
    fun onStreamEnded(msg: HubToParent.StreamEnded) {
        val id = when (val current = mutableState.value) {
            is LiveState.Playing -> current.streamId
            is LiveState.Requesting -> current.streamId
            else -> null
        }
        if (id != msg.streamId) return
        val failure = if (mutableState.value is LiveState.Requesting) LiveFailure.CAMERA_UNAVAILABLE else LiveFailure.STREAM_INTERRUPTED
        retry(failure)
    }

    @Synchronized
    fun onError(msg: HubToParent.ErrorMessage) {
        val current = mutableState.value as? LiveState.Requesting ?: return
        if (!matches(msg.requestId) || (msg.monitorId != null && msg.monitorId != current.monitorId)) return
        if (msg.code == "monitor_offline") retry(LiveFailure.MONITOR_OFFLINE)
    }

    private fun retry(failure: LiveFailure) {
        val monitorId = wanted ?: return
        cancelTimer()
        releaseCurrent()
        requestId = null
        if (attempts >= maxAttempts) {
            finish(failure)
            return
        }
        val baseWaitMs = if (failure == LiveFailure.MONITOR_OFFLINE) 5_000L else 2_000L
        val waitMs = baseWaitMs * attempts.coerceAtLeast(1)
        mutableState.value = LiveState.Retrying(monitorId, attempts + 1, waitMs, failure)
        timer = scope.launch {
            delay(waitMs)
            synchronized(this@LiveSession) { if (wanted == monitorId) beginAttempt() }
        }
    }

    private fun finish(failure: LiveFailure) {
        cancelTimer()
        mutableState.value = LiveState.Ended("${failure.description} Automatic attempts exhausted; tap Try again.")
    }

    fun accepts(frame: MediaFrame): Boolean {
        val current = mutableState.value
        return current is LiveState.Playing && current.streamId == frame.streamId
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
