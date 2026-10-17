package com.kidsmonitor.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val HEARTBEAT_INTERVAL_MS = 15_000L
const val LIVENESS_TIMEOUT_MS = 45_000L

/**
 * Bounds on what a stream may advertise, the twins of the same constants in
 * packages/protocol (checked by tools/check-protocol-conformance.mjs). The hub
 * refuses formats outside them; the parent applies them again to what it
 * decodes and plays, because a picture's real size is only known from its bytes.
 */
const val MAX_VIDEO_DIMENSION = 4096

/** Digits in a pairing code. Mirrored in packages/protocol and checked by the conformance script. */
const val PAIRING_CODE_DIGITS = 6

/** Bytes of the certificate fingerprint a person is asked to compare. */
const val FINGERPRINT_CHECK_BYTES = 3
const val MIN_SAMPLE_RATE = 8000
const val MAX_SAMPLE_RATE = 48000

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
enum class Role {
    @SerialName("monitor") MONITOR,
    @SerialName("parent") PARENT,
}

@Serializable
enum class EventType {
    LOUD_NOISE, MOTION, MONITOR_STARTED, MONITOR_STOPPED, MONITOR_LOST, MONITOR_ONLINE
}

@Serializable
data class EventMetadata(
    val peakDb: Double? = null,
    val durationMs: Int? = null,
)

@Serializable
enum class CameraFacing {
    @SerialName("back") BACK,
    @SerialName("front") FRONT,
}

@Serializable
data class VideoFormat(
    val width: Int,
    val height: Int,
    val fps: Double,
    /** Clockwise rotation the viewer applies. 0, 90, 180 or 270. */
    val rotationDegrees: Int = 0,
    val facing: CameraFacing? = null,
    val availableCameras: List<CameraFacing> = emptyList(),
)

@Serializable
data class AudioFormat(val sampleRate: Int, val channels: Int)

@Serializable
data class MonitorConfig(
    val thresholdDb: Double,
    val sustainMs: Int,
    val cooldownS: Int,
    val noiseFloorDb: Double,
    val motionEnabled: Boolean,
    val motionThreshold: Double,
    val calibrationWindowMs: Int,
)

@Serializable
sealed interface HubToParent {
    @Serializable @SerialName("camera_result")
    data class CameraResult(val streamId: Int, val requestId: String, val reason: String? = null) : HubToParent

    @Serializable @SerialName("talk_state")
    data class TalkState(val streamId: Int, val requestId: String, val talkId: Int?, val ready: Boolean, val reason: String?) : HubToParent

    @Serializable
    @SerialName("event")
    data class Event(
        val eventId: Long,
        val monitorId: String,
        val type: EventType,
        val tsMs: Long,
        val metadata: EventMetadata = EventMetadata(),
    ) : HubToParent

    @Serializable
    @SerialName("monitor_status")
    data class MonitorStatus(
        val monitorId: String,
        val name: String,
        val online: Boolean,
        val lastSeenMs: Long? = null,
        val batteryPct: Int? = null,
    ) : HubToParent

    @Serializable
    @SerialName("stream_waiting")
    data class StreamWaiting(
        val streamId: Int,
        val monitorId: String,
        val phase: String,
        val requestId: String? = null,
    ) : HubToParent

    @Serializable
    @SerialName("stream_ready")
    data class StreamReady(
        val streamId: Int,
        val monitorId: String,
        val video: VideoFormat? = null,
        val audio: AudioFormat? = null,
        val requestId: String? = null,
    ) : HubToParent

    @Serializable
    @SerialName("stream_ended")
    data class StreamEnded(val streamId: Int, val reason: String) : HubToParent

    @Serializable
    @SerialName("heartbeat_ack")
    data class HeartbeatAck(val serverTsMs: Long) : HubToParent

    @Serializable
    @SerialName("error")
    data class ErrorMessage(val code: String, val message: String, val monitorId: String? = null, val requestId: String? = null) : HubToParent
}

/**
 * Starting points for a real room, not tuned values (spec §9).
 *
 * These must match `DEFAULT_MONITOR_CONFIG` in `packages/protocol` — the hub
 * sends its own copy on connect, so a mismatch shows up as the monitor
 * behaving differently before and after the first `config` message.
 */
val DEFAULT_MONITOR_CONFIG: MonitorConfig = MonitorConfig(
    thresholdDb = 12.0,
    sustainMs = 1500,
    cooldownS = 60,
    noiseFloorDb = -50.0,
    motionEnabled = false,
    motionThreshold = 12.0,
    calibrationWindowMs = 30_000,
)

@Serializable
sealed interface MonitorToHub {
    @Serializable @SerialName("camera_result")
    data class CameraResult(val streamId: Int, val requestId: String, val reason: String? = null) : MonitorToHub

    @Serializable @SerialName("talk_started")
    data class TalkStarted(val talkId: Int) : MonitorToHub
    @Serializable @SerialName("talk_stopped")
    data class TalkStopped(val talkId: Int, val reason: String) : MonitorToHub

    @Serializable
    @SerialName("noise_level")
    data class NoiseLevel(val sample: NoiseSample) : MonitorToHub

    @Serializable
    @SerialName("hello")
    data class Hello(val deviceName: String, val appVersion: String) : MonitorToHub

    @Serializable
    @SerialName("event")
    data class Event(
        val clientEventId: String,
        val type: EventType,
        val tsMs: Long,
        val metadata: EventMetadata = EventMetadata(),
    ) : MonitorToHub

    @Serializable
    @SerialName("stream_preparing")
    data class StreamPreparing(val streamId: Int) : MonitorToHub

    @Serializable
    @SerialName("stream_started")
    data class StreamStarted(
        val streamId: Int,
        val video: VideoFormat? = null,
        val audio: AudioFormat? = null,
    ) : MonitorToHub

    @Serializable
    @SerialName("stream_stopped")
    data class StreamStopped(val streamId: Int, val reason: String) : MonitorToHub

    @Serializable
    @SerialName("calibration_result")
    data class CalibrationResult(
        val noiseFloorDb: Double,
        val spreadDb: Double,
        val sampledMs: Int,
    ) : MonitorToHub

    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        val batteryPct: Int?,
        val thermal: String,
        val charging: Boolean? = null,
        val batteryTemperatureC: Double? = null,
        val speakerVolumePct: Int? = null,
    ) : MonitorToHub
}

const val NOISE_INTERVAL_MS = 15_000L

/** Numeric summaries only; no audio samples or recordings. Null threshold means calibration. */
@Serializable
data class NoiseSample(
    val startMs: Long,
    val endMs: Long,
    val levelDb: Double,
    val peakDb: Double,
    val thresholdDb: Double?,
)

@Serializable
sealed interface HubToMonitor {
    @Serializable @SerialName("select_camera")
    data class SelectCamera(val streamId: Int, val requestId: String, val facing: CameraFacing) : HubToMonitor

    @Serializable @SerialName("start_talk")
    data class StartTalk(val streamId: Int, val talkId: Int) : HubToMonitor
    @Serializable @SerialName("stop_talk")
    data class StopTalk(val talkId: Int) : HubToMonitor

    @Serializable
    @SerialName("start_stream")
    data class StartStream(val streamId: Int, val video: Boolean, val audio: Boolean) : HubToMonitor

    @Serializable
    @SerialName("stop_stream")
    data class StopStream(val streamId: Int) : HubToMonitor

    @Serializable
    @SerialName("config")
    data class Config(val config: MonitorConfig) : HubToMonitor

    @Serializable
    @SerialName("recalibrate")
    data class Recalibrate(val windowMs: Int) : HubToMonitor

    @Serializable
    @SerialName("heartbeat_ack")
    data class HeartbeatAck(val serverTsMs: Long) : HubToMonitor

    @Serializable
    @SerialName("error")
    data class ErrorMessage(val code: String, val message: String) : HubToMonitor
}

@Serializable
sealed interface ParentToHub {
    @Serializable @SerialName("select_camera")
    data class SelectCamera(val streamId: Int, val requestId: String, val facing: CameraFacing) : ParentToHub

    @Serializable @SerialName("start_talk")
    data class StartTalk(val streamId: Int, val requestId: String) : ParentToHub
    @Serializable @SerialName("stop_talk")
    data class StopTalk(val streamId: Int, val requestId: String) : ParentToHub

    @Serializable
    @SerialName("hello")
    data class Hello(val deviceName: String, val appVersion: String) : ParentToHub

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val monitorIds: List<String>?) : ParentToHub

    @Serializable
    @SerialName("request_stream")
    data class RequestStream(
        val monitorId: String,
        val video: Boolean,
        val audio: Boolean,
        val requestId: String? = null,
    ) : ParentToHub

    @Serializable
    @SerialName("cancel_stream_request")
    data class CancelStreamRequest(val monitorId: String, val requestId: String? = null) : ParentToHub

    @Serializable
    @SerialName("stop_stream")
    data class StopStream(val streamId: Int) : ParentToHub

    @Serializable
    @SerialName("ack_event")
    data class AckEvent(val eventId: Long) : ParentToHub

    @Serializable
    @SerialName("heartbeat")
    data object Heartbeat : ParentToHub
}

object ProtocolJson {
    // classDiscriminator "t" matches the zod discriminatedUnion in
    // packages/protocol. explicitNulls keeps `"monitorIds": null` on the wire,
    // which the hub reads as "every monitor" -- dropping the key would change
    // its meaning. ignoreUnknownKeys lets a newer hub add fields without
    // breaking an app that has not been updated.
    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }

    fun decodeHubToParent(raw: String): HubToParent =
        try {
            json.decodeFromString(HubToParent.serializer(), raw)
        } catch (e: Exception) {
            throw ProtocolException("could not decode hub message: ${e.message}", e)
        }

    fun encode(msg: ParentToHub): String = json.encodeToString(ParentToHub.serializer(), msg)

    fun decodeHubToMonitor(raw: String): HubToMonitor =
        try {
            json.decodeFromString(HubToMonitor.serializer(), raw)
        } catch (e: Exception) {
            throw ProtocolException("could not decode hub message: ${e.message}", e)
        }

    fun encodeMonitor(msg: MonitorToHub): String =
        json.encodeToString(MonitorToHub.serializer(), msg)
}
