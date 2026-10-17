package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaFeedTest {

    private fun jpeg(n: Int) = MediaFrame(MediaKind.JPEG, 1, n.toLong(), ByteArray(1))
    private fun pcm(n: Int) = MediaFrame(MediaKind.PCM16, 1, n.toLong(), ByteArray(2))

    /**
     * A reader that has fallen behind: it takes one frame from each flow and
     * then waits on [gate] before taking any more, so later frames pile up in
     * the feed's buffers.
     */
    private class StalledReader {
        val gate = CompletableDeferred<Unit>()
        val video = mutableListOf<MediaFrame>()
        val audio = mutableListOf<MediaFrame>()
    }

    // Global Constraint: drop video before audio at every hop. A burst of
    // pictures while the reader is behind must not push sound out of the buffer.
    @Test
    fun `a burst of video does not drop audio waiting for a slow reader`() = runTest {
        val feed = MediaFeed()
        val reader = StalledReader()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) {
            feed.video.collect { reader.video += it; reader.gate.await() }
        }
        backgroundScope.launch(dispatcher) {
            feed.audio.collect { reader.audio += it; reader.gate.await() }
        }
        runCurrent()

        feed.tryEmit(jpeg(0)); feed.tryEmit(pcm(0)) // each reader now holds one and waits
        feed.tryEmit(pcm(1))
        repeat(200) { feed.tryEmit(jpeg(it + 1)) }
        reader.gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(0L, 1L), reader.audio.map { it.timestampUs })
        assertTrue("video was bounded, got ${reader.video.size}", reader.video.size <= 1 + MediaFeed.VIDEO_BUFFER_FRAMES)
    }

    @Test
    fun `each kind arrives on its own flow`() = runTest {
        val feed = MediaFeed()
        val video = mutableListOf<MediaFrame>()
        val audio = mutableListOf<MediaFrame>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { feed.video.collect { video += it } }
        backgroundScope.launch(dispatcher) { feed.audio.collect { audio += it } }
        runCurrent()

        feed.tryEmit(jpeg(1)); feed.tryEmit(pcm(2))

        assertEquals(listOf(MediaKind.JPEG), video.map { it.kind })
        assertEquals(listOf(MediaKind.PCM16), audio.map { it.kind })
    }
}
