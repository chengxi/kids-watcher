import Foundation
import MonitorCore

@MainActor
protocol HubTransport: AnyObject {
  var onConnected: (() -> Void)? { get set }
  var onDisconnected: (() -> Void)? { get set }
  var onControl: (([String: Any]) -> Void)? { get set }
  var onMedia: ((MediaFrame) -> Void)? { get set }
  var onState: ((String) -> Void)? { get set }
  var heartbeat: (() -> [String: Any])? { get set }
  func start()
  func stop()
  func send(_ value: [String: Any])
  func send(_ frame: MediaFrame)
  func discardMedia()
}
extension HubConnection: HubTransport {}

protocol AudioCapturing: AnyObject {
  var onWindows: (([[Int16]]) -> Void)? { get set }
  var onFailure: (() -> Void)? { get set }
  var isCapturing: Bool { get }
  func start() throws
  func stop()
  func startTalk() throws
  func play(_ data: Data) -> Bool
  func stopTalk()
}
extension AudioCapture: AudioCapturing {}

protocol CameraCapturing: AnyObject {
  var onFailure: (() -> Void)? { get set }
  func start(facing: String, onFrame: @escaping (Data, CameraFormat) -> Void) async -> CameraFormat?
  func cancel()
}
extension CameraCapture: CameraCapturing {}
