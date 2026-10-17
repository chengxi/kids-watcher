package com.kidsmonitor.shared.audio

/**
 * The body of PcmPlayer's playback thread, apart from AudioTrack so its rules
 * are JVM-tested: write whatever the jitter buffer has, idle when it has
 * nothing, and stop for good when a write fails. A negative return from
 * AudioTrack.write means the track is unusable; writing again would only spin
 * on a dead native object.
 */
class PlaybackLoop(
    private val buffer: AudioJitterBuffer,
    private val write: (ByteArray) -> Int,
    private val idle: () -> Unit,
    private val onWriteError: (Int) -> Unit,
) {
    @Volatile var running = true

    fun run() {
        while (running) {
            val chunk = buffer.poll()
            if (chunk == null) {
                idle()
                continue
            }
            val written = write(chunk)
            if (written < 0) {
                running = false
                onWriteError(written)
            }
        }
    }
}
