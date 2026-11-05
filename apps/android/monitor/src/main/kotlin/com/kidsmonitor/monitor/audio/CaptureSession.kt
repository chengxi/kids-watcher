package com.kidsmonitor.monitor.audio

import android.media.AudioRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking PCM input. Implementations must let [stop] unblock an in-flight [read]. */
internal interface PcmSource {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

internal class AudioRecordSource(private val record: AudioRecord) : PcmSource {
    override fun start() = record.startRecording()
    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)
    override fun stop() = record.stop()
    override fun release() = record.release()
}

/** Owns one recorder from construction through exactly one release. */
internal class CaptureSession(
    private val sourceFactory: () -> PcmSource,
    private val bufferSize: Int,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onWindow: (ShortArray, Int) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onFailure: () -> Unit,
) {
    private val lifecycleLock = Any()
    private var source: PcmSource? = null
    private var starting = false
    private var stopCompletion: CompletableDeferred<Unit>? = null
    private var releaseClaimed = false
    @Volatile private var available = false
    private val closing = AtomicBoolean(false)
    private val completed = CompletableDeferred<Unit>()

    val isAvailable: Boolean get() = available

    suspend fun run() {
        var failed = false
        try {
            withContext(readDispatcher) {
                currentCoroutineContext().ensureActive()
                val created = sourceFactory()
                val startAllowed = synchronized(lifecycleLock) {
                    source = created
                    if (closing.get()) false else {
                        starting = true
                        true
                    }
                }
                if (!startAllowed) return@withContext
                try {
                    created.start()
                } catch (t: Throwable) {
                    failed = !closing.get()
                    throw t
                } finally {
                    synchronized(lifecycleLock) { starting = false }
                }
                if (closing.get()) return@withContext
                available = true
                withContext(callbackDispatcher) { if (!closing.get()) onStarted() }
                val buffer = ShortArray(bufferSize)
                var emptyReads = 0
                while (true) {
                    if (closing.get()) break
                    currentCoroutineContext().ensureActive()
                    val count = try {
                        created.read(buffer)
                    } catch (t: Throwable) {
                        if (closing.get() || t is CancellationException) break
                        failed = true
                        break
                    }
                    when {
                        count > 0 -> {
                            emptyReads = 0
                            if (!closing.get()) onWindow(buffer, count)
                        }
                        count < 0 -> {
                            if (!closing.get()) failed = true
                            break
                        }
                        else -> {
                            emptyReads++
                            if (emptyReads >= MAX_EMPTY_READS) {
                                if (!closing.get()) failed = true
                                break
                            }
                            // The bounded retry is for this blocking read API. Revisit if it becomes nonblocking.
                            delay(EMPTY_READ_DELAY_MS)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException && !closing.get()) failed = true
        } finally {
            available = false
            try {
                withContext(NonCancellable) { closeSource() }
                if (failed && !closing.get()) {
                    try {
                        withContext(callbackDispatcher) { if (!closing.get()) onFailure() }
                    } catch (_: CancellationException) {
                        // Cancellation during teardown is not a capture failure.
                    }
                }
            } finally {
                completed.complete(Unit)
            }
        }
    }

    /** Stop first to unblock read, then wait for it to finish before releasing. */
    suspend fun stop() {
        requestStop()
        completed.await()
    }

    /** Non-suspending half for Android onDestroy; never waits on the main thread. */
    fun requestStop() {
        closing.set(true)
        available = false
        stopSource()
    }

    private suspend fun closeSource() {
        stopSource()
        awaitStop()
        releaseSource()
    }

    private fun stopSource() {
        val work = synchronized(lifecycleLock) {
            val current = source ?: return
            if (starting || stopCompletion != null) return
            val done = CompletableDeferred<Unit>()
            stopCompletion = done
            current to done
        }
        runCatching { work.first.stop() }
        work.second.complete(Unit)
    }

    private suspend fun awaitStop() {
        val done = synchronized(lifecycleLock) { stopCompletion } ?: return
        done.await()
    }

    private fun releaseSource() {
        val current = synchronized(lifecycleLock) {
            if (releaseClaimed) return
            releaseClaimed = true
            source
        } ?: return
        runCatching { current.release() }
    }

    companion object {
        const val EMPTY_READ_DELAY_MS = 20L
        const val MAX_EMPTY_READS = 3
    }
}
