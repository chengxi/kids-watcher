package com.kidsmonitor.monitor.stream

/** Admits at most one frame per [intervalUs]. The camera runs faster than we stream. */
class FrameThrottle(private val intervalUs: Long) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun admit(timestampUs: Long): Boolean {
        if (last != Long.MIN_VALUE && timestampUs - last < intervalUs) return false
        last = timestampUs
        return true
    }
}
