package com.kidsmonitor.monitor.stream

object PcmFrames {
    /** The first [count] samples as PCM16 little-endian, the wire format of a PCM frame. */
    fun littleEndian(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
