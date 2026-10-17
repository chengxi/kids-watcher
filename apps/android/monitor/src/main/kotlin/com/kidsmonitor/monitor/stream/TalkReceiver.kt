package com.kidsmonitor.monitor.stream

import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.MonitorToHub

interface TalkSpeaker {
    fun offer(pcm: ByteArray)
    fun release()
}

/** Serialized speaker ownership; stale packets and absent PCM never keep a speaker alive. */
class TalkReceiver(
    private val activeStream: () -> Int?,
    private val openSpeaker: () -> TalkSpeaker,
    private val send: (MonitorToHub) -> Unit,
    private val onActive: (Boolean) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var talkId: Int? = null
    private var streamId: Int? = null
    private var speaker: TalkSpeaker? = null
    private var lastFrameMs = 0L

    @Synchronized fun start(message: HubToMonitor.StartTalk) {
        if (activeStream() != message.streamId) {
            send(MonitorToHub.TalkStopped(message.talkId, "Live view is no longer active"))
            return
        }
        if (talkId == message.talkId) return
        stop("Talk replaced")
        try {
            speaker = openSpeaker()
            talkId = message.talkId
            streamId = message.streamId
            lastFrameMs = nowMs()
            onActive(true)
            send(MonitorToHub.TalkStarted(message.talkId))
        } catch (error: Exception) {
            send(MonitorToHub.TalkStopped(message.talkId, "Monitor speaker unavailable"))
            stop("Monitor speaker unavailable")
        }
    }

    @Synchronized fun offer(frame: MediaFrame) {
        if (frame.streamId != talkId || frame.kind != MediaKind.PCM16 ||
            frame.payload.isEmpty() || frame.payload.size > 3200 || frame.payload.size % 2 != 0) return
        lastFrameMs = nowMs()
        speaker?.offer(frame.payload)
    }

    @Synchronized fun sweep() {
        if (talkId != null && nowMs() - lastFrameMs > 3_000) stop("Voice connection interrupted")
    }

    @Synchronized fun stop(reason: String = "Talk stopped", onlyTalkId: Int? = null) {
        val id = talkId ?: return
        if (onlyTalkId != null && onlyTalkId != id) return
        talkId = null
        streamId = null
        val old = speaker
        speaker = null
        onActive(false)
        runCatching { old?.release() }
        send(MonitorToHub.TalkStopped(id, reason))
    }
}
