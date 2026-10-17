package com.kidsmonitor.parent.ui.live

import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.VideoFormat
import org.junit.Assert.*
import org.junit.Test

class LiveFeedHealthTest {
    @Test fun `green requires a picture and both advertised media formats on a connected hub`() {
        val playing = LiveState.Playing("room", 1, VideoFormat(640, 480, 10.0), AudioFormat(16_000, 1))
        assertTrue(liveFeedHealthy(playing, true, true))
        assertFalse(liveFeedHealthy(playing, false, true))
        assertFalse(liveFeedHealthy(playing, true, false))
        assertFalse(liveFeedHealthy(playing.copy(video = null), true, true))
        assertFalse(liveFeedHealthy(playing.copy(audio = null), true, true))
        assertFalse(liveFeedHealthy(LiveState.Ended("Disconnected"), true, true))
    }
}
