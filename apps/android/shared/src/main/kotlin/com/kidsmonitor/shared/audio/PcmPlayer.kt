package com.kidsmonitor.shared.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import android.util.Log
import com.kidsmonitor.shared.protocol.AudioFormat

private const val TAG = "KidsMonitor"

/**
 * Plays live PCM16 (little-endian, as on the wire) through `AudioTrack`.
 *
 * Device-only: Robolectric's AudioTrack plays nothing, so a test would only
 * prove the shadow. The logic worth testing lives in [AudioJitterBuffer] and
 * [PlaybackLoop]; this is verified by ear in Task 11, including leaving the
 * screen mid-sentence (release while a write is in progress).
 *
 * The constructor and [start] may throw (AudioTrack init or play failing);
 * LiveViewer treats that as "no sound for this stream" and keeps the picture.
 */
class PcmPlayer(
    format: AudioFormat,
    private val speaker: android.media.AudioDeviceInfo? = null,
    private val onPlaybackError: (Int) -> Unit = {},
) {
    private val buffer = AudioJitterBuffer(format.sampleRate, format.channels)

    private val track: AudioTrack = run {
        val channelMask = if (format.channels == 2) {
            android.media.AudioFormat.CHANNEL_OUT_STEREO
        } else {
            android.media.AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate, channelMask, android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(format.sampleRate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, format.sampleRate * format.channels * 2 / 10))
            .build()
    }

    private var loop: PlaybackLoop? = null
    private var thread: Thread? = null
    private var routeLogged = false

    fun start() {
        if (loop != null) return
        try {
            if (speaker != null) check(track.setPreferredDevice(speaker)) { "Could not route audio to the built-in speaker" }
            track.play()
        } catch (e: Exception) {
            track.release() // nobody will call release() on a player that never started
            throw e
        }
        val playback = PlaybackLoop(
            buffer = buffer,
            write = { chunk ->
                val written = track.write(chunk, 0, chunk.size)
                if (speaker != null && written > 0) {
                    val routed = track.routedDevice
                    if (routed != null && routed.id != speaker.id) {
                        Log.w(TAG, "Voice playback routed away from the built-in speaker")
                        AudioTrack.ERROR_INVALID_OPERATION
                    } else {
                        if (routed != null && !routeLogged) {
                            routeLogged = true
                            Log.i(TAG, "Voice playback speaker route=${routed.type}; PCM bytes=$written")
                        }
                        written
                    }
                } else written
            },
            idle = { Thread.sleep(5) },
            onWriteError = { code -> Log.w(TAG, "AudioTrack.write returned $code; live audio stopped"); onPlaybackError(code) },
        )
        loop = playback
        thread = Thread(playback::run, "live-audio").apply { start() }
    }

    fun offer(pcm: ByteArray) = buffer.offer(pcm)

    /**
     * The playback thread may be blocked inside `track.write`. Releasing the
     * native track under it is a use-after-free crash, so: stop the loop, pause
     * and flush (which returns a blocked write early), wait for the thread, and
     * only then release. If the thread still has not exited, the track is left
     * to its finalizer rather than freed under a running write.
     */
    fun release() {
        loop?.running = false
        runCatching {
            track.pause()
            track.flush()
        }
        val playing = thread
        playing?.join(500)
        if (playing?.isAlive == true) {
            Log.w(TAG, "live audio thread did not exit; not releasing its AudioTrack")
            return
        }
        thread = null
        loop = null
        runCatching { track.stop() }
        track.release()
    }
}
