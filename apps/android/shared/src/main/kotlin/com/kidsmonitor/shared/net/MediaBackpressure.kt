package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.MediaKind

/**
 * When to drop an outgoing media frame instead of queueing it.
 *
 * Live media is only useful while it is live: if the network cannot keep up,
 * queueing frames adds latency and memory without ever catching up. More
 * importantly, OkHttp *closes* a WebSocket whose outgoing queue exceeds 16 MiB,
 * and on the monitor that socket also carries alerts, so an unbounded send
 * would turn a slow parent into MONITOR_LOST.
 *
 * Video is dropped first (a stale picture is worthless); audio is kept longer
 * because a gap in sound is what a parent notices.
 */
object MediaBackpressure {
    const val MAX_QUEUED_VIDEO_BYTES: Long = 1L * 1024 * 1024
    const val MAX_QUEUED_AUDIO_BYTES: Long = 2L * 1024 * 1024

    fun shouldSend(kind: MediaKind, queuedBytes: Long): Boolean = when (kind) {
        MediaKind.JPEG -> queuedBytes <= MAX_QUEUED_VIDEO_BYTES
        MediaKind.PCM16 -> queuedBytes <= MAX_QUEUED_AUDIO_BYTES
    }
}
