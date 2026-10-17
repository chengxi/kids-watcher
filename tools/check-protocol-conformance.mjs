#!/usr/bin/env node
// Validates Kotlin's wire encodings against the hub's own zod schemas.
//
// Kotlin and TypeScript each test themselves; neither can prove they agree.
// This closes that gap: EncodingDumpTest writes every ParentToHub encoding to a
// file, and this script parses each one with the schema the hub actually uses.
//
//   cd apps/android && ./gradlew :shared:testDebugUnitTest --tests '*EncodingDumpTest*'
//   node tools/check-protocol-conformance.mjs <path-to-kotlin-encodings.jsonl>
//
// With no argument it searches the usual temp locations.

import { execSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import * as protocol from "../packages/protocol/dist/index.js";
import {
  MonitorToHubSchema,
  ParentToHubSchema,
  decodeMediaFrame,
  encodeMediaFrame,
} from "../packages/protocol/dist/index.js";

function locateDump(filename) {
  const found = execSync(
    `find /var/folders "\${TMPDIR:-/tmp}" /tmp -name ${filename} 2>/dev/null | head -1`,
    { encoding: "utf8", shell: "/bin/sh" },
  ).trim();
  return found || null;
}

const directions = [
  { name: "parent -> hub", file: "kotlin-encodings.jsonl", schema: ParentToHubSchema },
  { name: "monitor -> hub", file: "kotlin-monitor-encodings.jsonl", schema: MonitorToHubSchema },
];

let rejected = 0;
let checked = 0;
let missing = 0;

for (const direction of directions) {
  const path = process.argv[2] && directions.length === 1 ? process.argv[2] : locateDump(direction.file);
  if (path === null || !existsSync(path)) {
    console.error(`  MISSING  ${direction.name}: no ${direction.file} found`);
    missing++;
    continue;
  }

  console.log(`${direction.name}:`);
  const lines = readFileSync(path, "utf8").trim().split("\n").filter(Boolean);
  for (const line of lines) {
    const parsed = JSON.parse(line);
    const result = direction.schema.safeParse(parsed);
    checked++;
    if (result.success) {
      console.log(`  OK       ${parsed.t}`);
    } else {
      rejected++;
      const why = result.error.issues
        .map((i) => `${i.path.join(".")}: ${i.message}`)
        .join("; ");
      console.log(`  REJECTED ${parsed.t}: ${why}`);
    }
  }
}

// Binary frames, both directions: TypeScript decodes what Kotlin encoded, and
// TypeScript's own encoding of the same fields must be byte-identical.
const framePath = locateDump("kotlin-media-frames.jsonl");
if (framePath === null) {
  console.error("  MISSING  media frames: no kotlin-media-frames.jsonl found");
  missing++;
} else {
  console.log("media frames:");
  const hexOf = (bytes) => Buffer.from(bytes).toString("hex");
  for (const line of readFileSync(framePath, "utf8").trim().split("\n").filter(Boolean)) {
    const row = JSON.parse(line);
    checked++;
    try {
      const decoded = decodeMediaFrame(new Uint8Array(Buffer.from(row.frameHex, "hex")));
      const reencoded = hexOf(encodeMediaFrame({
        kind: row.kind,
        streamId: row.streamId,
        timestampUs: BigInt(row.timestampUs),
        payload: new Uint8Array(Buffer.from(row.payloadHex, "hex")),
      }));
      const same =
        decoded.kind === row.kind &&
        decoded.streamId === row.streamId &&
        decoded.timestampUs === BigInt(row.timestampUs) &&
        hexOf(decoded.payload) === row.payloadHex &&
        reencoded === row.frameHex;
      if (same) {
        console.log(`  OK       kind ${row.kind} stream ${row.streamId}`);
      } else {
        rejected++;
        console.log(`  REJECTED kind ${row.kind} stream ${row.streamId}: fields or bytes differ`);
      }
    } catch (error) {
      rejected++;
      console.log(`  REJECTED kind ${row.kind}: ${error.message}`);
    }
  }
}

// Constants both languages hard-code: the hub enforces them on stream formats
// and the parent enforces them again on what it decodes and plays.
const constantsPath = locateDump("kotlin-protocol-constants.json");
if (constantsPath === null) {
  console.error("  MISSING  constants: no kotlin-protocol-constants.json found");
  missing++;
} else {
  console.log("protocol constants:");
  const kotlin = JSON.parse(readFileSync(constantsPath, "utf8"));
  for (const [name, value] of Object.entries(kotlin)) {
    checked++;
    if (protocol[name] === value) {
      console.log(`  OK       ${name} = ${value}`);
    } else {
      rejected++;
      console.log(`  REJECTED ${name}: Kotlin ${value}, TypeScript ${protocol[name]}`);
    }
  }
}

if (missing > 0) {
  console.error(
    "\nRun both dumps first:\n" +
      "  cd apps/android && ./gradlew :shared:testDebugUnitTest --tests '*EncodingDumpTest*'",
  );
  process.exit(2);
}

console.log(
  rejected === 0
    ? `\nAll ${checked} Kotlin encodings validate against the hub schemas.`
    : `\n${rejected} of ${checked} REJECTED \u2014 Kotlin and zod have drifted.`,
);
process.exit(rejected === 0 ? 0 : 1);
