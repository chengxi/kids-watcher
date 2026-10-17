package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.HubToParent
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

data class CameraUiState(val switching: Boolean = false, val error: String? = null)

/** Only explicit foreground taps switch cameras; stale replies cannot finish a newer request. */
class CameraController(
    private val live: StateFlow<LiveState>,
    replies: SharedFlow<HubToParent.CameraResult>,
    private val scope: CoroutineScope,
    private val send: (ParentToHub.SelectCamera) -> Boolean,
) {
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var pending: ParentToHub.SelectCamera? = null
    private var timer: Job? = null

    init {
        scope.launch {
            replies.collect { reply ->
                val request = pending ?: return@collect
                if (reply.streamId == request.streamId && reply.requestId == request.requestId) finish(reply.reason)
            }
        }
        scope.launch {
            live.collect { current ->
                val request = pending ?: return@collect
                if (current !is LiveState.Playing || current.streamId != request.streamId) finish()
            }
        }
    }

    fun visible(value: Boolean) { visible = value; if (!value) finish() }

    fun switch() {
        val playing = live.value as? LiveState.Playing ?: return
        val video = playing.video ?: return
        val target = when (video.facing) { CameraFacing.BACK -> CameraFacing.FRONT; CameraFacing.FRONT -> CameraFacing.BACK; null -> return }
        if (!visible || pending != null || target !in video.availableCameras) return
        val request = ParentToHub.SelectCamera(playing.streamId, UUID.randomUUID().toString(), target)
        pending = request
        mutableState.value = CameraUiState(switching = true)
        if (!send(request)) { finish("The hub is disconnected. Try again when the live view reconnects."); return }
        timer = scope.launch {
            delay(35_000)
            if (pending == request) finish("The monitor did not respond to the camera switch. Retry video.")
        }
    }

    private fun finish(error: String? = null) {
        timer?.cancel(); timer = null; pending = null
        mutableState.value = CameraUiState(error = error)
    }
}
