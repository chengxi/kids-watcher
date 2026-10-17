import MonitorCore
import SwiftUI
import UserNotifications

@main struct KidsParentApp: App {
  @StateObject private var model = ParentModel(
    start: ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil)
  @Environment(\.scenePhase) private var phase
  private let notifications = ParentNotificationDelegate()
  init() { UNUserNotificationCenter.current().delegate = notifications }
  var body: some Scene {
    WindowGroup {
      ParentRoot(model: model)
        .onChange(of: phase) {
          if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            model.scene($0)
          }
        }
        .task {
          #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--debug-pair") {
              let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("parent-pairing.json")
              if model.credentials == nil, let data = try? Data(contentsOf: url),
                let raw = String(data: data, encoding: .utf8)
              {
                try? FileManager.default.removeItem(at: url)
                model.pairQR(raw)
              }
            }
          #endif
        }
    }
  }
}
final class ParentNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
  func userNotificationCenter(
    _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) { completionHandler([.banner, .sound]) }
}
enum Palette {
  private static func adaptive(_ light: UIColor, _ dark: UIColor) -> Color {
    Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark : light })
  }
  static let ink = adaptive(
    UIColor(white: 0.10, alpha: 1),
    UIColor(red: 242 / 255, green: 242 / 255, blue: 238 / 255, alpha: 1))
  static let secondary = adaptive(UIColor(white: 0.36, alpha: 1), UIColor(white: 0.74, alpha: 1))
  static let line = adaptive(UIColor(white: 0.85, alpha: 1), UIColor(white: 0.15, alpha: 1))
  static let background = adaptive(.white, .black)
  static let surface = adaptive(UIColor(white: 0.99, alpha: 1), UIColor(white: 0.045, alpha: 1))
  static let onPrimary = adaptive(.white, UIColor(white: 0.067, alpha: 1))
  static let orange = adaptive(
    UIColor(red: 185 / 255, green: 62 / 255, blue: 10 / 255, alpha: 1),
    UIColor(red: 240 / 255, green: 106 / 255, blue: 58 / 255, alpha: 1))
  static let green = Color(red: 30 / 255, green: 158 / 255, blue: 87 / 255)
  static let unknown = Color(white: 0.55)
  static let red = adaptive(
    UIColor(red: 210 / 255, green: 56 / 255, blue: 43 / 255, alpha: 1),
    UIColor(red: 1, green: 138 / 255, blue: 128 / 255, alpha: 1))
}
struct ParentButton: View {
  let title: String, icon: String
  var disabled = false, accent = false
  let action: () -> Void
  var body: some View {
    Button(action: action) {
      Label(title, systemImage: icon).font(.system(size: 17, weight: .medium)).frame(
        maxWidth: .infinity
      ).frame(minHeight: 54)
    }.buttonStyle(.plain).foregroundStyle(disabled ? Palette.secondary : Palette.onPrimary)
      .background(
        disabled ? Palette.line : accent ? Palette.orange : Palette.ink,
        in: RoundedRectangle(cornerRadius: 14)
      ).disabled(disabled)
  }
}
struct ParentPanel<Content: View>: View {
  @ViewBuilder var content: Content
  var body: some View {
    content.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(
      Palette.surface, in: RoundedRectangle(cornerRadius: 18)
    ).overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.line))
  }
}
struct ParentRoot: View {
  @ObservedObject var model: ParentModel
  var body: some View {
    Group {
      if model.credentials == nil {
        ParentSetup(model: model)
      } else if model.watching != nil {
        ParentLive(model: model)
      } else {
        ParentHome(model: model)
      }
    }.foregroundStyle(Palette.ink).tint(Palette.ink).frame(
      maxWidth: .infinity, maxHeight: .infinity
    ).background(Palette.background.ignoresSafeArea()).preferredColorScheme(
      model.watching == nil ? .light : .dark
    )
    .alert(
      "Kid Monitor",
      isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })
    ) {
      Button("OK") { model.notice = nil }
    } message: {
      Text(model.notice ?? "")
    }
  }
}
struct ParentSetup: View {
  @ObservedObject var model: ParentModel
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 22) {
        Image(systemName: "house.fill").font(.system(size: 32)).foregroundStyle(Palette.orange)
          .padding(.top, 24)
        Text("PARENT SETUP").font(.system(size: 12, weight: .semibold)).tracking(2).foregroundStyle(
          Palette.secondary)
        Text("A little peace\nof mind.").font(.system(size: 38, weight: .medium)).fixedSize(
          horizontal: false, vertical: true)
        Text(
          "Connect to your home hub to watch your monitors, hear the room, and see what happened."
        ).font(.system(size: 16)).foregroundStyle(Palette.secondary)
        ParentPanel {
          VStack(alignment: .leading, spacing: 18) {
            Text("Connect to home hub").font(.system(size: 20, weight: .medium))
            if model.busy {
              HStack {
                ProgressView()
                Text("Pairing securely…")
              }
            } else if model.setup == "confirm" {
              Text("Does the hub's check line show these six characters?")
              Text(String((model.fingerprint ?? "").prefix(6)).uppercased()).font(
                .system(size: 34, design: .monospaced)
              ).tracking(5)
              Text("Confirm the certificate before sending your code.").font(.footnote)
                .foregroundStyle(Palette.secondary)
              ParentButton(title: "They match — pair", icon: "lock.shield") { model.pair() }
              Button("They do not match") {
                model.fingerprint = nil
                model.setup = "manual"
              }.frame(maxWidth: .infinity, minHeight: 44)
            } else if model.setup == "qr" {
              Text("Scan a parent pairing QR from your hub. Codes expire after five minutes.").font(
                .subheadline
              ).foregroundStyle(Palette.secondary)
              QRScanner(onScan: model.pairQR).frame(height: 240).clipShape(
                RoundedRectangle(cornerRadius: 12))
              Button("Enter address & pairing code") { model.setup = "manual" }.frame(minHeight: 44)
            } else {
              Text("Use a parent pairing code from your hub. Keep both phones on home Wi-Fi.").font(
                .subheadline
              ).foregroundStyle(Palette.secondary)
              field("Hub address", text: $model.host, prompt: "192.168.1.100")
              field("Port", text: $model.port, prompt: "8444", numeric: true)
              field("Pairing code", text: $model.code, prompt: "123 456", numeric: true)
              ParentButton(title: "Continue", icon: "arrow.right") { model.probe() }
              Button {
                model.setup = "qr"
              } label: {
                Label("Scan a QR code", systemImage: "qrcode.viewfinder")
              }.frame(maxWidth: .infinity, minHeight: 44)
            }
          }
        }
        if let error = model.error {
          Text(error).foregroundStyle(Palette.red).font(.subheadline).accessibilityIdentifier(
            "pairingError")
        }
        Label("Private. Local. On your home Wi-Fi.", systemImage: "lock").font(.footnote)
          .foregroundStyle(Palette.secondary)
      }.padding(22).frame(maxWidth: 600)
    }.scrollDismissesKeyboard(.interactively)
  }
  private func field(_ label: String, text: Binding<String>, prompt: String, numeric: Bool = false)
    -> some View
  {
    VStack(alignment: .leading, spacing: 8) {
      Text(label).font(.system(size: 12, weight: .medium)).foregroundStyle(Palette.secondary)
      TextField(prompt, text: text).font(.system(size: 17, design: .monospaced))
        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(
          numeric ? .numberPad : .URL
        )
        .padding(14).background(Palette.line, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier(label)
    }
  }
}
struct ParentHome: View {
  @ObservedObject var model: ParentModel
  @State private var status = false
  @State private var guide = false
  var body: some View {
    ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 20, pinnedViews: [.sectionHeaders]) {
          HStack {
            Text("Kid Monitor").font(.system(size: 24, weight: .medium))
            Spacer()
            Button {
              status = true
            } label: {
              HStack(spacing: 7) {
                Image(systemName: "network")
                Circle().fill(
                  model.statusHealthy
                    ? Palette.green : model.statusProblem ? Palette.red : Palette.unknown
                ).frame(width: 7, height: 7)
                Text("Status").font(.system(size: 13))
              }
            }.frame(minHeight: 44).accessibilityIdentifier("Status")
          }
          if !model.monitors.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
              HStack(spacing: 8) {
                ForEach(model.monitors) { monitor in
                  Button {
                    model.selectedId = monitor.id
                  } label: {
                    HStack(spacing: 8) {
                      Circle().fill(
                        !model.connected || !model.currentStatus
                          ? Palette.unknown : monitor.online ? Palette.green : Palette.red
                      ).frame(width: 7, height: 7)
                      Text(monitor.name).font(.system(size: 14, weight: .medium))
                    }
                    .padding(.horizontal, 16).frame(minHeight: 42).foregroundStyle(
                      model.selected?.id == monitor.id ? Palette.onPrimary : Palette.ink
                    )
                    .background(
                      model.selected?.id == monitor.id ? Palette.ink : Palette.line, in: Capsule())
                  }.buttonStyle(.plain)
                }
              }
            }
            if let monitor = model.selected {
              ParentPanel {
                VStack(alignment: .leading, spacing: 20) {
                  HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 7) {
                      Text(monitor.name).font(.system(size: 23, weight: .medium))
                      HStack(spacing: 6) {
                        Circle().fill(model.canWatch ? Palette.green : Palette.unknown).frame(
                          width: 6, height: 6)
                        Text(
                          !model.connected || !model.currentStatus
                            ? "Availability unknown"
                            : monitor.online ? "Online · ready to watch" : "Offline · check monitor"
                        ).font(.system(size: 13)).foregroundStyle(Palette.secondary)
                      }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 5) {
                      Image(
                        systemName: monitor.charging == true
                          ? "battery.100percent.bolt" : "battery.75percent")
                      Text(model.battery).font(.system(size: 12, design: .monospaced))
                    }.foregroundStyle(Palette.secondary)
                  }
                  ParentButton(title: "Watch live", icon: "video", disabled: !model.canWatch) {
                    model.watch(monitor)
                  }.accessibilityIdentifier("Watch live")
                }
              }
            }
          } else {
            ParentPanel {
              VStack(alignment: .leading, spacing: 12) {
                Image(systemName: "video.badge.plus").font(.title)
                Text(
                  model.connected && model.currentStatus
                    ? "No monitors paired" : "Waiting for monitors"
                ).font(.title3)
                Text("Pair a monitor phone with your home hub to see it here.").font(.subheadline)
                  .foregroundStyle(Palette.secondary)
              }
            }
          }
          if !model.connected {
            Label(model.connectionText, systemImage: "wifi.exclamationmark").font(.footnote)
              .foregroundStyle(Palette.orange)
          }
          if let error = model.error {
            HStack {
              Text(error).font(.footnote).foregroundStyle(Palette.red)
              Spacer()
              Button("Dismiss") { model.error = nil }
            }
          }
          ParentNoiseView(model: model, guide: $guide)
          Section {
            if model.events.isEmpty {
              Text(model.historyBusy ? "Loading events…" : "No events match these filters.").font(
                .subheadline
              ).foregroundStyle(Palette.secondary).padding(.vertical, 24)
            }
            ForEach(Array(model.events.enumerated()), id: \.element.id) { index, event in
              if index == 0
                || !Calendar.current.isDate(
                  Date(timeIntervalSince1970: Double(event.tsMs) / 1000),
                  inSameDayAs: Date(
                    timeIntervalSince1970: Double(model.events[index - 1].tsMs) / 1000))
              {
                Text(
                  Date(timeIntervalSince1970: Double(event.tsMs) / 1000).formatted(
                    .dateTime.weekday(.wide).month(.abbreviated).day()
                  ).uppercased()
                ).font(.system(size: 11, weight: .medium)).tracking(1).foregroundStyle(
                  Palette.secondary
                ).padding(.top, 6)
              }
              ParentEventRow(
                event: event,
                name: model.monitors.first { $0.id == event.monitorId }?.name
                  ?? "Monitor \(event.monitorId.prefix(8))")
            }
            if !model.events.isEmpty {
              HStack {
                if model.eventOffset > 0 {
                  Button("Newer events") {
                    model.loadNewer()
                    proxy.scrollTo("events", anchor: .top)
                  }
                }
                Spacer()
                if model.moreEvents {
                  Button("Older events") {
                    model.loadOlder()
                    proxy.scrollTo("events", anchor: .top)
                  }
                }
              }.font(.subheadline).frame(minHeight: 44)
            }
          } header: {
            VStack(alignment: .leading, spacing: 12) {
              HStack {
                Text("Events").font(.system(size: 20, weight: .medium))
                Spacer()
                if model.historyBusy { ProgressView().scaleEffect(0.7) }
              }.id("events")
              HStack(spacing: 10) {
                Menu {
                  Button("All devices") {
                    model.deviceFilter = nil
                    model.loadEvents()
                    proxy.scrollTo("events", anchor: .top)
                  }
                  ForEach(model.monitors) { monitor in
                    Button(monitor.name) {
                      model.deviceFilter = monitor.id
                      model.loadEvents()
                      proxy.scrollTo("events", anchor: .top)
                    }
                  }
                } label: {
                  filterLabel(
                    model.monitors.first { $0.id == model.deviceFilter }?.name ?? "All devices")
                }
                Menu {
                  ForEach(["All events", "Noise", "Movement", "Monitoring"], id: \.self) {
                    category in
                    Button(category) {
                      model.categoryFilter = category == "All events" ? nil : category
                      model.loadEvents()
                      proxy.scrollTo("events", anchor: .top)
                    }
                  }
                } label: {
                  filterLabel(model.categoryFilter ?? "All events")
                }
              }
            }.padding(.vertical, 12).background(Palette.background)
          }
          Text(
            "Alerts arrive while this app is open. iOS pauses delivery in the background; missed events sync when you return."
          ).font(.system(size: 12)).foregroundStyle(Palette.secondary).padding(.vertical, 12)
        }.padding(.horizontal, 20).padding(.bottom, 20).frame(maxWidth: 850)
      }.refreshable { await model.refresh() }
    }.sheet(isPresented: $status) { ParentStatus(model: model) }.sheet(isPresented: $guide) {
      ParentSoundGuide()
    }
  }
  private func filterLabel(_ text: String) -> some View {
    HStack {
      Text(text).lineLimit(1)
      Spacer()
      Image(systemName: "chevron.down").font(.system(size: 10))
    }.font(.system(size: 13)).padding(.horizontal, 13).frame(minHeight: 42).background(
      Palette.line, in: RoundedRectangle(cornerRadius: 11))
  }
}
struct ParentEventRow: View {
  let event: ParentEvent, name: String
  private var color: Color {
    event.type == "MONITOR_LOST"
      ? Palette.red : event.type == "MONITOR_ONLINE" ? Palette.green : Palette.secondary
  }
  var body: some View {
    ParentPanel {
      HStack(spacing: 12) {
        Text(
          Date(timeIntervalSince1970: Double(event.tsMs) / 1000).formatted(
            .dateTime.hour().minute())
        ).font(.system(size: 12, weight: .medium, design: .monospaced)).frame(
          width: 66, alignment: .leading)
        VStack(alignment: .leading, spacing: 5) {
          Text(name).font(.system(size: 14, weight: .medium))
          Text(event.title).font(.system(size: 13)).foregroundStyle(color)
        }
        Spacer(minLength: 0)
        if event.type == "LOUD_NOISE", let peak = event.metadata.peakDb {
          VStack(alignment: .trailing, spacing: 3) {
            Text(String(format: "%.0f", peak)).font(.system(size: 22, design: .monospaced))
              .foregroundStyle(Palette.orange)
            Text("dBFS").font(.system(size: 10)).foregroundStyle(Palette.secondary)
          }
        }
      }
    }.accessibilityElement(children: .combine)
  }
}
struct ParentStatus: View {
  @ObservedObject var model: ParentModel
  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 18) {
          ParentPanel {
            VStack(alignment: .leading, spacing: 10) {
              Label("Home hub", systemImage: "network").font(.headline)
              Text("\(model.credentials?.host ?? ""):\(model.credentials?.port ?? 0)").font(
                .system(.footnote, design: .monospaced))
              Text(model.connectionText).font(.subheadline).foregroundStyle(
                model.connected ? Palette.green : Palette.orange)
            }
          }
          ForEach(model.monitors) { monitor in
            ParentPanel {
              VStack(alignment: .leading, spacing: 10) {
                Text(monitor.name).font(.headline)
                Text(
                  !model.connected || !model.currentStatus
                    ? "Availability unknown" : monitor.online ? "Online" : "Offline"
                ).foregroundStyle(
                  !model.currentStatus
                    ? Palette.unknown : monitor.online ? Palette.green : Palette.red)
                if model.connected && model.currentStatus && monitor.online,
                  let battery = monitor.batteryPct
                {
                  Text("Battery \(battery)%\(monitor.charging == true ? " · charging" : "")")
                }
                if let seen = monitor.lastSeenMs {
                  Text("Last report: \(Date(timeIntervalSince1970:Double(seen)/1000).formatted())")
                    .font(.footnote).foregroundStyle(Palette.secondary)
                }
              }
            }
          }
          ParentPanel {
            VStack(alignment: .leading, spacing: 12) {
              Label("Keep the app open for alerts", systemImage: "iphone").font(.headline)
              Text(
                "This home Wi-Fi app receives live events while open. Background and lock-screen delivery are unavailable. It reloads missed history when you return."
              ).font(.subheadline).foregroundStyle(Palette.secondary)
              Button("Enable event notifications") { Task { await model.enableNotifications() } }
            }
          }
          ParentButton(title: "Refresh status", icon: "arrow.clockwise") {
            Task { await model.refresh() }
          }
        }.padding(20)
      }.background(Palette.background).navigationTitle("Status").navigationBarTitleDisplayMode(
        .inline)
    }.preferredColorScheme(.light).presentationDetents([.large])
  }
}
struct ParentSoundGuide: View {
  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 20) {
          Text("Understanding sound level").font(.title2)
          Text(
            "The chart shows uncalibrated dBFS from the monitor's microphone. Values closer to 0 are louder. Different phones and microphone processing can give different readings."
          )
          Text(
            "The faint line shows measured 15-second peaks. The orange curve is a duration-weighted visual average of those same peaks. The dashed line is the latest adaptive alert level, not the setting throughout history."
          )
          Text(
            "A loud sound must stay above the adaptive threshold for the configured duration to trigger an alert. Calibration temporarily has no threshold. Grey bands mean no measurements; they never mean silence."
          )
          Text("Tap the timeline to inspect a measurement and its historical threshold.")
            .foregroundStyle(Palette.orange)
        }.padding(24)
      }.background(Palette.background).navigationTitle("Sound level guide")
        .navigationBarTitleDisplayMode(.inline)
    }.preferredColorScheme(.light)
  }
}
