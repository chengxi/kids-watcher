import Darwin
import MonitorCore
import UIKit

@MainActor
struct DeviceName {
  static var current: String {
    var system = utsname()
    uname(&system)
    let capacity = MemoryLayout.size(ofValue: system.machine)
    var identifier = withUnsafePointer(to: &system.machine) { pointer in
      pointer.withMemoryRebound(to: CChar.self, capacity: capacity) { String(cString: $0) }
    }
    #if targetEnvironment(simulator)
      identifier = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? identifier
    #endif
    return defaultMonitorName(modelIdentifier: identifier, fallback: UIDevice.current.model)
  }
}
