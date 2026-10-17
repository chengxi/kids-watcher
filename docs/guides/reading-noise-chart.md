# Reading the parent noise chart

The faint grey line is the measured peak of each 15-second interval. The dark
line is a centered, cosine-weighted moving average of those peaks in dBFS. It
uses 30/60/180/360-minute windows for the 1/3/12/24-hour views. Tapered weights,
sparse knots and shape-preserving interpolation emphasize sustained patterns;
brief peaks remain visible in the grey trace and alert markers. The trend is a
visual average, not the detector input or an acoustic energy measurement.

The Y-axis fits the visible measurements, trend, noise-alert peaks and latest
alert level with padding and a minimum 20 dB span. Compare tick values when
switching time ranges; automatic scaling can make equal changes look different.
Grey rectangles show the exact periods with no measurements, including before
the first sample and after the last sample. Their widths represent elapsed time;
missing periods are never compressed or filled with invented readings. Raw and
smoothed lines are clipped to the exact measured intervals and stop at every gap.
Tap inside a grey band to see **No data**. Measured digital silence remains data.

Averaging excludes missing time. For stability, scheduling gaps of up to 250 ms
can retain the same averaging window, but the chart still leaves those gaps grey
and draws no line across them. Longer capture gaps start independent averages.
Overlapping timestamps are clipped for averaging rather than counted twice.

## Alert level

The dashed horizontal line shows the **latest reported adaptive alert level**,
not a claim that this setting applied throughout history. The monitor adapts
its background estimate while sound is below threshold; the effective level is
that estimate plus the configured offset (12 dB by default). Detection uses
20-millisecond microphone RMS readings that must remain above the threshold
for the configured sustain time (1.5 seconds by default), followed by cooldown.
A brief raw peak above the line, or the averaged trend crossing it, does not
necessarily generate an alert. Tap a historical time to see its recorded level.
Calibration reports no threshold, so the reference is omitted then.

## Everyday sound examples

Tap the bottom-right **ⓘ** button for an explanation and reference examples.
The chart uses **dBFS**, the phone microphone's uncalibrated digital level;
0 is full scale and higher readings mean louder input. Everyday acoustic sound
levels use a different reference (typically dBA). There is no valid universal
conversion from this phone's dBFS to room dBA; acoustic calibration with a
reference sound meter would be needed. Compare the same phone and position.

The help includes approximate examples, not categories assigned to chart ticks:

- Quiet bedroom ~30 dBA, living room without TV ~40 dBA, conversation ~60 dBA,
  garbage disposal ~80 dBA: [Puyallup environmental noise study, Table 4-64](https://www.puyallupwa.gov/DocumentCenter/View/19285).
- Dishwasher 45–65 dB and concerts 95–115 dB: [CDC sound guide](https://www.cdc.gov/nceh/hearing_loss/infographic/index.html).
- Playground 61 dBA average / 73 dBA peak at one measured location:
  [San José noise study, Table 1](https://www.sanjoseca.gov/home/showpublisheddocument/85837/637882902247070000).

Activity, equipment and distance matter. The playground range in the help is
one site's average-to-peak example, not a universal playground rating. This
chart does not identify sound sources or measure hearing exposure.

## Verification (October 9, 2026)

All 167 parent tests passed, including axis fitting, clipping bounds, smoothing,
gap handling and rendering. The updated parent APK was installed on the paired
Pixel 8 Pro. On-device inspection confirmed the horizontal latest-level
reference, always-visible raw peaks, narrower axis, smoother weighted trend,
and the bottom-right guide opening, scrolling and dismissal. The app
reconnected to the hub and showed the Pixel 6 online.

### Exact no-data coverage update (October 9, 2026)

All 174 parent tests pass after making measured coverage exact, including gaps
of 1/100/250/251 ms, overlapping intervals, stable averaging around tiny timing
gaps, and a rendered six-hour gap. The rendered regression verifies the grey
band's true time-axis width, retained lines on measured sections, and absence
of raw/smoothed strokes inside the missing period.

The update was installed on the paired Pixel 8 Pro. In the one-hour view, the
actual 35.452-second gap during the Pixel 6 update appeared as a grey band with
both traces interrupted. Tapping inside it displayed **Fri 09:29:30 · No data**.
Existing measurements, timestamps, stored history, and backend configuration
were preserved.
