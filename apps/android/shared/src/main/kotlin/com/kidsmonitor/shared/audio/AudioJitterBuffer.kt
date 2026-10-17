package com.kidsmonitor.shared.audio

/**
 * Smooths PCM arriving over wifi into something `AudioTrack` can play.
 *
 * Two opposing jobs: a short prebuffer absorbs uneven arrival, and a hard cap
 * keeps latency from growing over a long session. Without the cap, every
 * stall adds permanently to the delay between the room and the speaker.
 */
class AudioJitterBuffer(
    sampleRate: Int,
    channels: Int,
    prebufferMs: Int = 120,
    maxBufferedMs: Int = 400,
) {
    private val bytesPerMs = sampleRate * channels * 2 / 1000.0
    private val prebufferBytes = (prebufferMs * bytesPerMs).toInt()
    private val maxBytes = (maxBufferedMs * bytesPerMs).toInt()

    private val chunks = ArrayDeque<ByteArray>()
    private var buffered = 0
    private var primed = false

    var droppedBytes = 0L
        private set

    @Synchronized
    fun offer(pcm: ByteArray) {
        chunks.addLast(pcm)
        buffered += pcm.size
        while (buffered > maxBytes && chunks.size > 1) {
            val oldest = chunks.removeFirst()
            buffered -= oldest.size
            droppedBytes += oldest.size
        }
        if (buffered >= prebufferBytes) primed = true
    }

    @Synchronized
    fun poll(): ByteArray? {
        if (!primed) return null
        val next = chunks.removeFirstOrNull()
        if (next == null) {
            primed = false
            return null
        }
        buffered -= next.size
        return next
    }

    @Synchronized
    fun bufferedMs(): Int = (buffered / bytesPerMs).toInt()
}
