import MonitorCore
import SwiftUI

struct ParentNoiseView: View {
  @ObservedObject var model: ParentModel
  @Binding var guide: Bool
  @State private var hours = 3
  @State private var inspection: String?
  @State private var plotWidth: CGFloat = 300
  var body: some View {
    TimelineView(.periodic(from: .now, by: 15)) { context in
      let now = Int64(context.date.timeIntervalSince1970 * 1000)
      let events = model.chartEvents.filter { $0.tsMs >= now - Int64(hours) * 3_600_000 }
      let chart = ParentNoiseChart(
        samples: model.noise, now: now, hours: hours,
        alertPeaks: events.compactMap(\.metadata.peakDb))
      ParentPanel {
        VStack(alignment: .leading, spacing: 16) {
          HStack {
            Text("Sound history").font(.system(size: 19, weight: .medium))
            Spacer()
            Text("dBFS").font(.system(size: 11, design: .monospaced)).foregroundStyle(
              Palette.secondary)
          }
          HStack(spacing: 6) {
            ForEach([1, 3, 12, 24], id: \.self) { value in
              Button {
                hours = value
                inspection = nil
              } label: {
                Text("\(value)h").font(.system(size: 12, weight: .medium)).frame(
                  maxWidth: .infinity, minHeight: 34
                ).foregroundStyle(hours == value ? Palette.onPrimary : Palette.secondary)
                  .background(hours == value ? Palette.ink : Palette.line, in: Capsule())
              }.buttonStyle(.plain)
            }
          }
          Canvas { context, size in
            let plot = CGRect(
              x: 34, y: 4, width: max(1, size.width - 40), height: max(1, size.height - 28))
            func x(_ time: Int64) -> CGFloat {
              plot.minX + CGFloat(Double(time - chart.from) / Double(chart.to - chart.from))
                * plot.width
            }
            func y(_ db: Double) -> CGFloat {
              plot.maxY - CGFloat((db - chart.bottom) / max(1, chart.top - chart.bottom))
                * plot.height
            }
            for gap in chart.gaps {
              context.fill(
                Path(
                  CGRect(
                    x: x(gap.start), y: plot.minY, width: max(0.3, x(gap.end) - x(gap.start)),
                    height: plot.height)), with: .color(Palette.line.opacity(0.65)))
            }
            for i in 0...4 {
              let value = chart.top - (chart.top - chart.bottom) * Double(i) / 4
              var line = Path()
              line.move(to: CGPoint(x: plot.minX, y: y(value)))
              line.addLine(to: CGPoint(x: plot.maxX, y: y(value)))
              context.stroke(line, with: .color(Palette.secondary.opacity(0.13)), lineWidth: 0.5)
              context.draw(
                Text(String(format: "%.0f", value)).font(.system(size: 9, design: .monospaced))
                  .foregroundColor(Palette.secondary), at: CGPoint(x: plot.minX - 6, y: y(value)),
                anchor: .trailing)
            }
            var raw = Path()
            var previous: ParentNoiseSample?
            for sample in chart.samples {
              let left = CGPoint(x: x(max(chart.from, sample.startMs)), y: y(sample.peakDb))
              if let previous, sample.startMs <= previous.endMs {
                raw.addLine(to: left)
              } else {
                raw.move(to: left)
              }
              raw.addLine(to: CGPoint(x: x(min(chart.to, sample.endMs)), y: y(sample.peakDb)))
              previous = sample
            }
            context.stroke(raw, with: .color(Palette.orange.opacity(0.22)), lineWidth: 0.9)
            // Clip every trend stroke to exact measured coverage, including scheduling gaps.
            for span in chart.coverage {
              var clipped = context
              clipped.clip(
                to: Path(
                  CGRect(
                    x: x(span.start), y: plot.minY, width: x(span.end) - x(span.start),
                    height: plot.height)))
              for points in chart.trend {
                guard let first = points.first else { continue }
                var line = Path()
                line.move(to: CGPoint(x: x(first.time), y: y(first.db)))
                let slopes = points.indices.dropLast().map {
                  (points[$0 + 1].db - points[$0].db)
                    / Double(points[$0 + 1].time - points[$0].time)
                }
                func tangent(_ i: Int) -> Double {
                  if slopes.isEmpty { return 0 }
                  if i == 0 { return slopes[0] }
                  if i == points.count - 1 { return slopes.last! }
                  let l = slopes[i - 1]
                  let r = slopes[i]
                  if l * r <= 0 { return 0 }
                  let d0 = Double(points[i].time - points[i - 1].time)
                  let d1 = Double(points[i + 1].time - points[i].time)
                  return (3 * (d0 + d1)) / ((2 * d1 + d0) / l + (d1 + 2 * d0) / r)
                }
                for i in points.indices.dropLast() {
                  let a = points[i]
                  let b = points[i + 1]
                  let dt = Double(b.time - a.time) / 3
                  line.addCurve(
                    to: CGPoint(x: x(b.time), y: y(b.db)),
                    control1: CGPoint(x: x(a.time + Int64(dt)), y: y(a.db + tangent(i) * dt)),
                    control2: CGPoint(x: x(b.time - Int64(dt)), y: y(b.db - tangent(i + 1) * dt)))
                }
                clipped.stroke(
                  line, with: .color(Palette.orange),
                  style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
              }
            }
            if let threshold = chart.threshold {
              var line = Path()
              line.move(to: CGPoint(x: plot.minX, y: y(threshold)))
              line.addLine(to: CGPoint(x: plot.maxX, y: y(threshold)))
              context.stroke(
                line, with: .color(Palette.ink.opacity(0.7)),
                style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
            for event in events where event.type == "LOUD_NOISE" {
              let point = CGPoint(
                x: x(event.tsMs), y: y(event.metadata.peakDb ?? chart.threshold ?? chart.bottom))
              context.fill(
                Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6)),
                with: .color(Palette.orange))
            }
            for i in 0...3 {
              let time = chart.from + (chart.to - chart.from) * Int64(i) / 3
              context.draw(
                Text(
                  Date(timeIntervalSince1970: Double(time) / 1000).formatted(
                    .dateTime.hour().minute())
                ).font(.system(size: 9, design: .monospaced)).foregroundColor(Palette.secondary),
                at: CGPoint(x: x(time), y: plot.maxY + 16),
                anchor: i == 0 ? .leading : i == 3 ? .trailing : .center)
            }
          }.frame(height: 190).background(
            GeometryReader { geometry in
              Color.clear.onAppear { plotWidth = geometry.size.width - 40 }.onChange(
                of: geometry.size.width
              ) { plotWidth = $0 - 40 }
            }
          ).contentShape(Rectangle()).gesture(
            DragGesture(minimumDistance: 0).onChanged { value in
              let fraction = min(1, max(0, (value.location.x - 34) / max(1, plotWidth)))
              let at = chart.from + Int64(Double(chart.to - chart.from) * fraction)
              if let sample = chart.samples.first(where: { $0.startMs <= at && $0.endMs > at }) {
                inspection =
                  "\(Date(timeIntervalSince1970:Double(at)/1000).formatted(.dateTime.hour().minute())) · peak \(String(format:"%.1f",sample.peakDb)) dBFS · \(sample.thresholdDb.map { "alert level \(String(format:"%.1f",$0)) dBFS" } ?? "calibrating")"
              } else {
                inspection = "No measurements at this time"
              }
            }
          ).accessibilityLabel(
            "Sound timeline, \(hours) hours. \(chart.coveredMs/60000) minutes measured. Grey intervals mean no measurements."
          )
          HStack(spacing: 12) {
            Label("Peak trend", systemImage: "waveform.path").foregroundStyle(Palette.orange)
            Text("- - Latest alert level").foregroundStyle(Palette.secondary)
          }.font(.system(size: 10))
          if let inspection {
            Text(inspection).font(.system(size: 11, design: .monospaced)).foregroundStyle(
              Palette.ink)
          }
          HStack {
            Text(
              chart.samples.isEmpty
                ? "No measurements yet" : "\(chart.coveredMs/60000) min measured · grey = no data"
            ).font(.system(size: 10)).foregroundStyle(Palette.secondary)
            Spacer()
            Button("Sound level guide") { guide = true }.font(.system(size: 10)).frame(
              minHeight: 32)
          }
          if !model.noiseCurrent {
            Text(model.noiseError ?? "Saved history · waiting for refresh").font(.system(size: 11))
              .foregroundStyle(Palette.secondary)
          }
        }
      }
    }
  }
}
