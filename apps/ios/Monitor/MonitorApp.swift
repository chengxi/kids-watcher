import AVFoundation
import MonitorCore
import SwiftUI

@main
struct KidsMonitorApp: App {
  @StateObject private var model = MonitorModel()
  @Environment(\.scenePhase) private var phase
  var body: some Scene {
    WindowGroup {
      MonitorRoot(model: model)
        .preferredColorScheme(.dark)
        .onChange(of: phase) { model.scene($0) }
    }
  }
}

enum Palette {
  static let ink = Color(red: 242 / 255, green: 242 / 255, blue: 238 / 255)
  static let secondary = Color(red: 189 / 255, green: 189 / 255, blue: 182 / 255)
  static let line = Color(white: 0.15)
  static let orange = Color(red: 240 / 255, green: 106 / 255, blue: 58 / 255)
  static let green = Color(red: 30 / 255, green: 158 / 255, blue: 87 / 255)
  static let unknown = Color(red: 163 / 255, green: 163 / 255, blue: 156 / 255)
  static let red = Color(red: 1, green: 138 / 255, blue: 128 / 255)
}
struct PrimaryButton: View {
  let title: String
  var disabled = false
  let action: () -> Void
  var body: some View {
    Button(action: action) {
      Text(title).font(.system(size: 17, weight: .medium)).frame(maxWidth: .infinity).frame(
        height: 56)
    }
    .buttonStyle(.plain).foregroundStyle(Color(white: 0.067))
    .background(Palette.ink.opacity(disabled ? 0.3 : 1), in: RoundedRectangle(cornerRadius: 14))
    .disabled(disabled)
  }
}
struct MonitorRoot: View {
  @ObservedObject var model: MonitorModel
  var body: some View {
    Group {
      if model.credentials == nil { SetupView(model: model) } else { Dashboard(model: model) }
    }
    .foregroundStyle(Palette.ink).tint(Palette.ink)
    .frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.black.ignoresSafeArea())
  }
}
struct SetupView: View {
  @ObservedObject var model: MonitorModel
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        Text("MONITOR SETUP").font(.system(size: 12, weight: .medium)).foregroundStyle(
          Palette.secondary)
        Text(model.setup == "name" ? "Name this monitor" : "Connect to home hub").font(
          .system(size: 28))
        if model.setup == "name" {
          Text(
            "Keep this device name or edit it to a name you will recognize on the parent phone. Use a different name for each monitor, even if the phones are the same model."
          ).font(.system(size: 14))
          input("Monitor name", value: $model.name, placeholder: "iPhone 13 Pro")
          Text(
            model.name.isEmpty
              ? "1–64 characters" : monitorNameError(model.name) ?? "1–64 characters"
          ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
          PrimaryButton(title: "Continue to pairing", disabled: monitorNameError(model.name) != nil)
          { model.continueName() }
        } else {
          Text("Monitor: \(model.name)").font(.system(size: 16, weight: .medium))
          Button("Change monitor name") {
            model.setup = "name"
            model.error = nil
            model.fingerprint = nil
          }.disabled(model.busy).font(.system(size: 14))
          if model.busy {
            ProgressView()
            Text("Connecting to the hub…").font(.system(size: 14))
          } else if model.setup == "confirm" {
            Text("Does the hub show these six characters?")
            Text(String((model.fingerprint ?? "").prefix(6)).uppercased()).font(
              .system(size: 36, design: .monospaced))
            Text(
              "The pair command prints them on the line that starts with “check:”. If they are different, someone else is answering at this address."
            ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
            PrimaryButton(title: "They match - pair") { model.confirm() }
            Button("They do not match") { model.mismatch() }.frame(maxWidth: .infinity).padding(16)
              .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.secondary))
          } else if model.setup == "manual" {
            Text(
              "Enter the address and single-use code printed by your hub. The code expires after five minutes."
            ).font(.system(size: 14))
            input("1 · Hub address", value: $model.host, placeholder: "192.168.1.100")
            input("Port", value: $model.port, placeholder: "8444", numeric: true)
            input(
              "2 · Six-digit pairing code", value: $model.code, placeholder: "123 456",
              numeric: true)
            PrimaryButton(title: "Continue") { model.probe() }
            Button("Scan a QR code instead") {
              model.setup = "qr"
              model.error = nil
            }
          } else {
            Text(
              "Run the monitor pair command on your hub and scan the QR code it prints. The code is single-use and expires after five minutes."
            ).font(.system(size: 14))
            QRScanner(onScan: model.pairQR).frame(height: 300).clipShape(
              RoundedRectangle(cornerRadius: 14))
            PrimaryButton(title: "Enter address & pairing code") {
              model.setup = "manual"
              model.error = nil
            }
          }
        }
        if let error = model.error {
          Text(error).font(.system(size: 14)).foregroundStyle(Palette.red).padding(16).frame(
            maxWidth: .infinity, alignment: .leading
          ).background(
            Color(red: 0.24, green: 0.09, blue: 0.08), in: RoundedRectangle(cornerRadius: 14))
        }
      }.padding(24).frame(maxWidth: 640, alignment: .leading).frame(maxWidth: .infinity)
    }.scrollDismissesKeyboard(.interactively)
  }
  private func input(
    _ label: String, value: Binding<String>, placeholder: String, numeric: Bool = false
  ) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(label).font(.system(size: 12)).foregroundStyle(Palette.secondary)
      TextField(placeholder, text: value).textInputAutocapitalization(.never)
        .autocorrectionDisabled().keyboardType(numeric ? .numberPad : .default).font(
          .system(size: 16))
    }.padding(16).overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.secondary))
  }
}
struct Dashboard: View {
  @ObservedObject var model: MonitorModel
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      ScrollView {
        if landscape {
          HStack(alignment: .top, spacing: 32) {
            VStack(alignment: .leading, spacing: 20) {
              state
              controls
            }.frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 20) {
              meter
              diagnostics
            }.frame(maxWidth: .infinity)
          }.padding(24)
        } else {
          VStack(alignment: .leading, spacing: 24) {
            state
            meter
            diagnostics
            controls
          }.padding(24)
        }
      }
    }
  }
  private var state: some View {
    VStack(alignment: .leading, spacing: 20) {
      Text(model.credentials?.name ?? "Room monitor").font(.system(size: 14)).foregroundStyle(
        Palette.secondary)
      HStack(spacing: 16) {
        Text(model.listening && model.connected ? "✓" : "—").font(.system(size: 30))
          .frame(width: 64, height: 64).overlay(
            Circle().stroke(
              model.listening && model.connected ? Palette.ink : Palette.unknown, lineWidth: 2))
        VStack(alignment: .leading, spacing: 4) {
          Text(model.listening ? "Monitoring" : "Ready to monitor").font(.system(size: 26))
          Text(
            !model.listening
              ? "Tap Start to listen to this room"
              : model.connected ? "Connected via home hub" : "Hub disconnected · alerts unavailable"
          )
          .font(.system(size: 12)).foregroundStyle(
            model.listening && !model.connected ? Palette.red : Palette.secondary)
        }
      }
      if model.talking {
        Text("Parent speaking · speaker on").font(.system(size: 14, weight: .medium))
          .foregroundStyle(Palette.orange)
      }
      if model.watching {
        HStack(spacing: 12) {
          Circle().fill(Palette.orange).frame(width: 8, height: 8)
          VStack(alignment: .leading, spacing: 4) {
            Text("Camera on · a parent is watching").font(.system(size: 14, weight: .medium))
            Text("Live video and sound are being shared").font(.system(size: 12)).foregroundStyle(
              Palette.secondary)
          }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).overlay(
          RoundedRectangle(cornerRadius: 14).stroke(Palette.orange, lineWidth: 1.5))
      }
      if model.calibrating {
        Text("Calibrating — keep the room as it normally is…").font(.system(size: 14))
      }
      if let notice = model.videoNotice {
        Text(notice).font(.system(size: 12)).foregroundStyle(Palette.orange)
      }
      if let error = model.error {
        Text(error).font(.system(size: 14)).foregroundStyle(Palette.red)
      }
    }
  }
  private var meter: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack {
        Text("Hearing now").font(.system(size: 12)).foregroundStyle(Palette.secondary)
        Spacer()
        Text(model.listening ? format(model.level) : "—").font(
          .system(size: 30, design: .monospaced)
        ).minimumScaleFactor(0.7)
      }
      HStack(spacing: 3) {
        ForEach(0..<20) { index in
          let position = Double(index + 1) / 20
          let trigger = model.floor > silenceDb ? normalize(model.floor + model.threshold) : 1
          Rectangle().fill(
            position <= (model.listening ? normalize(model.level) : 0)
              ? (position >= trigger ? Palette.orange : Palette.ink)
              : (position >= trigger && model.listening
                ? Color(red: 74 / 255, green: 36 / 255, blue: 22 / 255) : Palette.line))
        }
      }.frame(height: 22).accessibilityLabel("Sound level \(format(model.level))")
      Text(
        model.floor <= silenceDb
          ? "alerts above: not measured yet"
          : "alerts above \(format(model.floor + model.threshold))"
      ).font(.system(size: 12)).foregroundStyle(Palette.orange)
      Text(
        "Room floor \(model.floor <= silenceDb ? "not measured yet" : format(model.floor)) · threshold +\(Int(model.threshold)) dB"
      ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
    }
  }
  private var diagnostics: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text("DIAGNOSTICS").font(.system(size: 12, weight: .medium)).foregroundStyle(
        Palette.secondary)
      VStack(spacing: 0) {
        row(
          "Home hub", model.connected ? "connected" : model.listening ? "disconnected" : "stopped",
          model.connected ? Palette.green : model.listening ? Palette.red : Palette.unknown)
        row(
          "Microphone", model.listening ? "listening" : "stopped",
          model.listening ? Palette.green : Palette.unknown)
        row(
          "Camera", model.watching ? "parent watching" : "idle",
          model.watching ? Palette.orange : Palette.unknown)
        row(
          "Power",
          model.battery.map { "\(model.charging ? "charging" : "unplugged") · \($0)%" }
            ?? "unavailable", model.battery == nil ? Palette.unknown : Palette.green)
        row("Temperature", "unavailable", Palette.unknown)
      }.padding(.horizontal, 14).overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.line))
      Text(model.credentials?.baseURL.absoluteString ?? "Home hub").font(
        .system(size: 12, design: .monospaced)
      ).foregroundStyle(Palette.secondary)
      Text(
        model.lastEvent.map { "Last alert: \($0.formatted(date: .omitted, time: .standard))" }
          ?? "No alerts yet"
      ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
    }
  }
  private var controls: some View {
    VStack(alignment: .leading, spacing: 12) {
      PrimaryButton(
        title: model.listening ? "Stop monitoring" : "Start monitoring", disabled: model.busy
      ) {
        if model.listening {
          model.stop()
        } else {
          Task {
            // Optional video access; microphone denial blocks Start, camera denial permits sound-only monitoring.
            if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
              _ = await AVCaptureDevice.requestAccess(for: .video)
            }
            model.start()
          }
        }
      }
      Text("Start this phone by hand after every restart.").font(.system(size: 12)).foregroundStyle(
        Palette.secondary)
      Text(
        "Keep this app open for live video. Sound monitoring can continue while locked. Set speaker volume with this phone’s volume buttons."
      ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
    }
  }
  private func row(_ label: String, _ value: String, _ color: Color) -> some View {
    VStack(spacing: 0) {
      HStack(spacing: 10) {
        Circle().fill(color).frame(width: 8, height: 8)
        Text(label).font(.system(size: 14))
        Spacer()
        Text(value).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.secondary)
      }.padding(.vertical, 12)
      Rectangle().fill(Palette.line).frame(height: 1)
    }
  }
  private func format(_ value: Double) -> String {
    value <= silenceDb ? "silent" : String(format: "%.1f dBFS", value)
  }
  private func normalize(_ value: Double) -> Double { min(1, max(0, (value + 80) / 80)) }
}
