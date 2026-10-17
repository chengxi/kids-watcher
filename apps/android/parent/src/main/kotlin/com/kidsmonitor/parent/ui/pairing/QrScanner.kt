package com.kidsmonitor.parent.ui.pairing

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live camera preview that reports any QR code it sees.
 *
 * The camera is bound in a [LaunchedEffect] keyed on the lifecycle, not inside
 * `AndroidView(factory = ...)`. `factory` runs exactly once, so a binding made
 * there is never re-established after the screen locks: CameraX unbinds on
 * STOP, the PreviewView's surface is abandoned, and the user comes back to a
 * black rectangle with no error anywhere.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(modifier: Modifier = Modifier, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    // Rebinds whenever the screen comes back, which is what makes this survive
    // a lock/unlock cycle.
    LaunchedEffect(lifecycleOwner, previewView) {
        lifecycleOwner.lifecycle.currentStateFlow.collect { state ->
            if (!state.isAtLeast(Lifecycle.State.RESUMED)) return@collect
            val provider = awaitCameraProvider(context)
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(executor) { proxy ->
                        val image = proxy.image
                        if (image == null) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        scanner.process(
                            InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees),
                        ).addOnSuccessListener { barcodes ->
                            val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                            val value = qr?.rawValue
                            if (value != null) {
                                // Distinguishes "the camera never saw a code"
                                // from "it saw one and the app rejected it".
                                Log.i("KidsMonitor", "barcode detected, ${value.length} chars")
                                onScanned(value)
                            }
                        }.addOnFailureListener { e ->
                            Log.w("KidsMonitor", "barcode scan failed", e)
                        }.addOnCompleteListener { proxy.close() }
                    }
                }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                Log.i("KidsMonitor", "camera bound for scanning")
            }.onFailure { Log.w("KidsMonitor", "could not bind the camera", it) }
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}

/** ListenableFuture bridge; `awaitInstance` is not in this CameraX version. */
private suspend fun awaitCameraProvider(
    context: android.content.Context,
): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        {
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}
