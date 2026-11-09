import XCTest

final class ReaderLayoutHostUITests: XCTestCase {
    func testChangesKeepReadingState() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["下一页"].tap()
        app.switches["标记这一段"].tap()
        let note = app.textFields["写下笔记"]
        note.tap()
        note.typeText("sample")
        app.buttons["直板"].tap()
        app.buttons["自动"].tap()
        app.buttons["PDF"].tap()
        XCTAssertTrue(app.staticTexts["第 2 页"].exists)
        XCTAssertEqual(note.value as? String, "sample")
        XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
        XCTAssertTrue(app.staticTexts["直板布局 · EPUB 单页"].exists)
    }
}
