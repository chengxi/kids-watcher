package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraControllerTest {
    private fun playing(facing: CameraFacing = CameraFacing.BACK) = LiveState.Playing("m", 4,
        VideoFormat(640, 480, 10.0, facing = facing, availableCameras = CameraFacing.entries), AudioFormat(16_000, 1))

    @Test fun `explicit visible taps switch both ways and wait for correlated reply`() = runTest {
        val live = MutableStateFlow<LiveState>(playing())
        val replies = MutableSharedFlow<HubToParent.CameraResult>(extraBufferCapacity = 16)
        val sent = mutableListOf<ParentToHub.SelectCamera>()
        val camera = CameraController(live, replies, backgroundScope) { sent += it; true }
        runCurrent(); camera.switch(); assertTrue(sent.isEmpty())
        camera.visible(true); camera.switch(); camera.switch()
        assertEquals(1, sent.size); assertEquals(CameraFacing.FRONT, sent.single().facing)
        replies.emit(HubToParent.CameraResult(4, "stale")); runCurrent()
        assertTrue(camera.state.value.switching)
        live.value = playing(CameraFacing.FRONT); runCurrent()
        replies.emit(HubToParent.CameraResult(4, sent.single().requestId)); runCurrent()
        assertFalse(camera.state.value.switching)
        camera.switch(); assertEquals(CameraFacing.BACK, sent.last().facing)
        camera.visible(false)
    }

    @Test fun `leaving or disconnect cancels waiting and late replies cannot change a new request`() = runTest {
        val live = MutableStateFlow<LiveState>(playing())
        val replies = MutableSharedFlow<HubToParent.CameraResult>(extraBufferCapacity = 16)
        val sent = mutableListOf<ParentToHub.SelectCamera>()
        val camera = CameraController(live, replies, backgroundScope) { sent += it; true }
        runCurrent(); camera.visible(true); camera.switch()
        val old = sent.single()
        camera.visible(false); camera.visible(true); camera.switch()
        replies.emit(HubToParent.CameraResult(4, old.requestId, "old failure")); runCurrent()
        assertTrue(camera.state.value.switching)
        live.value = LiveState.Ended("disconnected"); runCurrent()
        assertFalse(camera.state.value.switching)
        live.value = playing(); runCurrent(); assertEquals(2, sent.size)
    }

    @Test fun `timeouts failed sends and missing camera support are handled`() = runTest {
        val live = MutableStateFlow<LiveState>(playing())
        val sent = mutableListOf<ParentToHub.SelectCamera>()
        var connected = true
        val camera = CameraController(live, MutableSharedFlow(), backgroundScope) { sent += it; connected }
        runCurrent(); camera.visible(true); camera.switch()
        advanceTimeBy(35_001); runCurrent()
        assertFalse(camera.state.value.switching); assertNotNull(camera.state.value.error)
        connected = false; camera.switch()
        assertFalse(camera.state.value.switching); assertTrue(camera.state.value.error!!.contains("disconnected"))
        live.value = playing().copy(video = VideoFormat(640, 480, 10.0)); runCurrent(); camera.switch()
        assertEquals(2, sent.size)
    }
}
