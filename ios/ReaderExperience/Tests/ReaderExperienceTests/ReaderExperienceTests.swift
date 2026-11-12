import XCTest
@testable import ReaderExperience
#if canImport(PDFKit)
import PDFKit
#endif

final class ReaderExperienceTests: XCTestCase {
    func testAnchoredNotesRemainIsolatedAfterReload() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = directory.appendingPathComponent("draft.json")
        let store = FileReaderDraftStore(url: url, scope: "synthetic")
        XCTAssertNil(try store.load())
        let a = ReaderDocumentID(document: "a", version: "1")
        let b = ReaderDocumentID(document: "b", version: "1")
        let a2 = ReaderDocumentID(document: "a", version: "2")
        let page = ReaderNoteLocation.pdf(pageIndex: 1, exactText: "same source")
        let otherPage = ReaderNoteLocation.pdf(pageIndex: 2, exactText: "same source")
        let anchor = ReaderNoteAnchor(document: a, location: page)
        var draft = ReaderDraft(position: .pdf(pageIndex: 1, columnIndex: 1), notes: ["source": "legacy"])
        draft.setNote("only a", at: anchor)
        draft.setNote("only b", at: ReaderNoteAnchor(document: b, location: page))
        draft.position = .pdf(pageIndex: 5, columnIndex: 0)
        try store.save(draft)
        let restored = try XCTUnwrap(FileReaderDraftStore(url: url, scope: "synthetic").load())
        XCTAssertEqual(restored.note(at: anchor), "only a")
        XCTAssertEqual(restored.note(at: ReaderNoteAnchor(document: b, location: page)), "only b")
        XCTAssertNil(restored.note(at: ReaderNoteAnchor(document: a2, location: page)))
        XCTAssertNil(restored.note(at: ReaderNoteAnchor(document: a, location: otherPage)))
        XCTAssertEqual(restored.notes["source"], "legacy")
        XCTAssertEqual(restored.position, .pdf(pageIndex: 5, columnIndex: 0))
        let first = try ReaderNoteLocation.epubLocator(Data(#"{"href":"one","locations":{"progression":0.5}}"#.utf8))
        let reordered = try ReaderNoteLocation.epubLocator(Data(#"{"locations":{"progression":0.5},"href":"one"}"#.utf8))
        let other = try ReaderNoteLocation.epubLocator(Data(#"{"href":"two","locations":{"progression":0.5}}"#.utf8))
        XCTAssertEqual(first, reordered)
        XCTAssertNotEqual(first, other)
        let legacy = Data(#"{"position":{"pdf":{"pageIndex":0}},"answers":{},"notes":{"source":"old"}}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(ReaderDraft.self, from: legacy).notes["source"], "old")
    }

    #if canImport(PDFKit)
    func testActualPDFCropCopiesAndRejectsWrongColumn() throws {
        let data = NSMutableData()
        let consumer = try XCTUnwrap(CGDataConsumer(data: data as CFMutableData))
        var media = CGRect(x: 0, y: 0, width: 600, height: 800)
        let context = try XCTUnwrap(CGContext(consumer: consumer, mediaBox: &media, nil))
        context.beginPDFPage(nil); context.endPDFPage(); context.closePDF()
        let document = try XCTUnwrap(PDFDocument(data: data as Data))
        let left = CGRect(x: 20, y: 20, width: 260, height: 760)
        let right = CGRect(x: 320, y: 20, width: 260, height: 760)
        let focus = try XCTUnwrap(PDFPageFocus(pageBounds: media, crop: media, columns: [left, right]))
        let copy = try XCTUnwrap(PDFPageCrop.copy(document, pageIndex: 0, bounds: try XCTUnwrap(focus.bounds(column: 1))))
        XCTAssertEqual(copy.page(at: 0)?.bounds(for: .cropBox), right)
        XCTAssertEqual(document.page(at: 0)?.bounds(for: .cropBox), media)
        XCTAssertNil(focus.bounds(column: 2))
        XCTAssertNil(PDFPageCrop.copy(document, pageIndex: 0, bounds: CGRect(x: 590, y: 0, width: 50, height: 100)))
        document.page(at: 0)?.rotation = 90
        XCTAssertNil(PDFPageCrop.copy(document, pageIndex: 0, bounds: left))
    }
    #endif
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
