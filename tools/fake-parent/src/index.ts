import { parseArgs } from "node:util";
import { FakeParent, type ReceivedEvent } from "./client.js";

// The hub uses a self-signed certificate that this tool trusts by way of the
// operator who typed the pairing code. Never do this in a shipped client -- the
// apps pin the fingerprint instead.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const { values } = parseArgs({
  options: {
    host: { type: "string", default: "127.0.0.1" },
    port: { type: "string", default: "8443" },
    code: { type: "string" },
    token: { type: "string" },
    name: { type: "string", default: "Fake Parent" },
    watch: { type: "string" },
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
  const { pairDevice } = await import("../../fake-monitor/dist/client.js");
  const creds = await pairDevice(httpsUrl, values.code, values.name);
  token = creds.token;
  console.log(`paired. device id: ${creds.deviceId}`);
  console.log(`reuse with:  --token ${creds.token}\n`);
}

function describe(event: ReceivedEvent): string {
  const time = new Date(event.tsMs).toLocaleTimeString();
  const peak = event.metadata.peakDb != null ? ` peak ${event.metadata.peakDb.toFixed(1)} dB` : "";
  const duration =
    event.metadata.durationMs != null ? ` ${(event.metadata.durationMs / 1000).toFixed(1)}s` : "";
  return `[${time}] #${event.eventId} ${event.type}${peak}${duration}  (monitor ${event.monitorId.slice(0, 8)})`;
}

const parent = new FakeParent({
  wsUrl,
  token,
  onEvent: (event) => console.log(describe(event)),
});

await parent.connect();
console.log(`listening for events from ${httpsUrl}. Ctrl-C to stop.\n`);

if (values.watch !== undefined) {
  parent.watch(values.watch);
  setInterval(() => console.log(`media frames: ${parent.mediaFrames}`), 5000);
}

process.on("SIGINT", () => {
  parent.stopWatching();
  parent.close();
  process.exit(0);
});
