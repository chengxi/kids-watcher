package com.kidsmonitor.shared.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The binary media frame of spec §6.2. Kotlin twin of packages/protocol/src/binary.ts. */
const val FRAME_MAGIC: Byte = 0x4B
const val FRAME_HEADER_BYTES = 12

enum class MediaKind(val code: Byte) {
    JPEG(0x01),
    PCM16(0x02),
    ;

    companion object {
        fun of(code: Byte): MediaKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One media frame. [timestampUs] is microseconds since the stream started.
 * PCM16 payloads are little-endian; JPEG payloads are a complete JPEG file.
 */
class MediaFrame(
    val kind: MediaKind,
    val streamId: Int,
    val timestampUs: Long,
    val payload: ByteArray,
)

object MediaFrames {
    fun encode(frame: MediaFrame): ByteArray {
        require(frame.streamId in 0..0xFFFF) { "streamId out of uint16 range: ${frame.streamId}" }
        require(frame.timestampUs >= 0) { "timestampUs must not be negative: ${frame.timestampUs}" }
        val out = ByteArray(FRAME_HEADER_BYTES + frame.payload.size)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).apply {
            put(FRAME_MAGIC)
            put(frame.kind.code)
            putShort(frame.streamId.toShort())
            putLong(frame.timestampUs)
            put(frame.payload)
        }
        return out
    }

    fun decode(bytes: ByteArray): MediaFrame {
        if (bytes.size < FRAME_HEADER_BYTES) {
            throw ProtocolException("frame shorter than header: ${bytes.size} bytes")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.get()
        if (magic != FRAME_MAGIC) throw ProtocolException("bad magic byte: 0x%02x".format(magic))
        val code = buffer.get()
        val kind = MediaKind.of(code) ?: throw ProtocolException("unknown media kind: 0x%02x".format(code))
        val streamId = buffer.short.toInt() and 0xFFFF
        val timestampUs = buffer.long
        // uint64 on the wire; anything past 2^63 µs (292,000 years) is garbage.
        if (timestampUs < 0) throw ProtocolException("timestamp out of range")
        return MediaFrame(kind, streamId, timestampUs, bytes.copyOfRange(FRAME_HEADER_BYTES, bytes.size))
    }
}
