package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.ParentToHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class TalkUiState(val starting: Boolean = false, val active: Boolean = false, val error: String? = null)

interface TalkMicrophone {
    fun start(talkId: Int, send: (MediaFrame) -> Unit, onFailure: () -> Unit)
    fun release()
}

interface TalkGateway {
    fun send(message: ParentToHub)
    fun sendFrame(frame: MediaFrame): Boolean
}

/** Microphone starts only after the monitor confirms speaker readiness and never auto-resumes. */
class TalkController(
    private val live: StateFlow<LiveState>,
    replies: SharedFlow<HubToParent.TalkState>,
    private val scope: CoroutineScope,
    private val gateway: TalkGateway,
    private val openMicrophone: () -> TalkMicrophone,
    private val muteRoom: (Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(TalkUiState())
    val state = mutableState.asStateFlow()
    private var request: ParentToHub.StartTalk? = null
    private var microphone: TalkMicrophone? = null
    private var timeout: Job? = null
    private var visible = false

    init {
        scope.launch { replies.collect { onReply(it) } }
        scope.launch { live.collect { current ->
            synchronized(this@TalkController) {
                if (request != null && (current !is LiveState.Playing || current.streamId != request?.streamId))
                    stop("Live connection interrupted")
            }
        } }
    }

    @Synchronized fun visible(value: Boolean) {
        visible = value
        if (!value) stop()
    }

    @Synchronized fun start() {
        if (!visible || request != null) return
        val playing = live.value as? LiveState.Playing ?: return
        val attempt = ParentToHub.StartTalk(playing.streamId, UUID.randomUUID().toString())
        request = attempt
        mutableState.value = TalkUiState(starting = true)
        muteRoom(true)
        gateway.send(attempt)
        timeout = scope.launch { delay(7_000); synchronized(this@TalkController) {
            if (request == attempt && microphone == null) stop("Voice connection timed out. Check that the hub and monitor apps are updated.")
        } }
    }

    @Synchronized fun permissionDenied() { mutableState.value = TalkUiState(error = "Allow microphone access to talk to the monitor.") }

    @Synchronized fun onReply(reply: HubToParent.TalkState) {
        val attempt = request
        if (attempt == null || attempt.streamId != reply.streamId || attempt.requestId != reply.requestId) return
        val talkId = reply.talkId
        if (!reply.ready || talkId == null) { stop(reply.reason?.takeUnless { it == "Talk stopped" }); return }
        if (microphone != null || !visible) return
        timeout?.cancel()
        timeout = null
        try {
            val opened = openMicrophone()
            microphone = opened
            opened.start(talkId, send = { frame ->
                synchronized(this@TalkController) {
                    if (request == attempt && visible) gateway.sendFrame(frame)
                }
            }, onFailure = { scope.launch { synchronized(this@TalkController) {
                if (request == attempt) stop("Could not capture voice. Check microphone access and try again.")
            } } })
            mutableState.value = TalkUiState(active = true)
        } catch (error: Exception) {
            stop("Could not start the microphone. Check microphone access and try again.")
        }
    }

    @Synchronized fun stop(error: String? = null) {
        val attempt = request
        request = null
        timeout?.cancel()
        timeout = null
        val old = microphone
        microphone = null
        // Stop recording immediately. Release joins the capture thread off the UI thread.
        old?.release()
        if (attempt != null) gateway.send(ParentToHub.StopTalk(attempt.streamId, attempt.requestId))
        muteRoom(false)
        mutableState.value = TalkUiState(error = error)
    }
}
