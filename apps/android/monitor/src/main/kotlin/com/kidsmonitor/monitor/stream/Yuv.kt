package com.kidsmonitor.monitor.stream

import java.nio.ByteBuffer

object Yuv {
    /**
     * Converts CameraX's YUV_420_888 planes to NV21, which `YuvImage` can
     * compress to JPEG. Honours row and pixel strides: real devices pad rows and
     * expose U/V as interleaved views, and ignoring either shears or tints the
     * picture. [width] and [height] must be even. Absolute reads only, so the
     * camera's buffers are not disturbed.
     */
    fun toNv21(
        width: Int,
        height: Int,
        y: ByteBuffer,
        yRowStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): ByteArray {
        require(width % 2 == 0 && height % 2 == 0) { "NV21 needs even dimensions, got ${width}x$height" }
        val out = ByteArray(width * height * 3 / 2)
        var pos = 0
        for (row in 0 until height) {
            val base = row * yRowStride
            for (col in 0 until width) out[pos++] = y.get(base + col)
        }
        for (row in 0 until height / 2) {
            val base = row * uvRowStride
            for (col in 0 until width / 2) {
                val offset = base + col * uvPixelStride
                out[pos++] = v.get(offset)
                out[pos++] = u.get(offset)
            }
        }
        return out
    }
}
