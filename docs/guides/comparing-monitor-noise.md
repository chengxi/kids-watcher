# Comparing two monitors that disagree about noise

Two monitors in the same room routinely draw different noise curves and raise
different numbers of alerts. Most of the difference is not a faulty microphone.
This guide is the order to check things in, the comparison that is statistically
valid, and the two physical tests that separate a device problem from everything
else.

Start from what the numbers are. Levels are reported in **dBFS**, the phone
microphone's uncalibrated digital level: 0 is the digital limit and lower
numbers are quieter. It is not an acoustic unit, and it cannot be converted to
one without calibrating against a sound meter. There is no fixed relationship
between one phone's dBFS and another's, and nothing in the pipeline applies a
per-device correction, so a couple of decibels of offset between two handsets is
expected and is not a defect. The parent app says as much in the chart's own
help, behind the **ⓘ** button.

How much two devices disagree depends on the devices. Phones of different ages
and vendors can differ far more than two of the same model, and this project
expects whatever hardware a household already owns. Treat the numbers in any
verification record below as one example, not a specification: the procedure is
what transfers, not the figures.

Do all inspection read-only. Comparing monitors never requires pushing config,
recalibrating, re-pairing or rebuilding, and a recalibration destroys the
baseline you are trying to measure.

## 0. Read the hub database without disturbing it

Samples live in `noise_samples` in the hub's SQLite database, one row per
15-second interval (`NOISE_INTERVAL_MS`) per monitor, carrying `level_db`
(energy mean), `peak_db` (maximum 20 ms window) and `threshold_db` (the adaptive
trigger at the end of the interval).

Run queries inside the container, with `-w /app` so `better-sqlite3` resolves,
and open the database `readonly`. Set `HUB_SSH` to your own hub host:

```bash
HUB_SSH=user@hub-host
ssh $HUB_SSH 'docker exec -w /app kids-monitor-hub node -e "
const D=require(\"better-sqlite3\");
const db=new D(\"/data/hub.db\",{readonly:true});
console.log(db.prepare(\"select id,name,last_seen_ms from devices where role=?\").all(\"monitor\"));
"'
```

Later steps assume `HUB_SSH` is still set in your shell.

Copy larger scripts in with `docker cp`, run them, then delete them — do not
leave diagnostic files in the deployment. Keep device ids, tokens and sample
contents out of anything you commit.

## 1. Confirm the monitors were co-located — do this first

**A comparison across any period when the phones were in different places
measures two rooms, not two devices.** This is the single most common way to
reach a wrong conclusion, and the data will tell you.

Compute rolling agreement in 30-minute blocks. Co-located phones give
r ≈ 0.85 and a median level difference within a couple of dB; separated phones
give erratic r and a drifting offset:

Save this as `rolling-agreement.js`:

```javascript
const D = require("better-sqlite3");
const db = new D("/data/hub.db", { readonly: true });
const A = process.env.A, B = process.env.B, CELL = 60000;
const from = Date.now() - 24 * 3600000;
const q = db.prepare(`select start_ms s, end_ms e, level_db l, peak_db p
  from noise_samples where monitor_id=? and end_ms>? order by start_ms`);
// Overlap-weighted energy resample onto a common grid: the two monitors'
// 15 s windows are not in phase, so cells must be shared, not paired.
const grid = rows => {
  const m = new Map();
  for (const r of rows) {
    const en = Math.pow(10, r.l / 10);
    for (let c = Math.floor(r.s / CELL); c <= Math.floor((r.e - 1) / CELL); c++) {
      const ov = Math.min(r.e, (c + 1) * CELL) - Math.max(r.s, c * CELL);
      if (ov <= 0) continue;
      const v = m.get(c) || { en: 0, ms: 0, pk: -120 };
      v.en += en * ov; v.ms += ov; v.pk = Math.max(v.pk, r.p); m.set(c, v);
    }
  }
  const o = new Map();
  for (const [c, v] of m) if (v.ms >= CELL * 0.9) o.set(c, { l: 10 * Math.log10(v.en / v.ms), p: v.pk });
  return o;
};
const ga = grid(q.all(A, from)), gb = grid(q.all(B, from));
const cs = [...ga.keys()].filter(c => gb.has(c)).sort((x, y) => x - y);
const med = v => [...v].sort((x, y) => x - y)[Math.floor(v.length / 2)];
const corr = (x, y) => {
  const n = x.length, mx = x.reduce((p, q) => p + q, 0) / n, my = y.reduce((p, q) => p + q, 0) / n;
  let sxy = 0, sxx = 0, syy = 0;
  for (let i = 0; i < n; i++) { sxy += (x[i]-mx)*(y[i]-my); sxx += (x[i]-mx)**2; syy += (y[i]-my)**2; }
  return sxx && syy ? sxy / Math.sqrt(sxx * syy) : NaN;
};
for (let i = 0; i + 30 <= cs.length; i += 30) {
  const s = cs.slice(i, i + 30);
  const r = corr(s.map(c => ga.get(c).l), s.map(c => gb.get(c).l));
  const dl = s.map(c => ga.get(c).l - gb.get(c).l);
  console.log(new Date(s[0] * CELL).toISOString().slice(5, 16),
    " r=" + (isNaN(r) ? "n/a" : r.toFixed(3)), " medDiff=" + med(dl).toFixed(1));
}
```

Then run it against the deployment and clean up after yourself:

```bash
scp rolling-agreement.js $HUB_SSH:/tmp/
ssh $HUB_SSH 'docker cp /tmp/rolling-agreement.js kids-monitor-hub:/app/ &&
  docker exec -w /app -e A=<monitor-id-1> -e B=<monitor-id-2> \
    kids-monitor-hub node /app/rolling-agreement.js;
  docker exec kids-monitor-hub rm -f /app/rolling-agreement.js; rm -f /tmp/rolling-agreement.js'
```

Set `A` and `B` to the two monitor ids. The block times print in **UTC**, while
`logcat` timestamps on the handsets are in the phone's local zone — convert
before lining the two up. Restrict every later comparison to the
blocks that show agreement, and ask whoever owns the phones whether one was
moved — the block where r collapses dates it.

## 2. Rule out configuration and build differences

```bash
# Per-monitor config. A monitor with no row uses the hub defaults.
ssh $HUB_SSH 'docker exec -w /app kids-monitor-hub node -e "
const D=require(\"better-sqlite3\");const db=new D(\"/data/hub.db\",{readonly:true});
for(const r of db.prepare(\"select monitor_id,config_json,calibrated_at_ms from monitor_config\").all()) console.log(r);
"'
```

Compare against `DEFAULT_MONITOR_CONFIG` in `packages/protocol/src/messages.ts`
and its Kotlin twin in `Messages.kt`. A missing row is not a difference; the hub
sends the same defaults.

The hub accepts `appVersion` in the hello message but neither logs nor stores
it, so compare installed builds on the handsets instead:

```bash
ADB=$(sed -n 's/^sdk.dir=//p' apps/android/local.properties)/platform-tools/adb
$ADB devices -l
$ADB -s <serial> shell dumpsys package com.kidsmonitor.monitor | grep -E "versionName|lastUpdateTime"
$ADB -s <serial> shell getprop ro.build.version.release
```

Different Android versions are expected to behave differently below the app and
are not by themselves a fault.

## 3. Compare samples correctly

Two traps make a valid comparison look broken:

- **The 15 s windows of two monitors are not in phase.** They can sit 8 s apart,
  so pairing samples by rounded bucket mismatches them by up to a full interval.
  Resample both onto a common 1-minute grid weighting each sample's
  `10^(level/10)` by its overlap with the cell, as the script in step 1 does.
  Naive bucketing on real data gave r ≈ 0.31 where the correct method gave 0.86.
- **Conditioning the analysis on one phone.** Splitting "quiet" and "loud" by
  one monitor's own median shrinks that monitor's spread in its own lower half
  by regression to the mean, and invents an asymmetry. Split on the **joint**
  mean of the two, or by time of day.

Quote the median *and* the mean offset, plus an orthogonal (geometric-mean)
slope. Both series are noisy, so a one-way regression slope is biased low. A
pure sensitivity difference shows a tight offset (sd < 2 dB) and r > 0.8;
a level-dependent mismatch shows a slope away from 1.

When comparing event peaks across phones, take the other phone's **maximum over
every 15 s window overlapping `[ts − 5 s, ts + 2 s]`**. An event's `ts_ms` is
the moment sustain completed, and with the phase offset a narrow lookup
attributes the sound to the wrong window and overstates the gap.

Check the direction of any gap. If each monitor reports its own events far
louder than its neighbour does — in **both** directions — that is proximity to
different sound sources, not a sensitivity defect in either phone.

## 4. Check the capture path on each phone

```bash
$ADB -s <serial> shell dumpsys media.audio_flinger | awk '/Input thread/,0' | head -30
```

Confirm on each phone:

- `Audio source: 1 (AUDIO_SOURCE_MIC)` and `AUDIO_DEVICE_IN_BUILTIN_MIC`
- **`0 Effect Chains`** on the active input. A framework `NoiseSuppressor`,
  `AutomaticGainControl` or `AcousticEchoCanceler` attached to one phone's
  record session would be a real difference. The monitor attaches none.
- the HAL `Sample rate`. The app asks for 16 kHz; a HAL running at 48 kHz means
  AudioFlinger is resampling, which differs between handsets.

`Signal power history` in the same section gives each HAL's own level at 50 ms
resolution. Comparing it against the app's reading separates a
microphone/HAL deficit from one introduced in the app's path. How much history
a device keeps varies — some retain only a few seconds — so poll it rather than
reading it once.

Capture is `MediaRecorder.AudioSource.MIC` (`MonitorService.kt`), a deliberately
*processed* source. Each vendor HAL applies its own processing, which `dumpsys`
does not expose. Differences there are plausible and can only be settled by the
tests below.

## 5. Controlled tests

Place the phones side by side, same orientation, mics unobstructed, neither
face-down. Watch each app's own reading live — the same number that feeds the
chart, logged every 5 s:

```bash
$ADB -s <serial> logcat -v time KidsMonitor:I '*:S' | grep level=
```

These are single 20 ms snapshots, so average many of them over a plateau rather
than reading one. For impulsive sounds use the hub's `peak_db` instead, which is
the maximum over the whole 15 s interval.

### Clap test — measures level, not alerting

Ten claps about 3 s apart, equidistant from both phones. Compare `peak_db` and
`level_db` for the interior 15 s windows; the first and last straddle the start
and end of the clapping and are not comparable.

Two limits, both important:

- **Claps do not test alerting.** `sustainMs` (1.5 s by default) requires the
  level to stay above the trigger *continuously*, and `NoiseDetector.onWindow`
  calls `reset()` on the first window at or below it. The level collapses
  between claps, so sustain never completes. Neither monitor firing on claps is
  correct behaviour, not a bug — the detector is meant to ignore doors and claps
  and listen for sustained noise.
- **Loud claps prove less than they appear to.** A −8 dBFS 20 ms RMS implies
  samples at or near full scale. Two clipping microphones agree regardless of
  their sensitivity, so near-clipping agreement says nothing about the
  mid-levels where curves actually diverge.

### Sustained step test — alerting and linearity

Generate plateaus with the steps built into the *signal*, not the volume slider,
whose taper is unknown. Pink noise, exact 10 dB steps, with silence either side:

```bash
ffmpeg -nostdin -y \
 -f lavfi -i "anullsrc=r=48000:cl=mono:d=40" \
 -f lavfi -i "anoisesrc=color=pink:sample_rate=48000:amplitude=1.0:duration=75" \
 -f lavfi -i "anoisesrc=color=pink:sample_rate=48000:amplitude=0.316:duration=75" \
 -f lavfi -i "anoisesrc=color=pink:sample_rate=48000:amplitude=0.1:duration=75" \
 -f lavfi -i "anullsrc=r=48000:cl=mono:d=40" \
 -filter_complex "[0][1][2][3][4]concat=n=5:v=0:a=1[out]" -map "[out]" -ac 1 stimulus.wav
```

Each plateau must span at least two full 15 s samples, and the phase offset
means longer is better. Play it from a speaker equidistant from both phones; on
macOS a specific output device can be targeted without changing the system
default:

```bash
ffmpeg -hide_banner -f lavfi -i "anoisesrc=duration=0.1" -f audiotoolbox -list_devices true -y /dev/null
ffmpeg -nostdin -i stimulus.wav -f audiotoolbox -audio_device_index <n> -y /dev/null
```

Calibrate first so the loudest plateau lands around −30 dBFS on the phones:
clearly above the trigger, well short of clipping. Then average only the samples
**fully contained** in each plateau, discarding the first ~12 s for settling,
and average in energy (`10^(level/10)`), not in dB.

Reading the result:

- steps near 10 dB on both, differing by a constant → a fixed offset, and a
  per-device calibration offset would make the curves comparable
- one phone's steps compressed → AGC or limiting in that phone's path
- one phone's quiet plateau flat while the other tracks it → that phone is
  limited by its own microphone self-noise, which no setting repairs
- **steps compressed on both phones → suspect the room, not the phones.**
  Compare `silence-pre` against `silence-post`. If the room grew louder during
  the run, background noise swamped the quieter plateaus and compressed every
  step. This invalidates the linearity result; rerun in a quiet room.

## 6. The adaptive-floor dead band

A monitor can desensitise itself to sustained sound. On the one pair of phones
measured so far this was the clearest difference left once they were genuinely
co-located; whether it dominates in general is not established.

`NoiseDetector.onWindow` feeds the floor EMA only when the level is at or below
the trigger:

```kotlin
val threshold = noiseFloorDb + config.thresholdDb
if (dbfs <= threshold) { updateFloor(dbfs); reset(); return null }
```

The guard protects sound *above* the trigger, but the trigger sits
`thresholdDb` (12 dB by default) above the floor. A sustained sound landing in
the **0–12 dB band above the floor** is therefore averaged into the floor
instead. The floor rises toward that sound and the trigger rises with it, which
keeps the sound below the trigger and feeds it back into the floor. The floor
settles near the sound's own level, leaving the trigger roughly `thresholdDb`
above it — so the monitor stops responding to a sound it had been hearing. This
is absorption rather than a runaway: the EMA follows the mean and stops there
instead of climbing without limit. The window is 60 s (`floorEmaWindowMs`), so
it develops over about a minute.

Watch for it in the `level=` log lines: a floor and trigger rising while
`level` sits just under `trigger`, then levelling off together.

This matters for comparison because it can turn a small device offset into a
visible behavioural one. Two phones a couple of dB apart can land on opposite
sides of their own triggers for the same sound: one holds its floor and alerts,
the other absorbs the sound into its floor and stops responding to it. Expect
co-located monitors to drift to different triggers, and do not assume they will
report equal alert counts.

When comparing alert counts between monitors, record each monitor's
`threshold_db` over the period as well. Divergent alert counts with matching
`level_db` point at this, not at the microphones.

## Verified: October 10, 2026

One pair of handsets, recorded so the procedure above has a worked example. The
models are named for the record only; nothing here should be read as expected
behaviour for other hardware.

Two monitors running the same build, side by side, co-location confirmed by the
owner (a Pixel 6 and a Pixel 3, on different Android versions). What the
procedure produced:

- An initial 17 h comparison showed r = 0.704, an offset sd of 5.68 dB and
  1 alert against 14. **All of it an artifact of one phone having been moved
  during the window** — step 1's rolling agreement dated the separation, and the
  one genuinely co-located stretch (90 common minutes) gave r = 0.856 with an
  offset median of 0.00 dB and sd 2.80 dB. This was by far the largest effect
  found, and it was not a device difference at all.
- Event gaps of 8–23 dB ran in **both** directions, each monitor louder on its
  own events — proximity to the source, not sensitivity.
- `0 Effect Chains` on the active input of both. The two HALs ran at different
  sample rates, one of them resampling to reach the 16 kHz the app requests,
  with no effect visible in the readings.
- Clap test: median `peak_db` difference −0.22 dB, median `level_db` difference
  −0.70 dB, and HAL medians 0.2 dB apart. Neither monitor alerted, correctly.
  Note the claps peaked near full scale, where two clipping microphones agree
  regardless of sensitivity, so this says little about mid-levels.
- Step test: both monitors alerted on the sustained loud plateau within 0.5 s of
  each other. Linearity was **inconclusive** — the room ended about 12 dB louder
  than it started, and both monitors compressed similarly.
- Dead band: about half of all readings (31/65 and 32/66) sat inside it. The
  floors also rose about 5 dB across the run, but that figure is confounded by
  the room getting louder, so the usable evidence is the mid-plateau, where the
  same sound was absorbed by one monitor's floor and left alone by the other's.

The level mismatch that remained, co-located, was roughly ±3 dB and depended on
level: one monitor read about 2.5 dB higher in near-silence and about 3.3 dB
lower on the loud plateau. A median offset of 0.00 dB over 90 minutes is a
closer agreement than two arbitrary handsets should be assumed to reach; expect
more on a mixed fleet of older devices, and re-measure rather than carrying
these numbers over.
