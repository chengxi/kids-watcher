package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where live PCM goes. [PcmPlayer] in the app; a fake in tests. */
interface AudioSink {
    fun offer(pcm: ByteArray)
    fun release()
}

/**
 * One screen's view of one monitor: whether the viewer wants to watch, which
 * session to drive, and the audio player and picture for the current stream.
 *
 * Pure Kotlin so the rules below are JVM-tested. Decoding and playback are
 * injected; the Android versions (BitmapFactory, AudioTrack) live in
 * LiveViewModel and are device-verified.
 *
 * - **Watching is an intent, not a call.** [start] records that the viewer
 *   wants the stream; any session that appears while that holds is started.
 *   The screen can come up before AlertService attaches its session (process
 *   death restoring the screen, a service recreate), and must not wait forever.
 * - **Nothing outlives [stop].** Audio or a picture already in flight on a
 *   worker thread must not open a player or repaint the cleared screen.
 * - **One player per stream.** It is released when the stream ends, and the
 *   next stream gets its own, built from that stream's format.
 * - **A stream cannot crash the process.** This process also hosts
 *   AlertService. A player that fails to build means no sound for that stream
 *   (tried once, not per frame); a picture that fails to decode is skipped.
 *   Sizes are bounded by [LiveMediaLimits].
 * - **Only this monitor's stream is stopped.** The session is shared by every
 *   live screen, so leaving a screen stops the session only if it is still on
 *   this screen's monitor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewer<F : Any>(
    private val monitorId: String,
    private val sessions: StateFlow<LiveSession?>,
    /** Pictures and sound on separate lossy flows, so video is dropped before audio. */
    private val video: SharedFlow<MediaFrame>,
    private val audio: SharedFlow<MediaFrame>,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher,
    /** Builds and starts a player. Called under this viewer's lock. */
    private val openAudio: (AudioFormat) -> AudioSink,
    /** Releases a player. May block (AudioTrack joins its thread), so production hands it off the main thread. */
    private val releaseAudio: (AudioSink) -> Unit,
    /** Decodes a picture for a stream that advertised [VideoFormat] (null if unknown); null to skip it. */
    private val decode: (ByteArray, VideoFormat?) -> F?,
    private val onAudioFailed: (Throwable) -> Unit = {},
    private val onDecodeFailed: (Throwable) -> Unit = {},
) {
    val state: StateFlow<LiveState> = sessions
        .flatMapLatest { it?.state ?: flowOf(LiveState.WaitingForHub(monitorId, ConnectionState.Connecting)) }
        .stateIn(scope, SharingStarted.Eagerly, LiveState.Idle)

    private val mutableFrame = MutableStateFlow<F?>(null)
    val frame: StateFlow<F?> = mutableFrame.asStateFlow()

    private val lock = Any()
    // All guarded by lock.
    private var wanted = false
    /** Bumped by every stop, so work begun before it can tell it is stale. */
    private var generation = 0
    private var jobs: List<Job> = emptyList()
    private var player: AudioSink? = null
    private var playerStreamId: Int? = null
    /** The stream whose player could not be built; not retried until the next stream. */
    private var audioFailedStreamId: Int? = null
    private val latestJpeg = MutableStateFlow<MediaFrame?>(null)
    private var renderedStreamId: Int? = null
    private var renderedFacing: CameraFacing? = null
    private var audioMuted = false

    fun muteAudio(muted: Boolean) {
        val released = synchronized(lock) {
            audioMuted = muted
            if (muted) takePlayerLocked() else null
        }
        released?.let(releaseAudio)
    }

    init {
        // Start whichever session shows up while the viewer wants to watch.
        // LiveSession.start is idempotent for the same monitor, so a session
        // start() already started is not asked twice.
        scope.launch {
            sessions.collect { session ->
                if (session != null && synchronized(lock) { wanted }) session.start(monitorId)
            }
        }
        // Release the player once its stream is over (ended, stopped, lost).
        scope.launch {
            state.collect { current ->
                synchronized(lock) {
                    if (current !is LiveState.Playing || current.video == null ||
                        (renderedStreamId != null && (renderedStreamId != current.streamId || renderedFacing != current.video.facing))) {
                        latestJpeg.value = null
                        mutableFrame.value = null
                        renderedStreamId = null
                    }
                }
                val streamId = (current as? LiveState.Playing)?.streamId
                val stale = synchronized(lock) {
                    if (player != null && playerStreamId != streamId) takePlayerLocked() else null
                }
                stale?.let(releaseAudio)
            }
        }
    }

    fun start() {
        val gen = synchronized(lock) {
            wanted = true
            if (jobs.isNotEmpty()) null else generation
        }
        sessions.value?.start(monitorId)
        if (gen == null) return

        val receiveVideo = scope.launch(workDispatcher) { video.collect { onMedia(it) } }
        val receiveAudio = scope.launch(workDispatcher) { audio.collect { onMedia(it) } }
        // Decode only the newest picture; if decoding falls behind, skip frames
        // rather than showing an ever-later one.
        val decoder = scope.launch(workDispatcher) {
            latestJpeg.filterNotNull().conflate().collect { media ->
                val session = sessions.value ?: return@collect
                val playing = session.state.value as? LiveState.Playing ?: return@collect
                if (playing.streamId != media.streamId || playing.monitorId != monitorId) return@collect
                val format = playing.video ?: return@collect
                val decoded = try {
                    decode(media.payload, format)
                } catch (e: Exception) {
                    onDecodeFailed(e)
                    null
                } ?: return@collect
                val accepted = synchronized(lock) {
                    val current = session.state.value as? LiveState.Playing
                    if (generation == gen && wanted && current?.streamId == media.streamId && current.video == format) {
                        renderedStreamId = media.streamId
                        renderedFacing = format.facing
                        mutableFrame.value = decoded
                        true
                    } else false
                }
                if (accepted) session.onPictureDecoded(media.streamId)
            }
        }
        val launched = listOf(receiveVideo, receiveAudio, decoder)
        synchronized(lock) {
            if (generation == gen) {
                jobs = launched
                return
            }
        }
        // stop() ran while the jobs were being launched.
        launched.forEach { it.cancel() }
    }

    fun stop() {
        val released: AudioSink?
        val cancelled: List<Job>
        synchronized(lock) {
            wanted = false
            generation++
            cancelled = jobs
            jobs = emptyList()
            released = takePlayerLocked()
            audioFailedStreamId = null
            latestJpeg.value = null
            mutableFrame.value = null
            renderedStreamId = null
        }
        // Outside the lock: LiveSession takes its own lock and calls the socket.
        sessions.value?.stop(monitorId)
        cancelled.forEach { it.cancel() }
        released?.let(releaseAudio)
    }

    internal fun onMedia(frame: MediaFrame) {
        var stale: AudioSink? = null
        synchronized(lock) {
            if (!wanted) return
            when (frame.kind) {
                MediaKind.JPEG -> latestJpeg.value = frame
                MediaKind.PCM16 -> {
                    if (audioMuted) return
                    val playing = state.value as? LiveState.Playing ?: return
                    val format = playing.audio ?: return
                    if (player != null && playerStreamId != playing.streamId) stale = takePlayerLocked()
                    val sink = player ?: openPlayerLocked(playing.streamId, format) ?: return
                    sink.offer(frame.payload)
                }
            }
        }
        stale?.let(releaseAudio)
    }

    private fun openPlayerLocked(streamId: Int, format: AudioFormat): AudioSink? {
        if (audioFailedStreamId == streamId) return null
        val opened = try {
            if (!LiveMediaLimits.playable(format)) throw IllegalArgumentException("unplayable audio format $format")
            openAudio(format)
        } catch (e: Exception) {
            audioFailedStreamId = streamId
            onAudioFailed(e)
            return null
        }
        player = opened
        playerStreamId = streamId
        return opened
    }

    private fun takePlayerLocked(): AudioSink? {
        val taken = player
        player = null
        playerStreamId = null
        return taken
    }
}
