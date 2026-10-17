package com.kidsmonitor.parent.ui.live

import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.parent.live.RequestPhase
import com.kidsmonitor.parent.live.LiveFailure
import com.kidsmonitor.shared.net.ConnectionState
import org.junit.Assert.assertTrue
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextTest {

    private val video = VideoFormat(640, 480, 10.0)
    private val audio = AudioFormat(16_000, 1)

    @Test
    fun `says what is happening at each stage`() {
        assertEquals("Connecting…", liveStatusText(LiveState.Idle))
        assertEquals("Checking the connection to the hub… (attempt 1 of 3)", liveStatusText(LiveState.Requesting("m")))
        assertEquals("Live", liveStatusText(LiveState.Playing("m", 1, video, audio)))
        assertEquals("the monitor is offline", liveStatusText(LiveState.Ended("the monitor is offline")))
    }

    // The camera can fail while the microphone works (Task 9 falls back to
    // audio-only). The parent must be told why there is no picture, not left
    // staring at a blank box.
    @Test
    fun `says plainly when there is sound but no picture`() {
        assertEquals("Live · sound only, the camera is not available", liveStatusText(LiveState.Playing("m", 1, null, audio)))
    }

    // Final review I4: a monitor whose microphone is not capturing now streams
    // video with no audio. Plain "Live" over a silent speaker reads as a quiet
    // room, which is exactly what a baby monitor must never imply.
    @Test
    fun `says plainly when there is a picture but no sound`() {
        assertEquals(
            "Live · picture only, the microphone is not available",
            liveStatusText(LiveState.Playing("m", 1, video, null)),
        )
    }

    @Test
    fun `a quarter turn swaps width and height`() {
        assertEquals(640 to 480, rotatedSize(640, 480, 0))
        assertEquals(480 to 640, rotatedSize(640, 480, 90))
        assertEquals(640 to 480, rotatedSize(640, 480, 180))
        assertEquals(480 to 640, rotatedSize(640, 480, 270))
    }
    @Test
    fun `names only the stage for which there is evidence`() {
        assertTrue(liveStatusText(LiveState.WaitingForHub("m", ConnectionState.Reconnecting)).contains("hub over the network"))
        assertTrue(liveStatusText(LiveState.Requesting("m", phase = RequestPhase.MONITOR)).contains("Waiting for the monitor"))
        assertTrue(liveStatusText(LiveState.Requesting("m", phase = RequestPhase.VIDEO)).contains("Monitor responded"))
        assertTrue(liveStatusText(LiveState.Retrying("m", 2, 2000, LiveFailure.VIDEO_DELAY)).contains("Retrying automatically"))
        assertTrue(liveStatusText(LiveState.Playing("m", 1, video, audio), hasPicture = false).contains("first picture"))
    }

}
