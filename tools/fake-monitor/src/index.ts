import { parseArgs } from "node:util";
import { FakeMonitor, pairDevice } from "./client.js";
import { FAKE_VIDEO, MediaPusher } from "./media.js";

// This tool trusts the operator who typed the pairing code. The real apps pin
// the hub's certificate fingerprint instead; never do this in a shipped client.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const { values } = parseArgs({
  options: {
    host: { type: "string", default: "127.0.0.1" },
    port: { type: "string", default: "8443" },
    code: { type: "string" },
    token: { type: "string" },
    name: { type: "string", default: "Fake Nursery" },
  },
});

const httpsUrl = `https://${values.host}:${values.port}`;
const wsUrl = `wss://${values.host}:${values.port}`;

let token = values.token;
if (token === undefined) {
  if (values.code === undefined) {
    console.error("pass --code <PAIRING CODE> on first run, or --token <TOKEN> afterwards");
    process.exit(2);
  }
  const creds = await pairDevice(httpsUrl, values.code, values.name);
  token = creds.token;
  console.log(`paired. device id: ${creds.deviceId}`);
  console.log(`reuse with:  --token ${creds.token}\n`);
}

const monitor = new FakeMonitor({ wsUrl, token, name: values.name });
const pusher = new MediaPusher();

monitor.on("config", (config: { thresholdDb: number }) =>
  console.log(`config pushed: thresholdDb=${config.thresholdDb}`));
monitor.on("recalibrate", (windowMs: number) =>
  console.log(`recalibrating for ${windowMs}ms`));
monitor.on("start_stream", (msg: { streamId: number; video: boolean; audio: boolean }) => {
  console.log(`streaming (id ${msg.streamId})`);
  monitor.send({
    t: "stream_started",
    streamId: msg.streamId,
    video: msg.video ? FAKE_VIDEO : null,
    audio: msg.audio ? { sampleRate: 16000, channels: 1 } : null,
  });
  pusher.start(monitor, msg.streamId, msg.video, msg.audio);
});
monitor.on("stop_stream", () => {
  console.log("stream stopped");
  pusher.stop();
});

await monitor.connect();
console.log("connected. keys:  n = loud noise   m = motion   s = started   q = quit\n");

const CTRL_C = 3;

process.stdin.setRawMode?.(true);
process.stdin.resume();
process.stdin.on("data", (buffer: Buffer) => {
  const key = buffer.toString();
  if (key === "n") {
    monitor.emitEvent("LOUD_NOISE", { peakDb: -10, durationMs: 2000 });
    console.log("-> LOUD_NOISE");
  } else if (key === "m") {
    monitor.emitEvent("MOTION", {});
    console.log("-> MOTION");
  } else if (key === "s") {
    monitor.emitEvent("MONITOR_STARTED", {});
    console.log("-> MONITOR_STARTED");
  } else if (key === "q" || buffer[0] === CTRL_C) {
    pusher.stop();
    monitor.close();
    process.exit(0);
  }
});
