package com.kidsmonitor.parent.ui.live

import android.graphics.Bitmap
import android.content.ContentResolver
import android.os.SystemClock
import com.kidsmonitor.parent.live.LivePhotoStore
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsmonitor.parent.live.AudioSink
import com.kidsmonitor.parent.live.CameraController
import com.kidsmonitor.parent.live.LiveLink
import com.kidsmonitor.parent.live.LiveMediaLimits
import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.parent.live.LiveViewer
import com.kidsmonitor.parent.live.PcmPlayer
import com.kidsmonitor.parent.live.TalkController
import com.kidsmonitor.parent.live.TalkGateway
import com.kidsmonitor.parent.live.VoiceMicrophone
import com.kidsmonitor.shared.protocol.ParentToHub
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android half of one live view: JPEG decoding and AudioTrack playback.
 * Device-verified (Task 11): BitmapFactory and AudioTrack do nothing real
 * under Robolectric. The rules (start whichever session appears, nothing
 * outlives stop, one player per stream) are in [LiveViewer], which is
 * JVM-tested, and the stream state machine is LiveSession.
 */
class LiveViewModel(monitorId: String) : ViewModel() {

    private val viewer = LiveViewer(
        monitorId = monitorId,
        sessions = LiveLink.session,
        video = LiveLink.video,
        audio = LiveLink.audio,
        scope = viewModelScope,
        workDispatcher = Dispatchers.Default,
        openAudio = ::openPlayer,
        // PcmPlayer.release joins its playback thread for up to 500 ms; keep
        // that off the main thread. Not viewModelScope: it is already
        // cancelled by the time onCleared runs.
        releaseAudio = { sink -> Thread({ sink.release() }, "live-audio-release").start() },
        decode = ::decode,
        onAudioFailed = { Log.w(TAG, "live audio unavailable; showing the picture only", it) },
        onDecodeFailed = { Log.w(TAG, "could not decode a live picture", it) },
    )

    val state: StateFlow<LiveState> = viewer.state
    val frame: StateFlow<Bitmap?> = viewer.frame
    private val talk = TalkController(viewer.state, LiveLink.talkReplies, viewModelScope,
        object : TalkGateway {
            override fun send(message: ParentToHub) = LiveLink.sendTalk(message)
            override fun sendFrame(frame: MediaFrame) = LiveLink.sendTalkFrame(frame)
        }, ::VoiceMicrophone, viewer::muteAudio)
    private val camera = CameraController(viewer.state, LiveLink.cameraReplies, viewModelScope, LiveLink::selectCamera)
    val cameraState = camera.state
    fun switchCamera() = camera.switch()

    val talkState = talk.state
    private val mutablePhotoState = MutableStateFlow(PhotoUiState())
    val photoState = mutablePhotoState.asStateFlow()
    @Volatile private var lastDecodedAt = 0L

    fun savePhoto(resolver: ContentResolver, rotationDegrees: Float) {
        if (mutablePhotoState.value.saving) return
        val picture = frame.value
        val playing = state.value as? LiveState.Playing
        if (picture == null || playing?.video == null || SystemClock.elapsedRealtime() - lastDecodedAt > 5_000) {
            mutablePhotoState.value = PhotoUiState(message = "Wait for a fresh live picture, then try again.")
            return
        }
        mutablePhotoState.value = PhotoUiState(saving = true)
        viewModelScope.launch {
            // Once the parent explicitly taps the shutter, finish publishing or
            // removing the pending row even if they immediately navigate back.
            withContext(NonCancellable + Dispatchers.IO) {
                mutablePhotoState.value = try {
                    LivePhotoStore(resolver).save(picture, rotationDegrees)
                    PhotoUiState(message = "Photo saved to gallery · KidsMonitor album")
                } catch (error: Exception) {
                    Log.w(TAG, "could not save live photo", error)
                    PhotoUiState(message = "Couldn't save the photo. Check available storage and try again.")
                }
            }
        }
    }

    fun dismissPhotoMessage() { mutablePhotoState.value = mutablePhotoState.value.copy(message = null) }
    fun startTalking() = talk.start()
    fun stopTalking() = talk.stop()
    fun microphoneDenied() = talk.permissionDenied()

    fun start() { camera.visible(true); talk.visible(true); viewer.start() }

    fun stop() { camera.visible(false); talk.visible(false); viewer.stop() }

    fun retry() { camera.visible(false); camera.visible(true); talk.stop(); viewer.stop(); viewer.start() }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun openPlayer(format: AudioFormat): AudioSink {
        val player = PcmPlayer(format).also { it.start() }
        return object : AudioSink {
            override fun offer(pcm: ByteArray) = player.offer(pcm)
            override fun release() = player.release()
        }
    }

    /**
     * Reads the JPEG header first and refuses a picture far larger than the
     * stream advertised (LiveMediaLimits) before any pixels are allocated.
     * Device-verified in Task 11: BitmapFactory decodes nothing under Robolectric.
     */
    private fun decode(bytes: ByteArray, video: VideoFormat?): Bitmap? {
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, header)
        if (!LiveMediaLimits.decodable(header.outWidth, header.outHeight, video)) {
            Log.w(TAG, "refusing a ${header.outWidth}x${header.outHeight} picture for a stream of ${video?.width}x${video?.height}")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotationDegrees = video?.rotationDegrees ?: 0
        lastDecodedAt = SystemClock.elapsedRealtime()
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val TAG = "KidsMonitor"
    }
}

data class PhotoUiState(val saving: Boolean = false, val message: String? = null)
