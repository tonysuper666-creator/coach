// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CoachConnection",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CoachConnection", targets: ["CoachConnection"])],
    targets: [
        .target(name: "CoachConnection"),
        .testTarget(name: "CoachConnectionTests", dependencies: ["CoachConnection"]),
    ]
)
