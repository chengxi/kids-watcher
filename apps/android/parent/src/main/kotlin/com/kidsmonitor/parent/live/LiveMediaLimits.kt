package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.MAX_SAMPLE_RATE
import com.kidsmonitor.shared.protocol.MAX_VIDEO_DIMENSION
import com.kidsmonitor.shared.protocol.MIN_SAMPLE_RATE
import com.kidsmonitor.shared.protocol.VideoFormat

/**
 * What the parent is willing to decode and play.
 *
 * The live view runs in the same process as AlertService, so a stream that
 * makes it run out of memory or throw takes every alert down with it. A JPEG
 * of a few kilobytes can declare 30000x30000 pixels; its header is read first
 * and checked here before any bitmap is allocated.
 */
object LiveMediaLimits {
    /**
     * [width] x [height] come from the JPEG header. Either orientation is
     * accepted, up to twice the larger advertised dimension (a camera picks the
     * closest size it supports), and never beyond [MAX_VIDEO_DIMENSION].
     */
    fun decodable(width: Int, height: Int, advertised: VideoFormat?): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION) return false
        if (advertised == null) return true
        val limit = 2 * maxOf(advertised.width, advertised.height)
        return width <= limit && height <= limit
    }

    fun playable(format: AudioFormat): Boolean =
        format.sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE && format.channels in 1..2
}
