package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.net.MediaFeed
import com.kidsmonitor.shared.net.HubSocket
import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.ParentToHub
import kotlinx.coroutines.flow.MutableSharedFlow
import com.kidsmonitor.shared.protocol.MediaFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the live-view screen reaches the alert service's socket.
 *
 * There is exactly one socket per parent device - the hub closes an older
 * connection when a newer one arrives - and it belongs to AlertService, which
 * is also the only consumer of its event queue. So the service owns the
 * session and routes stream messages into it; the screen only ever sees the
 * session and already-filtered media through here.
 */
object LiveLink {
    private val mutableSession = MutableStateFlow<LiveSession?>(null)
    val session: StateFlow<LiveSession?> = mutableSession.asStateFlow()

    /**
     * Frames for the stream being played, split by kind so a picture backlog
     * never pushes out sound. Lossy broadcasts; several collectors are fine.
     */
    internal val media = MediaFeed()
    val video: SharedFlow<MediaFrame> get() = media.video
    val audio: SharedFlow<MediaFrame> get() = media.audio
    internal val cameraReplies = MutableSharedFlow<HubToParent.CameraResult>(extraBufferCapacity = 16)
    fun selectCamera(message: ParentToHub.SelectCamera): Boolean {
        val current = socket ?: return false
        if (!current.isConnected.value) return false
        current.send(message)
        return true
    }
    internal val talkReplies = MutableSharedFlow<HubToParent.TalkState>(extraBufferCapacity = 16)
    @Volatile private var socket: HubSocket? = null

    fun sendTalk(message: ParentToHub) { socket?.send(message) }
    fun sendTalkFrame(frame: MediaFrame): Boolean = socket?.sendMedia(frame) ?: false

    internal fun attach(session: LiveSession, socket: HubSocket? = null) {
        this.socket = socket
        mutableSession.value = session
    }

    internal fun detach() {
        socket = null
        mutableSession.value?.stop()
        mutableSession.value = null
    }
}
