package com.kidsmonitor.monitor.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionTest {
    @Test
    fun `positive partial and full reads preserve counts before a terminal read`() = runTest {
        val source = ScriptedSource(mutableListOf(7, 320, -6))
        val windows = mutableListOf<Int>()
        var failures = 0
        val session = session(source, StandardTestDispatcher(testScheduler), windows::add) { failures++ }
        session.run()
        assertEquals(listOf(7, 320), windows)
        assertEquals(1, failures)
        assertFalse(session.isAvailable)
        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
        assertEquals(3, source.reads)
    }

    @Test
    fun `every negative read is terminal`() = runTest {
        listOf(-1, -6, -3).forEach { code ->
            val source = ScriptedSource(mutableListOf(code, 12))
            var failures = 0
            session(source, StandardTestDispatcher(testScheduler), {}, { failures++ }).run()
            assertEquals(1, source.reads)
            assertEquals(1, source.stops)
            assertEquals(1, source.releases)
            assertEquals(1, failures)
        }
    }

    @Test
    fun `three empty reads fail and a positive read resets the empty bound`() = runTest {
        val source = ScriptedSource(mutableListOf(0, 0, 0))
        var failures = 0
        session(source, StandardTestDispatcher(testScheduler), {}, { failures++ }).run()
        assertEquals(3, source.reads)
        assertEquals(1, failures)
        assertEquals(1, source.releases)
        assertEquals(40, testScheduler.currentTime)
    }

    @Test
    fun `positive read resets consecutive empty read count`() = runTest {
        val source = ScriptedSource(mutableListOf(0, 0, 4, 0, 0, 0))
        var failures = 0
        session(source, StandardTestDispatcher(testScheduler), {}, { failures++ }).run()
        assertEquals(6, source.reads)
        assertEquals(1, failures)
        assertEquals(80, testScheduler.currentTime)
    }

    @Test
    fun `read exception is terminal and stop throwing does not skip release`() = runTest {
        val source = ScriptedSource(mutableListOf()) { error("read failed") }
        source.throwOnStop = true
        var failures = 0
        session(source, StandardTestDispatcher(testScheduler), {}, { failures++ }).run()
        assertEquals(1, failures)
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
    }

    @Test
    fun `start exception is terminal and releases allocated source`() = runTest {
        val source = ScriptedSource(mutableListOf()).apply { throwOnStart = true }
        var failures = 0
        session(source, StandardTestDispatcher(testScheduler), {}, { failures++ }).run()
        assertEquals(1, failures)
        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
    }

    @Test
    fun `source construction exception is terminal`() = runTest {
        var failures = 0
        val session = CaptureSession(
            sourceFactory = { error("construction failed") }, bufferSize = 320,
            readDispatcher = StandardTestDispatcher(testScheduler),
            callbackDispatcher = StandardTestDispatcher(testScheduler),
            onWindow = { _, _ -> }, onFailure = { failures++ },
        )
        session.run()
        assertEquals(1, failures)
        assertFalse(session.isAvailable)
    }

    @Test
    fun `stop requested during source construction prevents start and releases after construction`() = runTest {
        val factoryEntered = java.util.concurrent.CountDownLatch(1)
        val finishFactory = java.util.concurrent.CountDownLatch(1)
        val source = ScriptedSource(mutableListOf())
        var failures = 0
        val session = CaptureSession(
            sourceFactory = {
                factoryEntered.countDown()
                finishFactory.await()
                source
            }, bufferSize = 320, readDispatcher = Dispatchers.IO,
            callbackDispatcher = Dispatchers.Unconfined, onWindow = { _, _ -> }, onFailure = { failures++ },
        )
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { session.run() }
        try {
            check(factoryEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            session.requestStop()
            finishFactory.countDown()
            job.join()
            assertEquals(0, source.starts)
            assertEquals(1, source.stops)
            assertEquals(1, source.releases)
            assertEquals(0, failures)
        } finally {
            finishFactory.countDown()
            session.requestStop()
            job.join()
        }
    }

    @Test
    fun `stop during start waits for start to finish before stopping the source`() = runTest {
        val source = StartBlockingSource()
        var failures = 0
        val session = CaptureSession(
            sourceFactory = { source }, bufferSize = 320, readDispatcher = Dispatchers.IO,
            callbackDispatcher = Dispatchers.Unconfined, onWindow = { _, _ -> }, onFailure = { failures++ },
        )
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { session.run() }
        try {
            check(source.awaitStart())
            session.requestStop()
            assertEquals(0, source.stops)
            source.finishStart()
            job.join()
            assertEquals(1, source.starts)
            assertEquals(1, source.stops)
            assertEquals(1, source.releases)
            assertEquals(0, failures)
        } finally {
            source.finishStart()
            session.requestStop()
            job.join()
        }
    }

    @Test
    fun `intentional stop does not report a read error`() = runTest {
        val source = BlockingSource()
        var failures = 0
        val session = CaptureSession(
            sourceFactory = { source }, bufferSize = 320,
            readDispatcher = Dispatchers.IO,
            callbackDispatcher = Dispatchers.Unconfined,
            onWindow = { _, _ -> }, onFailure = { failures++ },
        )
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { session.run() }
        try {
            source.awaitRead()
            val stopJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { session.stop() }
            source.awaitStopBlocked()
            source.awaitReadReturned()
            assertEquals(0, source.releases)
            source.finishStop()
            stopJob.join()
            job.join()
        } finally {
            source.finishStop()
            session.requestStop()
            job.join()
        }
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
        assertEquals(0, failures)
    }

    @Test
    fun `cancellation while stop is in progress still releases after stop and reports no failure`() = runTest {
        val source = BlockingSource()
        var failures = 0
        val session = CaptureSession(
            sourceFactory = { source }, bufferSize = 320,
            readDispatcher = Dispatchers.IO,
            callbackDispatcher = Dispatchers.Unconfined,
            onWindow = { _, _ -> }, onFailure = { failures++ },
        )
        val workerScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        val runJob = workerScope.launch { session.run() }
        var stopJob: kotlinx.coroutines.Job? = null
        try {
            source.awaitRead()
            stopJob = workerScope.launch { session.stop() }
            source.awaitStopBlocked()
            source.awaitReadReturned()
            runJob.cancel()
            source.finishStop()
            runJob.join()
            stopJob?.join()
            assertEquals(1, source.releases)
            assertEquals(0, failures)
        } finally {
            source.finishStop()
            session.requestStop()
            runJob.join()
            stopJob?.join()
        }
    }

    private fun session(
        source: PcmSource,
        dispatcher: CoroutineDispatcher,
        onWindow: (Int) -> Unit,
        onFailure: () -> Unit,
    ) = CaptureSession(
        sourceFactory = { source },
        bufferSize = 320,
        readDispatcher = dispatcher,
        callbackDispatcher = dispatcher,
        onWindow = { _, count -> onWindow(count) },
        onFailure = onFailure,
    )

    private class ScriptedSource(
        private val results: MutableList<Int>,
        private val readException: (() -> Nothing)? = null,
    ) : PcmSource {
        var starts = 0
        var reads = 0
        var stops = 0
        var releases = 0
        var throwOnStart = false
        var throwOnStop = false
        override fun start() { starts++; if (throwOnStart) error("start failed") }
        override fun read(buffer: ShortArray): Int {
            reads++
            readException?.invoke()
            return results.removeAt(0)
        }
        override fun stop() { stops++; if (throwOnStop) error("stop failed") }
        override fun release() { releases++ }
    }

    private class BlockingSource : PcmSource {
        var stops = 0
        var releases = 0
        private val monitor = Object()
        private val entered = java.util.concurrent.CountDownLatch(1)
        private val stopBlocked = java.util.concurrent.CountDownLatch(1)
        private val finishStop = java.util.concurrent.CountDownLatch(1)
        private val readReturned = java.util.concurrent.CountDownLatch(1)
        @Volatile private var inRead = false
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int = synchronized(monitor) {
            entered.countDown()
            inRead = true
            while (stops == 0) monitor.wait()
            inRead = false
            readReturned.countDown()
            -3
        }
        override fun stop() {
            synchronized(monitor) { stops++; monitor.notifyAll() }
            stopBlocked.countDown()
            finishStop.await()
        }
        override fun release() { check(!inRead); releases++ }
        fun awaitRead() { check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        fun awaitStopBlocked() { check(stopBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        fun awaitReadReturned() { check(readReturned.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        fun finishStop() { finishStop.countDown() }
    }

    private class StartBlockingSource : PcmSource {
        var starts = 0
        var stops = 0
        var releases = 0
        private val entered = java.util.concurrent.CountDownLatch(1)
        private val finish = java.util.concurrent.CountDownLatch(1)
        override fun start() { starts++; entered.countDown(); finish.await() }
        override fun read(buffer: ShortArray): Int = error("should not read after stop during start")
        override fun stop() { stops++ }
        override fun release() { releases++ }
        fun awaitStart() = entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
        fun finishStart() { finish.countDown() }
    }
}
