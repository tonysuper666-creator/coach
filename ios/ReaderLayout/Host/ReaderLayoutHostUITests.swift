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

    func testDrawerAndStateSurviveRotationAndPoseChanges() {
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launch()
        app.buttons["下一页"].tap()
        app.switches["标记这一段"].tap()
        app.textFields["写下笔记"].tap()
        app.textFields["写下笔记"].typeText("sample\n")
        app.buttons["打开抽屉"].tap()
        showInDrawer(app.buttons["查看出处"], in: app)
        app.buttons["查看出处"].tap()
        showInDrawer(app.staticTexts["出处示例：这是一句自造的示例文字。"], in: app)
        capture(app, name: "portrait-source")

        XCUIDevice.shared.orientation = .landscapeLeft
        let rotated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.frame.width > app.frame.height
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [rotated], timeout: 10), .completed, "Window must actually rotate")
        XCTAssertTrue(app.buttons["返回题目"].waitForExistence(timeout: 5))
        showInDrawer(app.buttons["返回题目"], in: app)
        showInReader(app.staticTexts["sampleBody"], in: app)
        capture(app, name: "landscape-source")
        scrollTo(app.buttons["poseMenu"], in: app)
        app.buttons["poseMenu"].tap()
        app.buttons["pose-半折书本"].tap()
        XCTAssertTrue(app.buttons["返回题目"].exists)
        app.buttons["返回题目"].tap()
        showInDrawer(app.buttons["查看出处"], in: app)
        showInDrawer(app.buttons["收起"], in: app)
        app.buttons["收起"].tap()
        scrollTo(app.buttons["poseMenu"], in: app)
        app.buttons["poseMenu"].tap()
        app.buttons["自动选择"].tap()
        scrollTo(app.textFields["写下笔记"], in: app)
        XCTAssertEqual(app.textFields["写下笔记"].value as? String, "sample")
        XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
        XCTAssertTrue(app.staticTexts["第 2 页"].exists)
    }

    func testNarrowLargeTypeFixtureCanReachReaderAndDrawer() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-narrowFixture", "-largeTypeFixture"]
        app.launch()
        showInReader(app.staticTexts["sampleBody"], in: app)
        capture(app, name: "narrow-large-body")
        scrollTo(app.switches["标记这一段"], in: app)
        app.switches["标记这一段"].tap()
        scrollTo(app.textFields["写下笔记"], in: app)
        app.textFields["写下笔记"].tap()
        app.textFields["写下笔记"].typeText("sample\n")
        scrollTo(app.buttons["下一页"], in: app)
        assertVisible(app.buttons["下一页"], in: app)
        app.buttons["下一页"].tap()
        scrollTo(app.buttons["打开抽屉"], in: app)
        app.buttons["打开抽屉"].tap()
        app.scrollViews["sampleDrawer"].swipeUp()
        showInDrawer(app.staticTexts["题目示例：这段话讲了什么？"], in: app)
        showInDrawer(app.buttons["查看出处"], in: app)
        capture(app, name: "narrow-large-question")
        app.buttons["查看出处"].tap()
        app.scrollViews["sampleDrawer"].swipeUp()
        showInDrawer(app.buttons["返回题目"], in: app)
        capture(app, name: "narrow-large-source")
        app.buttons["返回题目"].tap()
        showInDrawer(app.buttons["收起"], in: app)
        app.buttons["收起"].tap()
        XCTAssertTrue(app.staticTexts["第 2 页"].exists)
        scrollTo(app.textFields["写下笔记"], in: app)
        XCTAssertEqual(app.textFields["写下笔记"].value as? String, "sample")
        XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
    }

    func testDrawerStateSurvivesWidthAndTypeChanges() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-adaptiveFixture"]
        app.launch()
        app.buttons["下一页"].tap()
        app.switches["标记这一段"].tap()
        app.textFields["写下笔记"].tap()
        app.textFields["写下笔记"].typeText("sample\n")
        app.buttons["打开抽屉"].tap()
        showInDrawer(app.buttons["查看出处"], in: app)
        app.buttons["查看出处"].tap()
        app.buttons["切换测试宽度"].tap()
        app.buttons["切换测试字号"].tap()
        app.scrollViews["sampleDrawer"].swipeUp()
        showInDrawer(app.buttons["返回题目"], in: app)
        capture(app, name: "resized-large-source")
        app.buttons["返回题目"].tap()
        showInDrawer(app.buttons["收起"], in: app)
        app.buttons["收起"].tap()
        scrollTo(app.textFields["写下笔记"], in: app)
        XCTAssertEqual(app.textFields["写下笔记"].value as? String, "sample")
        XCTAssertEqual(app.switches["标记这一段"].value as? String, "1")
        XCTAssertTrue(app.staticTexts["第 2 页"].exists)
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) {
        let scroll = app.scrollViews["readerScroll"]
        for _ in 0..<6 where !element.isHittable { scroll.swipeUp() }
        if !element.isHittable {
            for _ in 0..<6 where !element.isHittable { scroll.swipeDown() }
        }
        XCTAssertTrue(element.isHittable, "Element should be reachable: \(element)")
    }

    private func assertVisible(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        XCTAssertTrue(element.isHittable, "Element should be visible: \(element)")
        XCTAssertTrue(app.frame.contains(element.frame), "Element lies outside the app window: \(element.frame)")
    }

    private func showInDrawer(_ element: XCUIElement, in app: XCUIApplication) {
        let drawer = app.scrollViews["sampleDrawer"]
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        for _ in 0..<4 where !drawer.frame.contains(element.frame) { drawer.swipeUp() }
        for _ in 0..<4 where !drawer.frame.contains(element.frame) { drawer.swipeDown() }
        assertVisible(element, in: app)
        XCTAssertTrue(drawer.frame.contains(element.frame), "Element is clipped by drawer: \(element.frame)")
    }

    private func showInReader(_ element: XCUIElement, in app: XCUIApplication) {
        let reader = app.scrollViews["readerScroll"]
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        for _ in 0..<6 where !reader.frame.contains(element.frame) { reader.swipeUp() }
        for _ in 0..<6 where !reader.frame.contains(element.frame) { reader.swipeDown() }
        assertVisible(element, in: app)
        XCTAssertTrue(reader.frame.contains(element.frame), "Element is clipped by reader: \(element.frame)")
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
