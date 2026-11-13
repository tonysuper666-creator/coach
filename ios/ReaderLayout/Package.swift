// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ReaderLayout",
    platforms: [.iOS(.v17)],
    products: [.library(name: "ReaderLayout", targets: ["ReaderLayout"])],
    targets: [
        .target(name: "ReaderLayout"),
        .testTarget(name: "ReaderLayoutTests", dependencies: ["ReaderLayout"]),
    ]
)
