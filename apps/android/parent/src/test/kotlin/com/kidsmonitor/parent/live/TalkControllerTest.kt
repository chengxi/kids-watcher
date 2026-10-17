package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TalkControllerTest {
    private class Mic : TalkMicrophone {
        var starts = 0
        var releases = 0
        var emit: ((MediaFrame) -> Unit)? = null
        var fail: (() -> Unit)? = null
        override fun start(talkId: Int, send: (MediaFrame) -> Unit, onFailure: () -> Unit) { starts++; emit = send; fail = onFailure }
        override fun release() { releases++ }
    }
    private class Gateway : TalkGateway {
        val controls = mutableListOf<ParentToHub>()
        val frames = mutableListOf<MediaFrame>()
        override fun send(message: ParentToHub) { controls += message }
        override fun sendFrame(frame: MediaFrame): Boolean { frames += frame; return true }
    }

    @Test fun `microphone waits for speaker acknowledgement and releases when screen leaves`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, AudioFormat(16_000, 1)))
        val gateway = Gateway()
        val mic = Mic()
        val muted = mutableListOf<Boolean>()
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { mic }, { muted += it })
        runCurrent()
        talk.visible(true); talk.start()
        val request = gateway.controls.single() as ParentToHub.StartTalk
        assertEquals(0, mic.starts)
        assertTrue(talk.state.value.starting)
        talk.onReply(HubToParent.TalkState(1, request.requestId, 2, true, null))
        assertEquals(1, mic.starts)
        assertTrue(talk.state.value.active)
        val frame = MediaFrame(MediaKind.PCM16, 2, 0, byteArrayOf(0, 0))
        mic.emit!!(frame)
        assertEquals(1, gateway.frames.size)
        talk.visible(false)
        mic.emit!!(frame)
        assertEquals(1, gateway.frames.size)
        assertEquals(1, mic.releases)
        assertEquals(listOf(true, false), muted)
        assertTrue(gateway.controls.last() is ParentToHub.StopTalk)
    }

    @Test fun `cancelling startup ignores a late ready and stale replies cannot enable another attempt`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, null))
        val gateway = Gateway(); val mic = Mic()
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { mic }, {})
        runCurrent(); talk.visible(true); talk.start()
        val old = gateway.controls.last() as ParentToHub.StartTalk
        talk.stop(); talk.start()
        val current = gateway.controls.last() as ParentToHub.StartTalk
        talk.onReply(HubToParent.TalkState(1, old.requestId, 2, true, null))
        assertEquals(0, mic.starts)
        talk.onReply(HubToParent.TalkState(1, current.requestId, 3, true, null))
        assertEquals(1, mic.starts)
        talk.stop()
    }

    @Test fun `disconnect stops recording and reconnection does not resume voice`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, null))
        val gateway = Gateway(); val mic = Mic()
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { mic }, {})
        runCurrent(); talk.visible(true); talk.start()
        val request = gateway.controls.last() as ParentToHub.StartTalk
        talk.onReply(HubToParent.TalkState(1, request.requestId, 2, true, null))
        live.value = LiveState.Ended("Hub disconnected"); runCurrent()
        assertEquals(1, mic.releases)
        assertFalse(talk.state.value.active)
        live.value = LiveState.Playing("m1", 3, null, null); runCurrent()
        assertEquals(1, mic.starts)
    }

    @Test fun `missing speaker response times out without opening microphone`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, null))
        val gateway = Gateway(); val mic = Mic()
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { mic }, {})
        runCurrent(); talk.visible(true); talk.start()
        advanceTimeBy(7_001); runCurrent()
        assertEquals(0, mic.starts)
        assertFalse(talk.state.value.starting)
        assertNotNull(talk.state.value.error)
        assertTrue(gateway.controls.last() is ParentToHub.StopTalk)
    }

    @Test fun `microphone permission or startup failure cancels speaker and restores room audio`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, null))
        val gateway = Gateway(); val muted = mutableListOf<Boolean>(); var releases = 0
        val denied = object : TalkMicrophone {
            override fun start(talkId: Int, send: (MediaFrame) -> Unit, onFailure: () -> Unit) { throw SecurityException("Microphone denied") }
            override fun release() { releases++ }
        }
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { denied }, { muted += it })
        runCurrent(); talk.visible(true); talk.start()
        val request = gateway.controls.last() as ParentToHub.StartTalk
        talk.onReply(HubToParent.TalkState(1, request.requestId, 2, true, null))
        assertEquals(1, releases)
        assertFalse(talk.state.value.active)
        assertNotNull(talk.state.value.error)
        assertEquals(listOf(true, false), muted)
        assertTrue(gateway.controls.last() is ParentToHub.StopTalk)
    }

    @Test fun `capture failure stops voice and late failure cannot cancel a new attempt`() = runTest {
        val live = MutableStateFlow<LiveState>(LiveState.Playing("m1", 1, null, null))
        val gateway = Gateway(); val first = Mic(); val next = Mic(); var opened = 0
        val talk = TalkController(live, MutableSharedFlow(), backgroundScope, gateway, { if (opened++ == 0) first else next }, {})
        runCurrent(); talk.visible(true); talk.start()
        val request = gateway.controls.last() as ParentToHub.StartTalk
        talk.onReply(HubToParent.TalkState(1, request.requestId, 2, true, null))
        first.fail!!(); runCurrent()
        assertEquals(1, first.releases)
        assertNotNull(talk.state.value.error)
        talk.start()
        val retry = gateway.controls.last() as ParentToHub.StartTalk
        talk.onReply(HubToParent.TalkState(1, retry.requestId, 3, true, null))
        first.fail!!(); runCurrent()
        assertTrue(talk.state.value.active)
        assertEquals(0, next.releases)
        talk.stop()
    }
}
