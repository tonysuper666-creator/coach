import XCTest
@testable import ReaderLayout

final class ReaderPoseTests: XCTestCase {
    private func geometry(_ hinge: ReaderGeometry.Hinge = .open,
                          _ division: ReaderGeometry.Division = .none,
                          _ surface: ReaderGeometry.Surface = .other,
                          width: Double = 800, height: Double = 600,
                          compact: Bool = false) -> ReaderGeometry {
        ReaderGeometry(width: width, height: height, hinge: hinge, division: division,
                       surface: surface, compactWidth: compact)
    }

    func testSixSuggestionsAndGeometryTransitions() {
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.closed)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(width: 600, height: 800)), .openPortrait)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry()), .openLandscape)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.partiallyOpen, .vertical)), .book)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.partiallyOpen, .horizontal)), .laptop)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.open, .none, .faceUp)), .tabletop)
    }

    func testUncertainAndNarrowInputsDoNotPretendToBeHardwareFacts() {
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.partiallyOpen)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.unavailable, compact: true)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(.unavailable)), .openLandscape)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(width: .nan)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(geometry(width: 0)), .compact)
    }

    func testDivisionWinsDuringOutOfOrderEventsAndManualChoiceCanBeCleared() {
        let input = geometry(.closed, .vertical)
        XCTAssertEqual(ReaderPoseResolver.resolve(input), .book)
        XCTAssertEqual(ReaderPoseResolver.resolve(input, manual: .compact), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(input, manual: nil), .book)
    }
}
