// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ReaderExperience",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ReaderExperience", targets: ["ReaderExperience"])],
    dependencies: [.package(url: "https://github.com/readium/swift-toolkit.git", exact: "3.11.1")],
    targets: [
        .target(name: "ReaderExperience", dependencies: [
            .product(name: "ReadiumShared", package: "swift-toolkit", condition: .when(platforms: [.iOS])),
            .product(name: "ReadiumStreamer", package: "swift-toolkit", condition: .when(platforms: [.iOS])),
            .product(name: "ReadiumNavigator", package: "swift-toolkit", condition: .when(platforms: [.iOS])),
        ]),
        .testTarget(name: "ReaderExperienceTests", dependencies: ["ReaderExperience"]),
    ]
)
