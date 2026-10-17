import SwiftUI

struct ParentLive: View {
  @ObservedObject var model: ParentModel
  @State private var info = false
  @State private var zoom: CGFloat = 1
  @State private var baseZoom: CGFloat = 1
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      VStack(spacing: 0) {
        HStack {
          Button {
            model.leaveLive()
          } label: {
            Image(systemName: "chevron.left").font(.system(size: 20)).frame(width: 44, height: 44)
          }.accessibilityLabel("Back to home")
          Spacer()
          VStack(spacing: 5) {
            Text(model.watching?.name ?? "Monitor").font(.system(size: 17, weight: .medium))
            HStack(spacing: 6) {
              Circle().fill(model.liveText == "Live" ? Palette.green : Palette.orange).frame(
                width: 6, height: 6)
              Text(model.liveText == "Live" ? "LIVE" : "CONNECTING").font(
                .system(size: 10, weight: .medium)
              ).tracking(1)
            }
          }
          Spacer()
          Button {
            info = true
          } label: {
            Image(systemName: "info.circle").font(.system(size: 20)).frame(width: 44, height: 44)
          }.accessibilityLabel("Monitor device information")
        }.padding(.horizontal, 10)
        if landscape {
          HStack(spacing: 20) {
            VStack(spacing: 0) {
              video.frame(maxWidth: .infinity, maxHeight: .infinity)
              controls
            }
            ScrollView {
              VStack(spacing: 18) {
                details
                talk
              }
            }.frame(width: 220)
          }.padding(12)
        } else {
          ScrollView {
            VStack(spacing: 20) {
              video.frame(height: geometry.size.width * 0.9)
              controls
              details.padding(.horizontal, 20)
            }
          }
          talk.padding(20)
        }
      }
    }.sheet(isPresented: $info) { ParentStatus(model: model) }
  }
  private var video: some View {
    GeometryReader { geometry in
      ZStack {
        Color(white: 0.035)
        if let image = model.image {
          Image(uiImage: image).resizable().scaledToFit().frame(
            width: geometry.size.width, height: geometry.size.height
          ).scaleEffect(zoom)
            .gesture(
              MagnificationGesture().onChanged { zoom = min(4, max(1, baseZoom * $0)) }.onEnded {
                _ in baseZoom = zoom
              }
            )
            .onTapGesture(count: 2) {
              zoom = 1
              baseZoom = 1
            }.accessibilityIdentifier("Live picture")
        } else {
          VStack(spacing: 16) {
            Image(systemName: model.audioAvailable ? "waveform" : "video").font(.system(size: 36))
              .foregroundStyle(Palette.secondary)
            Text(model.liveText).font(.subheadline).multilineTextAlignment(.center).padding(
              .horizontal, 16)
            if model.liveText.contains("…") { ProgressView() }
          }
        }
        VStack {
          Spacer()
          HStack {
            Spacer()
            Text(model.liveText).font(.system(size: 10, weight: .medium)).padding(8).background(
              Color.black.opacity(0.7), in: Capsule()
            ).padding(12)
          }
        }
      }.clipped()
    }
  }
  private var controls: some View {
    HStack {
      tool(
        model.muted ? "Unmute" : "Mute", icon: model.muted ? "speaker.slash" : "speaker.wave.2",
        disabled: !model.audioAvailable || model.talking
      ) { model.setMuted() }
      Spacer()
      tool(
        model.cameraPending ? "Switching" : "Switch", icon: "arrow.triangle.2.circlepath.camera",
        disabled: model.cameras.count < 2 || model.cameraPending
      ) { model.switchCamera() }
      Spacer()
      tool(
        model.photoBusy ? "Saving" : "Photo", icon: "camera",
        disabled: model.image == nil || model.photoBusy || model.liveText != "Live"
      ) { model.savePhoto() }
    }.padding(.horizontal, 24).padding(.vertical, 12).background(Color(white: 0.04))
  }
  private var details: some View {
    let monitor = model.monitors.first { $0.id == model.watching?.id }
    return VStack(alignment: .leading, spacing: 14) {
      HStack {
        Label(model.watching?.name ?? "Monitor", systemImage: "video").font(.system(size: 14))
        Spacer()
        Text(
          model.monitors.first { $0.id == model.watching?.id }.flatMap {
            $0.online && model.connected && model.currentStatus ? $0.batteryPct : nil
          }.map { "\($0)%" } ?? "—"
        ).font(.system(size: 12, design: .monospaced))
      }
      HStack {
        Text(
          model.talking
            ? "Room sound paused while talking"
            : model.audioAvailable
              ? "Room sound \(model.muted ? "muted" : "on")" : "Room audio unavailable"
        ).font(.system(size: 12)).foregroundStyle(Palette.secondary)
        Spacer()
      }
      if model.connected && model.currentStatus && monitor?.online == true
        && monitor?.charging == true
      {
        Label("Charging", systemImage: "battery.100percent.bolt").font(.system(size: 12))
          .foregroundStyle(Palette.secondary)
      }
      if model.liveText != "Live" {
        Text(model.liveText).font(.system(size: 13)).foregroundStyle(Palette.orange)
        HStack {
          Button("Retry video") { model.retryLive() }
          Spacer()
          Button("Sound only") { model.retryLive(audioOnly: true) }
        }.font(.system(size: 13)).frame(minHeight: 40)
      }
    }
  }
  private var talk: some View {
    VStack(spacing: 10) {
      ParentButton(
        title: model.talking
          ? "Stop talking" : model.talkPending ? "Connecting speaker…" : "Talk to monitor",
        icon: model.talking ? "stop.fill" : "mic",
        disabled: !model.connected || model.activeStreamId == nil, accent: model.talking
      ) { model.toggleTalk() }.accessibilityIdentifier("Talk to monitor")
      Text(
        model.talking
          ? "Your microphone is on · room sound is paused"
          : "Watching stops when you leave this screen"
      ).font(.system(size: 10)).foregroundStyle(Palette.secondary).multilineTextAlignment(.center)
    }
  }
  private func tool(_ title: String, icon: String, disabled: Bool, action: @escaping () -> Void)
    -> some View
  {
    Button(action: action) {
      VStack(spacing: 7) {
        Image(systemName: icon).font(.system(size: 21))
        Text(title).font(.system(size: 10))
      }.frame(minWidth: 56, minHeight: 48)
    }.disabled(disabled).foregroundStyle(disabled ? Palette.unknown : Palette.ink)
  }
}
