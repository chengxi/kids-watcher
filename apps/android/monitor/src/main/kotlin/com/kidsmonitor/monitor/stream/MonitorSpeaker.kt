package com.kidsmonitor.monitor.stream

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.kidsmonitor.shared.audio.PcmPlayer
import com.kidsmonitor.shared.protocol.AudioFormat
import kotlin.math.ceil

/** Force the built-in loudspeaker and temporarily raise media volume to at least 80%. */
class MonitorSpeaker(context: Context, onFailure: () -> Unit) : TalkSpeaker {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val originalVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private val talkVolume = maxOf(originalVolume, ceil(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.8).toInt())
    private val player: PcmPlayer

    init {
        val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: error("No built-in speaker")
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, talkVolume, 0)
        try {
            player = PcmPlayer(AudioFormat(16_000, 1), speaker, onPlaybackError = { onFailure() })
            player.start()
        } catch (error: Exception) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            throw error
        }
    }

    override fun offer(pcm: ByteArray) = player.offer(pcm)

    override fun release() {
        try { player.release() } finally {
            // Preserve a volume adjustment the person made while speaking.
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == talkVolume)
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }
}
