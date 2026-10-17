package com.kidsmonitor.monitor.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Device-only: YuvImage is a stub under Robolectric. The conversion it depends on is Yuv, which is tested. */
object JpegEncoder {
    fun encode(image: ImageProxy, quality: Int): ByteArray {
        val (y, u, v) = image.planes
        val width = image.width and 1.inv()
        val height = image.height and 1.inv()
        val nv21 = Yuv.toNv21(width, height, y.buffer, y.rowStride, u.buffer, v.buffer, u.rowStride, u.pixelStride)
        val out = ByteArrayOutputStream(64 * 1024)
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }
}
