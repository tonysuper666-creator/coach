import XCTest
import UIKit

final class ReaderHostUITests: XCTestCase {
    private func recordPosition(_ name: String, _ value: String) {
        let attachment = XCTAttachment(string: value)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func captureHighlight(_ name: String, app: XCUIApplication) {
        // Static WKWebView text can be visible without being hittable. Check
        // the source drawer's actual pixels rather than a tapability flag.
        let deadline = Date().addingTimeInterval(8)
        var screenshot = app.screenshot()
        while !hasYellowHighlight(screenshot.image), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.4))
            screenshot = app.screenshot()
        }
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(hasYellowHighlight(screenshot.image), "Source highlight must be visible in the drawer")
    }

    private func hasYellowHighlight(_ image: UIImage) -> Bool {
        guard let cgImage = image.cgImage else { return false }
        let width = cgImage.width, height = cgImage.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        var yellow = 0
        for y in (height / 2)..<height {
            for x in 0..<width {
                let i = (y * width + x) * 4
                let r = Int(pixels[i]), g = Int(pixels[i + 1]), b = Int(pixels[i + 2])
                if r > 230 && g > 190 && b < 210 && r - b > 30 && g - b > 20 { yellow += 1 }
            }
        }
        return yellow > 1000
    }

    func testPDFDrawerAndRestart() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture-scope=\(UUID().uuidString)"]
        app.launch()
        app.buttons["PDF"].tap()
        app.buttons["下一页"].tap()
        app.buttons["题目"].tap()
        let answer = app.textFields["answer"]
        XCTAssertTrue(answer.waitForExistence(timeout: 10))
        answer.tap(); answer.typeText("north")
        app.textFields["note"].tap(); app.textFields["note"].typeText("synthetic note")
        app.buttons["查看出处"].tap()
        XCTAssertTrue(app.staticTexts["source-verified"].waitForExistence(timeout: 10))
        captureHighlight("pdf-source", app: app)
        app.buttons["返回题目"].tap()
        XCTAssertEqual(answer.value as? String, "north")
        recordPosition("pdf-position-before-restart", app.staticTexts["pdf-position"].label)
        app.terminate()
        app.launch()
        XCTAssertEqual(app.staticTexts["pdf-position"].label, "PDF 2")
        recordPosition("pdf-position-after-restart", app.staticTexts["pdf-position"].label)
        app.buttons["题目"].tap()
        XCTAssertEqual(app.textFields["answer"].value as? String, "north")
        XCTAssertEqual(app.textFields["note"].value as? String, "synthetic note")
        app.buttons["查看出处"].tap()
        XCTAssertTrue(app.staticTexts["source-verified"].waitForExistence(timeout: 10))
        captureHighlight("pdf-source-after-restart", app: app)
    }

    func testEPUBSourceAfterFontChangeAndRestart() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture-scope=\(UUID().uuidString)"]
        app.launch()
        app.buttons["EPUB"].tap()
        app.buttons["题目"].tap()
        XCTAssertTrue(app.staticTexts["纸书页码无法确认"].waitForExistence(timeout: 15))
        app.textFields["note"].tap(); app.textFields["note"].typeText("synthetic epub note")
        app.buttons["查看出处"].tap()
        XCTAssertTrue(app.staticTexts["source-verified"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["暂时无法确认出处"].exists)
        let passage = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "The synthetic river bends north.")).firstMatch
        XCTAssertTrue(passage.waitForExistence(timeout: 20))
        captureHighlight("epub-source", app: app)
        app.buttons["返回题目"].tap()
        app.buttons["收起"].tap()
        app.buttons["大字号"].tap()
        app.buttons["题目"].tap()
        app.buttons["查看出处"].tap()
        XCTAssertTrue(app.staticTexts["source-verified"].waitForExistence(timeout: 20))
        let samePassage = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "The synthetic river bends north.")).firstMatch
        XCTAssertTrue(samePassage.waitForExistence(timeout: 20))
        captureHighlight("epub-source-large", app: app)
        app.buttons["返回题目"].tap()
        app.buttons["收起"].tap()
        let position = app.staticTexts["epub-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 15))
        let firstPosition = position.label
        app.buttons["下一页"].tap()
        let changed = NSPredicate(format: "label != %@", firstPosition)
        expectation(for: changed, evaluatedWith: position)
        waitForExpectations(timeout: 15)
        let savedPosition = position.label
        recordPosition("epub-position-before-restart", savedPosition)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["大字号"].waitForExistence(timeout: 15))
        let restored = NSPredicate(format: "label == %@", savedPosition)
        expectation(for: restored, evaluatedWith: app.staticTexts["epub-position"])
        waitForExpectations(timeout: 20)
        recordPosition("epub-position-after-restart", app.staticTexts["epub-position"].label)
        app.buttons["题目"].tap()
        XCTAssertEqual(app.textFields["note"].value as? String, "synthetic epub note")
        app.buttons["查看出处"].tap()
        XCTAssertTrue(app.staticTexts["source-verified"].waitForExistence(timeout: 20))
        captureHighlight("epub-source-after-restart", app: app)
    }
}
