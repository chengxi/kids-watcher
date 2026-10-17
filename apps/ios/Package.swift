// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "MonitorCore", platforms: [.macOS(.v13), .iOS(.v16)], products: [.library(name: "MonitorCore", targets: ["MonitorCore"])], targets: [.target(name: "MonitorCore", path: "Core"), .testTarget(name: "CoreTests", dependencies: ["MonitorCore"], path: "Tests/CoreTests")])
