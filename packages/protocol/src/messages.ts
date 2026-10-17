import { z } from "zod";

export const HEARTBEAT_INTERVAL_MS = 15_000;
export const LIVENESS_TIMEOUT_MS = 45_000;
export const NOISE_INTERVAL_MS = 15_000;

export const NoiseSampleSchema = z.object({
  startMs: z.number().int().nonnegative(),
  endMs: z.number().int().nonnegative(),
  levelDb: z.number().min(-120).max(0),
  peakDb: z.number().min(-120).max(0),
  thresholdDb: z.number().min(-120).max(60).nullable(),
}).refine(s => s.endMs > s.startMs && s.endMs - s.startMs <= NOISE_INTERVAL_MS,
  { message: "noise interval must cover 1–15000 ms" });
export type NoiseSample = z.infer<typeof NoiseSampleSchema>;

export const RoleSchema = z.enum(["monitor", "parent"]);
export type Role = z.infer<typeof RoleSchema>;

export const EventTypeSchema = z.enum([
  "LOUD_NOISE",
  "MOTION",
  "MONITOR_STARTED",
  "MONITOR_STOPPED",
  "MONITOR_LOST",
  "MONITOR_ONLINE",
]);
export type EventType = z.infer<typeof EventTypeSchema>;

// nullish, not optional: absent and null mean the same thing here -- no peak
// was measured -- and clients legitimately encode it either way. Kotlin must
// emit explicit nulls for the nullable fields in the same message (batteryPct,
// video, audio), so a serializer that omits nulls is not available to it.
// Being liberal about two encodings of one meaning is cheaper than a custom
// serializer working around a strictness that bought nothing.
export const EventMetadataSchema = z.object({
  peakDb: z.number().nullish(),
  durationMs: z.number().int().nonnegative().nullish(),
});
export type EventMetadata = z.infer<typeof EventMetadataSchema>;

export const MonitorConfigSchema = z.object({
  thresholdDb: z.number().min(0).max(60),
  sustainMs: z.number().int().min(100).max(30_000),
  cooldownS: z.number().int().min(0).max(3600),
  noiseFloorDb: z.number().min(-120).max(0),
  motionEnabled: z.boolean(),
  motionThreshold: z.number().min(0).max(255),
  calibrationWindowMs: z.number().int().min(5_000).max(120_000),
});
export type MonitorConfig = z.infer<typeof MonitorConfigSchema>;

export const DEFAULT_MONITOR_CONFIG: MonitorConfig = {
  thresholdDb: 12,
  sustainMs: 1500,
  cooldownS: 60,
  noiseFloorDb: -50,
  motionEnabled: false,
  motionThreshold: 12,
  calibrationWindowMs: 30_000,
};

// Bounds on what a stream may advertise. A viewer sizes an AudioTrack and a
// Bitmap from these numbers, so the hub refuses formats no real monitor
// produces rather than relaying them to every parent. The Kotlin twins are in
// Messages.kt, where the parent applies the same limits to what it plays.
export const MAX_VIDEO_DIMENSION = 4096;
export const MIN_SAMPLE_RATE = 8000;
export const MAX_SAMPLE_RATE = 48000;

export const CameraFacingSchema = z.enum(["back", "front"]);
export type CameraFacing = z.infer<typeof CameraFacingSchema>;

const CameraSelectionSchema = z.object({
  t: z.literal("select_camera"),
  streamId: z.number().int().min(1).max(65535),
  requestId: z.string().min(1).max(64),
  facing: CameraFacingSchema,
});
const CameraResultSchema = z.object({
  t: z.literal("camera_result"),
  streamId: z.number().int().min(1).max(65535),
  requestId: z.string().min(1).max(64),
  reason: z.string().max(128).nullable(),
});

const VideoFormatSchema = z.object({
  width: z.number().int().positive().max(MAX_VIDEO_DIMENSION),
  height: z.number().int().positive().max(MAX_VIDEO_DIMENSION),
  fps: z.number().positive(),
  facing: CameraFacingSchema.nullish(),
  availableCameras: z.array(CameraFacingSchema).max(2).optional(),
  // Clockwise rotation the viewer applies to show the picture upright. The
  // monitor sends frames as the sensor produced them rather than paying to
  // rotate each one on an old phone. Optional on the wire for older senders.
  rotationDegrees: z
    .union([z.literal(0), z.literal(90), z.literal(180), z.literal(270)])
    .default(0),
});
const AudioFormatSchema = z.object({
  sampleRate: z.number().int().min(MIN_SAMPLE_RATE).max(MAX_SAMPLE_RATE),
  channels: z.number().int().min(1).max(2),
});
export type VideoFormat = z.infer<typeof VideoFormatSchema>;
export type AudioFormat = z.infer<typeof AudioFormatSchema>;

export const MonitorToHubSchema = z.discriminatedUnion("t", [
  CameraResultSchema,
  z.object({ t: z.literal("talk_started"), talkId: z.number().int().min(1).max(65535) }),
  z.object({ t: z.literal("talk_stopped"), talkId: z.number().int().min(1).max(65535), reason: z.string().max(128) }),
  z.object({ t: z.literal("noise_level"), sample: NoiseSampleSchema }),
  z.object({
    t: z.literal("hello"),
    deviceName: z.string().min(1).max(64),
    appVersion: z.string().max(32),
  }),
  z.object({
    t: z.literal("event"),
    clientEventId: z.string().uuid(),
    type: EventTypeSchema,
    tsMs: z.number().int().nonnegative(),
    metadata: EventMetadataSchema.default({}),
  }),
  z.object({
    t: z.literal("stream_preparing"),
    streamId: z.number().int().min(0).max(65535),
  }),
  z.object({
    t: z.literal("stream_started"),
    streamId: z.number().int().min(0).max(65535),
    video: VideoFormatSchema.nullable(),
    audio: AudioFormatSchema.nullable(),
  }),
  z.object({
    t: z.literal("stream_stopped"),
    streamId: z.number().int().min(0).max(65535),
    reason: z.string().max(128),
  }),
  z.object({
    t: z.literal("calibration_result"),
    noiseFloorDb: z.number().min(-120).max(0),
    spreadDb: z.number().min(0).max(60),
    sampledMs: z.number().int().positive(),
  }),
  z.object({
    t: z.literal("heartbeat"),
    batteryPct: z.number().int().min(0).max(100).nullable(),
    thermal: z.enum(["none", "light", "moderate", "severe", "critical", "unknown"]),
    // Optional for monitors installed before live-view device information.
    charging: z.boolean().nullish(),
    batteryTemperatureC: z.number().min(-50).max(100).nullish(),
    speakerVolumePct: z.number().int().min(0).max(100).nullish(),
  }),
]);
export type MonitorToHub = z.infer<typeof MonitorToHubSchema>;

export const HubToMonitorSchema = z.discriminatedUnion("t", [
  CameraSelectionSchema,
  z.object({ t: z.literal("start_talk"), streamId: z.number().int().min(1).max(65535), talkId: z.number().int().min(1).max(65535) }),
  z.object({ t: z.literal("stop_talk"), talkId: z.number().int().min(1).max(65535) }),
  z.object({
    t: z.literal("start_stream"),
    streamId: z.number().int().min(0).max(65535),
    video: z.boolean(),
    audio: z.boolean(),
  }),
  z.object({ t: z.literal("stop_stream"), streamId: z.number().int().min(0).max(65535) }),
  z.object({ t: z.literal("config"), config: MonitorConfigSchema }),
  z.object({ t: z.literal("recalibrate"), windowMs: z.number().int().min(5_000).max(120_000) }),
  z.object({ t: z.literal("heartbeat_ack"), serverTsMs: z.number().int() }),
  z.object({ t: z.literal("error"), code: z.string(), message: z.string() }),
]);
export type HubToMonitor = z.infer<typeof HubToMonitorSchema>;

export const ParentToHubSchema = z.discriminatedUnion("t", [
  CameraSelectionSchema,
  z.object({ t: z.literal("start_talk"), streamId: z.number().int().min(1).max(65535), requestId: z.string().min(1).max(64) }),
  z.object({ t: z.literal("stop_talk"), streamId: z.number().int().min(1).max(65535), requestId: z.string().min(1).max(64) }),
  z.object({ t: z.literal("cancel_stream_request"), monitorId: z.string(), requestId: z.string().max(64).nullable().optional() }),
  z.object({
    t: z.literal("hello"),
    deviceName: z.string().min(1).max(64),
    appVersion: z.string().max(32),
  }),
  z.object({ t: z.literal("subscribe"), monitorIds: z.array(z.string()).nullable() }),
  z.object({
    t: z.literal("request_stream"),
    requestId: z.string().max(64).nullable().optional(),
    monitorId: z.string(),
    video: z.boolean(),
    audio: z.boolean(),
  }),
  z.object({ t: z.literal("stop_stream"), streamId: z.number().int().min(0).max(65535) }),
  z.object({ t: z.literal("ack_event"), eventId: z.number().int().positive() }),
  z.object({ t: z.literal("heartbeat") }),
]);
export type ParentToHub = z.infer<typeof ParentToHubSchema>;

export const HubToParentSchema = z.discriminatedUnion("t", [
  CameraResultSchema,
  z.object({ t: z.literal("talk_state"), streamId: z.number().int().min(1).max(65535), requestId: z.string().min(1).max(64), talkId: z.number().int().min(1).max(65535).nullable(), ready: z.boolean(), reason: z.string().max(128).nullable() }),
  z.object({
    t: z.literal("event"),
    eventId: z.number().int().positive(),
    monitorId: z.string(),
    type: EventTypeSchema,
    tsMs: z.number().int().nonnegative(),
    metadata: EventMetadataSchema.default({}),
  }),
  z.object({
    t: z.literal("monitor_status"),
    monitorId: z.string(),
    name: z.string(),
    online: z.boolean(),
    lastSeenMs: z.number().int().nullable(),
    batteryPct: z.number().int().min(0).max(100).nullable(),
  }),
  z.object({
    t: z.literal("stream_waiting"),
    streamId: z.number().int().min(0).max(65535),
    monitorId: z.string(),
    requestId: z.string().max(64).nullable().optional(),
    phase: z.enum(["contacting_monitor", "preparing_video"]),
  }),
  z.object({
    t: z.literal("stream_ready"),
    requestId: z.string().max(64).nullable().optional(),
    streamId: z.number().int().min(0).max(65535),
    monitorId: z.string(),
    video: VideoFormatSchema.nullable(),
    audio: AudioFormatSchema.nullable(),
  }),
  z.object({
    t: z.literal("stream_ended"),
    streamId: z.number().int().min(0).max(65535),
    reason: z.string().max(128),
  }),
  z.object({ t: z.literal("heartbeat_ack"), serverTsMs: z.number().int() }),
  z.object({ t: z.literal("error"), code: z.string(), message: z.string(), monitorId: z.string().optional(), requestId: z.string().max(64).nullable().optional() }),
]);
export type HubToParent = z.infer<typeof HubToParentSchema>;

export class MessageParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MessageParseError";
  }
}

export function parseMessage<T extends z.ZodTypeAny>(schema: T, raw: string): z.infer<T> {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    throw new MessageParseError("message is not valid JSON");
  }
  const result = schema.safeParse(json);
  if (!result.success) {
    throw new MessageParseError(
      result.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; "),
    );
  }
  return result.data;
}
