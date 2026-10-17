package com.kidsmonitor.monitor.stream

import com.kidsmonitor.shared.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TalkReceiverTest {
    private class Speaker : TalkSpeaker {
        val frames = mutableListOf<ByteArray>()
        var released = false
        override fun offer(pcm: ByteArray) { frames += pcm }
        override fun release() { released = true }
    }
    @Test fun `only valid current PCM reaches speaker and stopped packets are discarded`() {
        val speaker = Speaker(); val sent = mutableListOf<MonitorToHub>()
        val receiver = TalkReceiver({ 1 }, { speaker }, { sent += it })
        receiver.start(HubToMonitor.StartTalk(1, 2))
        assertEquals(MonitorToHub.TalkStarted(2), sent.single())
        receiver.offer(MediaFrame(MediaKind.PCM16, 3, 0, byteArrayOf(0, 0)))
        receiver.offer(MediaFrame(MediaKind.JPEG, 2, 0, byteArrayOf(0, 0)))
        receiver.offer(MediaFrame(MediaKind.PCM16, 2, 0, byteArrayOf(0)))
        receiver.offer(MediaFrame(MediaKind.PCM16, 2, 0, ByteArray(3202)))
        assertTrue(speaker.frames.isEmpty())
        receiver.offer(MediaFrame(MediaKind.PCM16, 2, 0, byteArrayOf(0, 0)))
        assertEquals(1, speaker.frames.size)
        receiver.stop(onlyTalkId = 3)
        assertFalse(speaker.released)
        receiver.stop(onlyTalkId = 2)
        assertTrue(speaker.released)
        receiver.offer(MediaFrame(MediaKind.PCM16, 2, 0, byteArrayOf(0, 0)))
        assertEquals(1, speaker.frames.size)
    }

    @Test fun `silent voice connection releases speaker within three seconds`() {
        var now = 0L
        val speaker = Speaker(); val sent = mutableListOf<MonitorToHub>()
        val receiver = TalkReceiver({ 1 }, { speaker }, { sent += it }, nowMs = { now })
        receiver.start(HubToMonitor.StartTalk(1, 2))
        now = 3_001; receiver.sweep()
        assertTrue(speaker.released)
        assertEquals(MonitorToHub.TalkStopped(2, "Voice connection interrupted"), sent.last())
    }

    @Test fun `inactive stream or unavailable speaker never acknowledges readiness`() {
        val sent = mutableListOf<MonitorToHub>()
        var opens = 0
        val receiver = TalkReceiver({ 1 }, { opens++; error("speaker unavailable") }, { sent += it })
        receiver.start(HubToMonitor.StartTalk(3, 2))
        assertEquals(0, opens)
        receiver.start(HubToMonitor.StartTalk(1, 2))
        assertTrue(sent.all { it is MonitorToHub.TalkStopped })
    }
}
