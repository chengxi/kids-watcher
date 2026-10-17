package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Received media, split by kind into two lossy broadcasts with their own
 * buffers.
 *
 * One buffer for both would drop the oldest frame of either kind, so a burst
 * of pictures while a reader is behind would push out sound. Separate buffers
 * make "drop video before audio" hold here as it does at the send-side hops
 * (MediaBackpressure, the hub's backpressure.ts). Keep the two flows separate
 * all the way to the reader: merging them again reintroduces one shared queue.
 *
 * Unlike a socket's events queue, each flow may have several collectors; a late
 * or slow one misses frames and never grows memory.
 */
class MediaFeed {
    private val mutableVideo = MutableSharedFlow<MediaFrame>(
        extraBufferCapacity = VIDEO_BUFFER_FRAMES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableAudio = MutableSharedFlow<MediaFrame>(
        extraBufferCapacity = AUDIO_BUFFER_FRAMES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val video: SharedFlow<MediaFrame> = mutableVideo.asSharedFlow()
    val audio: SharedFlow<MediaFrame> = mutableAudio.asSharedFlow()

    /** Never suspends; a full buffer drops its oldest frame of the same kind. */
    fun tryEmit(frame: MediaFrame): Boolean = when (frame.kind) {
        MediaKind.JPEG -> mutableVideo.tryEmit(frame)
        MediaKind.PCM16 -> mutableAudio.tryEmit(frame)
    }

    companion object {
        /** Under a second of 10 fps video: an older picture is not worth showing. */
        const val VIDEO_BUFFER_FRAMES = 8
        /** About 1.3 s of 20 ms audio windows. */
        const val AUDIO_BUFFER_FRAMES = 64
    }
}
