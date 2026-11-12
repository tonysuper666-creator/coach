import SwiftUI
import PDFKit
import ReadiumShared
import ReadiumNavigator

private let sentence = "The synthetic river bends north."

@MainActor final class HostModel: NSObject, ObservableObject, EPUBNavigatorDelegate {
    private static let fixtureScope: String? = {
        let prefix = "--fixture-scope="
        guard let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
              let uuid = UUID(uuidString: String(argument.dropFirst(prefix.count))) else { return nil }
        return uuid.uuidString
    }()
    private static func preferenceKey(_ key: String) -> String {
        fixtureScope.map { "\(key)-\($0)" } ?? key
    }
    @Published var format = UserDefaults.standard.string(forKey: HostModel.preferenceKey("fixture-format")) ?? "PDF"
    @Published var pdfPage = 0
    @Published var pdfColumn: Int? = 0
    @Published var pdfDocumentIndex = UserDefaults.standard.integer(forKey: HostModel.preferenceKey("fixture-pdf-document"))
    @Published var noteReady = false
    @Published var drawer = false
    @Published var sourceOpen = false
    @Published var sourceReady = false
    @Published var message = ""
    @Published var selection: PDFSelection?
    @Published var epub: EPUBNavigatorViewController?
    @Published var sourceEPUB: EPUBNavigatorViewController?
    @Published var answer = ""
    @Published var note = ""
    @Published var fontSize = UserDefaults.standard.double(forKey: HostModel.preferenceKey("fixture-font-size")) == 0 ? 1.0 : UserDefaults.standard.double(forKey: HostModel.preferenceKey("fixture-font-size"))
    @Published var epubPosition = ""
    private let documents: [PDFKit.PDFDocument]
    var document: PDFKit.PDFDocument { documents[pdfDocumentIndex == 1 ? 1 : 0] }
    private var documentID: ReaderDocumentID { ReaderDocumentID(document: format == "PDF" ? "synthetic-pdf-\(pdfDocumentIndex)" : "synthetic-epub", version: "1") }
    private var activeNoteAnchor: ReaderNoteAnchor?
    var pdfFocusBounds: CGRect? {
        let crop = CGRect(x: 40, y: 40, width: 532, height: 712)
        return PDFPageFocus(pageBounds: CGRect(x: 0, y: 0, width: 612, height: 792), crop: crop, columns: [CGRect(x: 40, y: 40, width: 252, height: 712), CGRect(x: 320, y: 40, width: 252, height: 712)])?.bounds(column: pdfColumn)
    }
    private let pdfStore: FileReaderDraftStore
    private let epubStore: FileReaderDraftStore
    private var pdfDraft: ReaderDraft
    private var epubDraft: ReaderDraft
    private var service: EPUBReadingService?
    private var sourceLocator: Locator?
    private var sourceFlashed = false

    override init() {
        let baseDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("synthetic-reader")
        let directory = Self.fixtureScope.map { baseDirectory.appendingPathComponent("runs").appendingPathComponent($0) } ?? baseDirectory
        pdfStore = FileReaderDraftStore(url: directory.appendingPathComponent("pdf.json"), scope: "synthetic-pdf-v1")
        epubStore = FileReaderDraftStore(url: directory.appendingPathComponent("epub.json"), scope: "synthetic-epub-v1")
        pdfDraft = ReaderDraft(position: .pdf(pageIndex: 0, columnIndex: nil))
        epubDraft = ReaderDraft(position: .epub(locator: Data()))
        documents = (0..<2).map { documentIndex in
            let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
            let data = renderer.pdfData { context in
                for page in 0..<2 {
                    context.beginPage()
                    let left = "Synthetic PDF \(documentIndex), page \(page + 1) — left column"
                    let right = page == 1 ? sentence : "Right column on first page."
                    left.draw(in: CGRect(x: 50, y: 80, width: 230, height: 250), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
                    right.draw(in: CGRect(x: 330, y: 80, width: 230, height: 250), withAttributes: [.font: UIFont.systemFont(ofSize: 14)])
                }
            }
            return PDFKit.PDFDocument(data: data)!
        }
        super.init()
        do {
            pdfDraft = try pdfStore.load() ?? pdfDraft
            epubDraft = try epubStore.load() ?? epubDraft
            if case let .pdf(page, column) = pdfDraft.position { pdfPage = page; pdfColumn = column }
            loadFields()
        } catch { message = "草稿读取失败" }
    }

    func loadFields() {
        let draft = format == "PDF" ? pdfDraft : epubDraft
        answer = draft.answers["q1"] ?? ""
        activeNoteAnchor = nil; note = ""; noteReady = false
    }
    func persist() {
        do {
            if format == "PDF" {
                pdfDraft.position = .pdf(pageIndex: pdfPage, columnIndex: pdfColumn)
                pdfDraft.answers["q1"] = answer
                try pdfStore.save(pdfDraft)
            } else {
                epubDraft.answers["q1"] = answer
                try epubStore.save(epubDraft)
            }
        } catch { message = "草稿保存失败或有冲突" }
    }
    func changeDocument() {
        persist()
        pdfDocumentIndex = pdfDocumentIndex == 0 ? 1 : 0
        UserDefaults.standard.set(pdfDocumentIndex, forKey: Self.preferenceKey("fixture-pdf-document"))
        loadFields()
        if drawer { Task { await prepareNote() } }
    }
    func prepareNote() async {
        let requestedFormat = format
        let requestedDocument = documentID
        activeNoteAnchor = nil; note = ""; noteReady = false
        do {
            let location: ReaderNoteLocation
            if requestedFormat == "PDF" {
                guard PDFSourceMatcher(document: document).selection(pageIndex: 1, sentence: sentence).1 != nil else {
                    message = "暂时无法确认批注位置"; return
                }
                location = .pdf(pageIndex: 1, exactText: sentence)
            } else {
                await openEPUB()
                guard let service else { message = "暂时无法确认批注位置"; return }
                let match = try await service.exactLocator(sentence: sentence)
                guard let locator = match else { message = "暂时无法确认批注位置"; return }
                sourceLocator = locator
                location = try .epubLocator(locator.jsonData())
            }
            guard requestedFormat == format, requestedDocument == documentID else { return }
            let anchor = ReaderNoteAnchor(document: requestedDocument, location: location)
            activeNoteAnchor = anchor
            note = (format == "PDF" ? pdfDraft : epubDraft).note(at: anchor) ?? ""
            noteReady = true
        } catch { message = "暂时无法确认批注位置" }
    }
    func updateNote(_ text: String) {
        guard let anchor = activeNoteAnchor, noteReady, anchor.document == documentID else { return }
        note = text
        if format == "PDF" { pdfDraft.setNote(text, at: anchor) }
        else { epubDraft.setNote(text, at: anchor) }
        persist()
    }
    func returnToNote() async {
        guard let anchor = activeNoteAnchor, anchor.document == documentID else { return }
        switch anchor.location {
        case let .pdf(page, text):
            guard PDFSourceMatcher(document: document).selection(pageIndex: page, sentence: text).1 != nil else { message = "暂时无法确认批注位置"; return }
            pdfPage = page; pdfColumn = 1; drawer = false; persist()
        case let .epub(data):
            do {
                guard let service, let match = try await service.exactLocator(sentence: sentence),
                      try ReaderNoteLocation.epubLocator(match.jsonData()) == anchor.location else { message = "暂时无法确认批注位置"; return }
                guard let locator = try EPUBReadingService.decodeLocator(data),
                      let epub, await epub.go(to: locator) else { message = "暂时无法定位批注"; return }
                drawer = false
            } catch { message = "暂时无法确认批注位置" }
        }
    }
    func changeFormat(_ value: String) {
        persist(); format = value; UserDefaults.standard.set(value, forKey: Self.preferenceKey("fixture-format"))
        drawer = false; sourceOpen = false; loadFields()
        if value == "EPUB" { Task { await openEPUB() } }
    }
    func openEPUB() async {
        guard epub == nil else { return }
        do {
            let url = Bundle.main.url(forResource: "synthetic", withExtension: "epub")!
            let opened = try await EPUBReadingService.open(url: url)
            let locator: Locator?
            if case let .epub(data) = epubDraft.position, !data.isEmpty { locator = try EPUBReadingService.decodeLocator(data) }
            else { locator = nil }
            let navigator = try opened.navigator(locator: locator, fontSize: fontSize)
            navigator.delegate = self
            service = opened; epub = navigator
        } catch { message = "EPUB 无法打开" }
    }
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        guard let epub, (navigator as AnyObject) === epub else { return }
        do {
            epubDraft.position = .epub(locator: try locator.jsonData())
            epubPosition = "\(locator.href) | \(locator.locations.progression ?? 0)"
            if format == "EPUB" { persist() }
        } catch { message = "阅读位置保存失败" }
    }
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) { message = "阅读器出错" }
    func navigator(_ navigator: any ViewportObservingNavigator, viewportDidChange viewport: NavigatorViewport?) {
        guard let sourceEPUB, (navigator as AnyObject) === sourceEPUB, viewport != nil,
              !sourceFlashed, let sourceLocator else { return }
        sourceFlashed = true
        sourceEPUB.apply(decorations: [Decoration(id: "fixture-source", locator: sourceLocator, style: .highlight(tint: .systemYellow))], in: "source")
        sourceReady = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak sourceEPUB] in sourceEPUB?.apply(decorations: [], in: "source") }
    }
    func showSource() async {
        sourceOpen = true; sourceReady = false; sourceFlashed = false; message = ""
        if format == "PDF" {
            let result = PDFSourceMatcher(document: document).selection(pageIndex: 1, sentence: sentence)
            selection = result.1
            sourceReady = selection != nil
            if let error = result.0.message { message = error }
        } else {
            do {
                guard let service else { message = "暂时无法确认出处"; return }
                let match: Locator?
                if let sourceLocator { match = sourceLocator }
                else { match = try await service.exactLocator(sentence: sentence) }
                guard let locator = match else { message = "暂时无法确认出处"; return }
                sourceLocator = locator
                let source = try service.navigator(locator: locator, fontSize: fontSize)
                source.delegate = self
                sourceEPUB = source
            } catch { message = "暂时无法确认出处" }
        }
    }
    func largerText() {
        fontSize = 1.5
        UserDefaults.standard.set(fontSize, forKey: Self.preferenceKey("fixture-font-size"))
        epub?.submitPreferences(EPUBPreferences(columnCount: .one, fontSize: fontSize, scroll: false))
    }
}

@main struct ReaderHost: App {
    @StateObject private var model = HostModel()
    var body: some Scene { WindowGroup { HostView(model: model).task { if model.format == "EPUB" { await model.openEPUB() } } } }
}

struct HostView: View {
    @ObservedObject var model: HostModel
    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                HStack {
                    Button("PDF") { model.changeFormat("PDF") }
                    Button("EPUB") { model.changeFormat("EPUB") }
                    if model.format == "PDF" { Text("PDF \(model.pdfPage + 1)").accessibilityIdentifier("pdf-position"); Button("下一页") { model.pdfPage = min(1, model.pdfPage + 1); model.pdfColumn = 0; model.persist() } }
                    else { Text(model.epubPosition).font(.system(size: 1)).frame(width: 1).accessibilityIdentifier("epub-position"); Button("大字号") { model.largerText() }; Button("下一页") { Task { _ = await model.epub?.goForward() } } }
                    Spacer()
                    Button("题目") { model.drawer = true; model.sourceOpen = false; Task { await model.prepareNote() } }
                }.buttonStyle(.bordered).padding(6)
                if model.format == "PDF" {
                    HStack {
                        Text(model.pdfColumn.map { "第\($0 + 1)栏" } ?? "整页").accessibilityIdentifier("pdf-column")
                        Button("下一栏") { model.pdfColumn = 1; model.persist() }
                        Button("整页") { model.pdfColumn = nil; model.persist() }
                        Button("切换文档") { model.changeDocument() }
                    }.buttonStyle(.bordered)
                }
                Group {
                    if model.format == "PDF" { PDFReadingSurface(document: model.document, pageIndex: model.pdfPage, focusBounds: model.pdfFocusBounds).id("pdf-\(model.pdfDocumentIndex)-\(model.pdfPage)-\(model.pdfColumn ?? -1)") }
                    else if let epub = model.epub { EPUBReadingSurface(navigator: epub) }
                    else { Text("正在打开 EPUB") }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                if model.drawer {
                    VStack(alignment: .leading, spacing: 8) {
                        if model.sourceOpen {
                            Button("返回题目") { model.sourceOpen = false; model.sourceEPUB = nil }
                            if !model.message.isEmpty { Text(model.message) }
                            else if model.format == "PDF", let selection = model.selection {
                                Text("原句").accessibilityIdentifier("source-verified")
                                PDFReadingSurface(document: model.document, pageIndex: 1, selection: selection)
                            } else if let source = model.sourceEPUB {
                                if model.sourceReady { Text("原句").accessibilityIdentifier("source-verified") }
                                EPUBReadingSurface(navigator: source)
                            }
                        } else {
                            Text("测试题：河流朝哪个方向弯曲？")
                            TextField("未提交答案", text: $model.answer).accessibilityIdentifier("answer").textFieldStyle(.roundedBorder).onChange(of: model.answer) { _ in model.persist() }
                            TextField("批注", text: Binding(get: { model.note }, set: { model.updateNote($0) })).accessibilityIdentifier("note").textFieldStyle(.roundedBorder).disabled(!model.noteReady)
                            Button("回到批注") { Task { await model.returnToNote() } }.disabled(!model.noteReady || model.note.isEmpty)
                            Button("查看出处") { Task { await model.showSource() } }
                            Button("收起") { model.drawer = false }
                            if !model.message.isEmpty { Text(model.message) }
                            if model.format == "EPUB" { Text("纸书页码无法确认") }
                        }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding().background(.regularMaterial)
                     .frame(height: geometry.size.height * 0.5)
                     .gesture(DragGesture(minimumDistance: 40).onEnded { if $0.translation.height > 40 && model.sourceOpen { model.sourceOpen = false } })
                }
            }
        }
    }
}
