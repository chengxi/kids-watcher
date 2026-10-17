package com.kidsmonitor.parent.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Visible-activity-only PCM capture. Audio never goes to disk. */
class VoiceMicrophone : TalkMicrophone {
    private val record: AudioRecord
    private var echo: AcousticEchoCanceler? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "No usable microphone buffer" }
        record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 3200))
        } catch (denied: SecurityException) {
            // Permission can be revoked after the screen's check. Let the talk
            // controller report a failed start without beginning capture.
            throw IllegalStateException("Microphone permission is required to talk", denied)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Microphone did not initialize") }
        if (AcousticEchoCanceler.isAvailable()) echo = runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }.getOrNull()
    }

    override fun start(talkId: Int, send: (MediaFrame) -> Unit, onFailure: () -> Unit) {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        running = true
        val startUs = SystemClock.elapsedRealtimeNanos() / 1000
        thread = Thread({
            val samples = ShortArray(320)
            try {
                while (running) {
                    val count = record.read(samples, 0, samples.size)
                    if (!running) break
                    if (count <= 0) { if (count < 0) error("Microphone read failed"); continue }
                    val pcm = ByteArray(count * 2)
                    ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
                    send(MediaFrame(MediaKind.PCM16, talkId, SystemClock.elapsedRealtimeNanos() / 1000 - startUs, pcm))
                }
            } catch (error: Exception) { if (running) onFailure() }
        }, "parent-voice").apply { start() }
    }

    override fun release() {
        running = false
        runCatching { record.stop() }
        val capturing = thread
        Thread({
            capturing?.join(500)
            if (capturing?.isAlive != true) { echo?.release(); record.release() }
        }, "parent-voice-release").start()
        thread = null
    }
}
