package com.kidsmonitor.shared.protocol

import org.junit.Test
import java.io.File

/**
 * Not an assertion test: it writes every ParentToHub encoding to a file so a
 * Node script can validate them against the hub's own zod schemas. Kotlin and
 * TypeScript agreeing is the one thing neither language's tests can prove alone.
 */
class EncodingDumpTest {

    @Test
    fun `dump every parent-to-hub encoding for cross-language validation`() {
        val messages = listOf<ParentToHub>(
            ParentToHub.Hello(deviceName = "Test Phone", appVersion = "0.1.0"),
            ParentToHub.Subscribe(monitorIds = null),
            ParentToHub.Subscribe(monitorIds = listOf("m1", "m2")),
            ParentToHub.RequestStream(monitorId = "m1", video = true, audio = true),
            ParentToHub.RequestStream("m1", true, true, "request-1"),
            ParentToHub.CancelStreamRequest("m1", "request-1"),
            ParentToHub.StopStream(streamId = 1),
            ParentToHub.StartTalk(1, "talk-1"),
            ParentToHub.StopTalk(1, "talk-1"),
            ParentToHub.SelectCamera(1, "camera-front", CameraFacing.FRONT),
            ParentToHub.SelectCamera(1, "camera-back", CameraFacing.BACK),
            ParentToHub.AckEvent(eventId = 42),
            ParentToHub.Heartbeat,
        )
        val out = File(System.getProperty("java.io.tmpdir"), "kotlin-encodings.jsonl")
        out.writeText(messages.joinToString("\n") { ProtocolJson.encode(it) })
        println("wrote ${messages.size} encodings to ${out.absolutePath}")
    }

    @Test
    fun `dump every monitor-to-hub encoding for cross-language validation`() {
        val messages = listOf<MonitorToHub>(
            MonitorToHub.Hello(deviceName = "Nursery Pixel", appVersion = "0.1.0"),
            MonitorToHub.NoiseLevel(NoiseSample(1757000000000, 1757000015000, -48.0, -25.0, -38.0)),
            MonitorToHub.NoiseLevel(NoiseSample(1757000015000, 1757000030000, -50.0, -40.0, null)),
            MonitorToHub.Event(
                clientEventId = "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
                type = EventType.LOUD_NOISE,
                tsMs = 1757000000000,
                metadata = EventMetadata(peakDb = -9.5, durationMs = 2200),
            ),
            MonitorToHub.Event(
                clientEventId = "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4e",
                type = EventType.MONITOR_STARTED,
                tsMs = 1757000000000,
            ),
            MonitorToHub.StreamPreparing(1),
            MonitorToHub.CameraResult(1, "camera-front"),
            MonitorToHub.CameraResult(1, "camera-back", "Camera unavailable"),
            MonitorToHub.TalkStarted(2),
            MonitorToHub.TalkStopped(2, "Talk stopped"),
            MonitorToHub.StreamStarted(
                streamId = 1,
                video = VideoFormat(width = 640, height = 480, fps = 10.0, rotationDegrees = 90, facing = CameraFacing.FRONT, availableCameras = CameraFacing.entries),
                audio = AudioFormat(sampleRate = 16000, channels = 1),
            ),
            MonitorToHub.StreamStarted(streamId = 2, video = null, audio = null),
            MonitorToHub.StreamStopped(streamId = 1, reason = "viewer left"),
            MonitorToHub.CalibrationResult(noiseFloorDb = -57.5, spreadDb = 3.2, sampledMs = 30000),
            MonitorToHub.Heartbeat(batteryPct = 88, thermal = "none"),
            MonitorToHub.Heartbeat(88, "none", charging = true, batteryTemperatureC = 32.5, speakerVolumePct = 80),
            MonitorToHub.Heartbeat(batteryPct = null, thermal = "unknown"),
        )
        val out = File(System.getProperty("java.io.tmpdir"), "kotlin-monitor-encodings.jsonl")
        out.writeText(messages.joinToString("\n") { ProtocolJson.encodeMonitor(it) })
        println("wrote ${messages.size} monitor encodings to ${out.absolutePath}")
    }

    @Test
    fun `dump media frames for cross-language validation`() {
        val frames = listOf(
            MediaFrame(MediaKind.JPEG, streamId = 258, timestampUs = 1_234_567, payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte())),
            MediaFrame(MediaKind.PCM16, streamId = 65_535, timestampUs = 9_000_000_000L, payload = byteArrayOf(1, 0, -2, -1)),
            MediaFrame(MediaKind.JPEG, streamId = 1, timestampUs = 0, payload = ByteArray(0)),
        )
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val out = File(System.getProperty("java.io.tmpdir"), "kotlin-media-frames.jsonl")
        out.writeText(
            frames.joinToString("\n") {
                """{"kind":${it.kind.code},"streamId":${it.streamId},"timestampUs":"${it.timestampUs}","payloadHex":"${hex(it.payload)}","frameHex":"${hex(MediaFrames.encode(it))}"}"""
            },
        )
        println("wrote ${frames.size} media frames to ${out.absolutePath}")
    }

    @Test
    fun `dump shared protocol constants for cross-language validation`() {
        val out = File(System.getProperty("java.io.tmpdir"), "kotlin-protocol-constants.json")
        out.writeText(
            """{"NOISE_INTERVAL_MS":$NOISE_INTERVAL_MS,"HEARTBEAT_INTERVAL_MS":$HEARTBEAT_INTERVAL_MS,"LIVENESS_TIMEOUT_MS":$LIVENESS_TIMEOUT_MS,""" +
                """"MAX_VIDEO_DIMENSION":$MAX_VIDEO_DIMENSION,"MIN_SAMPLE_RATE":$MIN_SAMPLE_RATE,""" +
                """"MAX_SAMPLE_RATE":$MAX_SAMPLE_RATE,"PAIRING_CODE_DIGITS":$PAIRING_CODE_DIGITS,""" +
                """"FINGERPRINT_CHECK_BYTES":$FINGERPRINT_CHECK_BYTES}""",
        )
        println("wrote protocol constants to ${out.absolutePath}")
    }
}
