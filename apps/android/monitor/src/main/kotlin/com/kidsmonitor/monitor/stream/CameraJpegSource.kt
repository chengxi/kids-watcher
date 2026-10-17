package com.kidsmonitor.monitor.stream

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.kidsmonitor.shared.protocol.VideoFormat
import com.kidsmonitor.shared.protocol.CameraFacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "KidsMonitor"

/**
 * The selected front or back camera, as JPEG frames, bound from inside the foreground service.
 *
 * Device-only. The claim it rests on - that a `camera|microphone` service
 * started by a visible activity may open the camera later, with the screen
 * locked and no activity - is exactly the kind of thing that has been wrong
 * before. Task 11 verifies it on the Pixel 6.
 *
 * Main.immediate, not Main: MonitorService.onDestroy calls stop() through
 * runBlocking on the main thread, where a dispatched Main would deadlock.
 */
class CameraJpegSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val rotation: StateFlow<Int>,
    private val settings: VideoSettings = VideoSettings.DEFAULT,
) : VideoSource {

    private var facing = CameraFacing.BACK
    private fun selector(facing: CameraFacing) = when (facing) {
        CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    override suspend fun selectFacing(facing: CameraFacing): Boolean = withContext(Dispatchers.Main.immediate) {
        val available = ProcessCameraProvider.awaitInstance(context).hasCamera(selector(facing))
        if (available) this@CameraJpegSource.facing = facing
        available
    }

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var cameraState: LiveData<CameraState>? = null
    private var stateObserver: Observer<CameraState>? = null
    private var rotationJob: Job? = null

    /**
     * Never throws for a camera failure: returns null, and anything half-built is
     * released first. (StreamController also calls [stop] after a start that
     * returned null or timed out; releasing twice is safe.) Only the cancellation of this coroutine itself is
     * rethrown. A CancellationException while the coroutine is still active came
     * from CameraX cancelling its own future, which is a camera failure like any other.
     */
    /**
     * The wait for proof that the camera really opened (a first frame, or a
     * CameraState error first) and the timeout around this whole call are in
     * StreamController, where they are JVM-tested. What stays device-only for
     * Task 11: that a CameraState error does reach [onError] (e.g. another app
     * holding the camera), and that a camera lost mid-stream is logged.
     */
    override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat? = withContext(Dispatchers.Main.immediate) {
        try {
            val cameraProvider = ProcessCameraProvider.awaitInstance(context)
            val throttle = FrameThrottle(intervalUs = 1_000_000L / settings.fps)
            val worker = Executors.newSingleThreadExecutor { Thread(it, "live-camera") }
            // Recorded before the bind, so a bind that throws cannot orphan them.
            provider = cameraProvider
            executor = worker
            val useCase = ImageAnalysis.Builder()
                .setTargetRotation(rotation.value)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(settings.width, settings.height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis = useCase
            rotationJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                rotation.collect { useCase.targetRotation = it }
            }
            useCase.setAnalyzer(worker) { image ->
                image.use {
                    if (throttle.admit(it.imageInfo.timestamp / 1_000)) {
                        onJpeg(JpegEncoder.encode(it, settings.jpegQuality), it.imageInfo.rotationDegrees)
                    }
                }
            }
            val camera = cameraProvider.bindToLifecycle(owner, selector(facing), useCase)
            // Binding returns before the camera opens; failures to open arrive here.
            val observer = Observer<CameraState> { state ->
                val error = state.error ?: return@Observer
                Log.w(TAG, "camera state ${state.type} error ${error.code}", error.cause)
                onError("camera error ${error.code}")
            }
            camera.cameraInfo.cameraState.also {
                cameraState = it
                stateObserver = observer
                it.observe(owner, observer)
            }

            val info = useCase.resolutionInfo
            Log.i(TAG, "camera started: ${info?.resolution} rotation=${info?.rotationDegrees}")
            VideoFormat(
                width = info?.resolution?.width ?: settings.width,
                height = info?.resolution?.height ?: settings.height,
                fps = settings.fps.toDouble(),
                facing = facing,
                availableCameras = CameraFacing.entries.filter { cameraProvider.hasCamera(selector(it)) },
                rotationDegrees = info?.rotationDegrees ?: 0,
            )
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e // our own cancellation: the controller cleans up
            Log.w(TAG, "camera could not start: CameraX cancelled", e)
            release()
            null
        } catch (e: Exception) {
            Log.w(TAG, "camera could not start", e)
            release()
            null
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.Main.immediate) {
        release()
        Log.i(TAG, "camera stopped")
    }

    /** Main thread only. Safe on a partly built start and when nothing was started. */
    private fun release() {
        rotationJob?.cancel()
        rotationJob = null
        stateObserver?.let { observer -> cameraState?.removeObserver(observer) }
        stateObserver = null
        cameraState = null
        runCatching {
            analysis?.let { useCase ->
                useCase.clearAnalyzer()
                provider?.unbind(useCase)
            }
        }.onFailure { Log.w(TAG, "camera release failed", it) }
        analysis = null
        provider = null
        executor?.shutdown()
        executor = null
    }
}
