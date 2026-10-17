package com.kidsmonitor.monitor.stream

/**
 * Live video format. 640x480 at 10 fps was chosen for M4: clear enough to see a
 * child in a dim room, light enough for an old phone. Constants for now;
 * pushing them from the hub like the noise thresholds is a later increment.
 */
data class VideoSettings(
    val width: Int = 640,
    val height: Int = 480,
    val fps: Int = 10,
    val jpegQuality: Int = 60,
) {
    companion object {
        val DEFAULT = VideoSettings()
    }
}
