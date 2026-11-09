// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ReaderExperience",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ReaderExperience", targets: ["ReaderExperience"])],
    targets: [
        .target(name: "ReaderExperience"),
        .testTarget(name: "ReaderExperienceTests", dependencies: ["ReaderExperience"]),
    ]
)
