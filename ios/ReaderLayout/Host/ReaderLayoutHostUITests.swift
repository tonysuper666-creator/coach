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
        app.buttons["poseMenu"].tap()
        app.buttons["pose-展开竖拿"].tap()
        app.buttons["poseMenu"].tap()
        app.buttons["自动选择"].tap()
        app.buttons["PDF"].tap()
        XCTAssertTrue(app.staticTexts["第 2 页"].exists)
        XCTAssertEqual(note.value as? String, "sample")
        XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
        XCTAssertTrue(app.staticTexts["直板布局 · EPUB 单页"].exists)
    }

    func testManualPosesRebuildCanvasButRetainReadingState() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["下一页"].tap()
        app.switches["标记这一段"].tap()
        app.textFields["写下笔记"].tap()
        app.textFields["写下笔记"].typeText("sample")

        let canvas = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "canvasInstance-")).firstMatch
        XCTAssertTrue(canvas.exists)
        var previous = canvas.identifier
        for name in ["展开竖拿", "展开横拿", "半折书本", "半折笔记本", "平放桌面", "合上"] {
            app.buttons["poseMenu"].tap()
            app.buttons["pose-\(name)"].tap()
            XCTAssertTrue(canvas.exists)
            let current = canvas.identifier
            XCTAssertNotEqual(previous, current, "canvas should be recreated for \(name)")
            previous = current
            XCTAssertTrue(app.staticTexts["第 2 页"].exists)
            XCTAssertEqual(app.textFields["写下笔记"].value as? String, "sample")
            XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
        }
        app.buttons["poseMenu"].tap()
        app.buttons["自动选择"].tap()
        XCTAssertTrue(app.staticTexts["直板布局 · PDF 列聚焦"].exists)
    }
}
