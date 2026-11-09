import XCTest
@testable import ReaderExperience

final class ReaderExperienceTests: XCTestCase {
    func testDraftSurvivesEncodingAndSourceRoundTrip() throws {
        var session = ReaderSession(draft: ReaderDraft(position: .pdf(pageIndex: 3, columnIndex: 0)))
        session.selectQuestion("q1")
        session.setAnswer("B", for: "q1")
        session.setNote("note", for: "a1")
        session.showSource(.verified(position: .pdf(pageIndex: 7, columnIndex: nil), passage: "synthetic sentence"))
        session.returnToQuestion()
        let restored = try JSONDecoder().decode(ReaderDraft.self, from: JSONEncoder().encode(session.draft))
        XCTAssertEqual(restored.position, .pdf(pageIndex: 3, columnIndex: 0))
        XCTAssertEqual(restored.answers["q1"], "B")
        XCTAssertEqual(restored.notes["a1"], "note")
        XCTAssertEqual(restored.selectedQuestionID, "q1")
        XCTAssertTrue(session.isQuestionListOpen)
    }

    func testPDFColumnsDoNotInventOrOverflowColumns() {
        var session = ReaderSession(draft: ReaderDraft(position: .pdf(pageIndex: 1, columnIndex: 0)))
        session.nextPDFColumn(columnCount: 2)
        session.nextPDFColumn(columnCount: 2)
        XCTAssertEqual(session.draft.position, .pdf(pageIndex: 1, columnIndex: 1))
        session.setPosition(.pdf(pageIndex: 1, columnIndex: nil))
        session.nextPDFColumn(columnCount: 2)
        XCTAssertEqual(session.draft.position, .pdf(pageIndex: 1, columnIndex: nil))
    }

    func testUnknownPageAndUnreliableSourceStayUnknown() {
        let locator = Data("synthetic locator".utf8)
        XCTAssertNil(PrintedPageList(pages: [:]).label(for: locator))
        XCTAssertEqual(PrintedPageList(pages: [locator: "112"]).label(for: locator), "112")
        XCTAssertEqual(SourceResolution.missing.message, "这一页没找到出处")
        XCTAssertEqual(SourceResolution.changedOrAmbiguous.message, "原句改过了，对不上")
        XCTAssertNotNil(SourceResolution.unavailable.message)
    }

    func testFileStoreRestoresAndRejectsStaleOrWrongScope() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("draft.json")
        let first = FileReaderDraftStore(url: url, scope: "synthetic-book")
        let stale = FileReaderDraftStore(url: url, scope: "synthetic-book")
        XCTAssertNil(try first.load())
        XCTAssertNil(try stale.load())
        let draft = ReaderDraft(position: .pdf(pageIndex: 2, columnIndex: 1), selectedQuestionID: "q", answers: ["q": "B"], notes: ["a": "note"])
        try first.save(draft)
        XCTAssertThrowsError(try stale.save(draft)) { XCTAssertEqual($0 as? FileReaderDraftStore.StoreError, .conflict) }
        XCTAssertEqual(try FileReaderDraftStore(url: url, scope: "synthetic-book").load(), draft)
        XCTAssertThrowsError(try FileReaderDraftStore(url: url, scope: "different-book").load()) { XCTAssertEqual($0 as? FileReaderDraftStore.StoreError, .wrongScope) }
    }

    func testPageContractDoesNotBecomeVerifiedSentence() throws {
        let json = #"{"docId":"synthetic","pdfPage":3,"bookPage":null,"text":"invented text"}"#.data(using: .utf8)!
        let page = try JSONDecoder().decode(CoachSourcePage.self, from: json)
        XCTAssertNil(page.bookPage)
        XCTAssertEqual(page.sourceWithoutSentenceAnchor(), .unavailable)
    }
}
