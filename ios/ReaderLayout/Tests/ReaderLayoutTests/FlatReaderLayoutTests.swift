import XCTest
@testable import ReaderLayout

final class FlatReaderLayoutTests: XCTestCase {
    func testNonFoldAndClosedUseSameBranchEvenWhenWide() {
        let make: (ReaderGeometry.Hinge) -> ReaderGeometry = {
            ReaderGeometry(width: 900, height: 500, hinge: $0, division: .none,
                           surface: .unknown, compactWidth: false)
        }
        XCTAssertEqual(ReaderPoseResolver.resolve(make(.unavailable)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(make(.closed)), .compact)
        XCTAssertEqual(ReaderPoseResolver.resolve(make(.closed), manual: .book), .book)
    }

    func testContentLayoutUsesAvailableContainer() {
        XCTAssertEqual(FlatReaderLayout.resolve(content: .pdf, width: 1000, height: 500), .pdfColumnFocus)
        XCTAssertEqual(FlatReaderLayout.resolve(content: .epub, width: 400, height: 800), .epubSinglePage)
        XCTAssertEqual(FlatReaderLayout.resolve(content: .epub, width: 600, height: 400), .epubSinglePage)
        XCTAssertEqual(FlatReaderLayout.resolve(content: .epub, width: 800, height: 500), .epubFacingPages)
        XCTAssertEqual(FlatReaderLayout.resolve(content: .epub, width: .nan, height: 500), .epubSinglePage)
    }
}
