import { loadConfig } from "./config.js";
import { createDeps, startHub } from "./server.js";

const config = loadConfig(process.env);
const hub = await startHub(createDeps(config.dataDir), config.port, config.eventRetentionDays);
console.log(`hub listening on port ${config.port}`);
console.log(`certificate fingerprint: ${hub.deps.tls.fingerprintSha256}`);
