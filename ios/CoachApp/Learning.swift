import Foundation
import Combine
import Security
import SwiftUI

private final class LearningNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}

struct WeekList: Decodable { let weeks: [WeekSummary] }
struct WeekSummary: Decodable, Identifiable { let week: Int; let title: String; var id: Int { week } }
struct PracticeWeek: Decodable { let title: String; let items: [PracticeItem] }
struct PracticeItem: Decodable, Identifiable {
    let id: String; let stem: String; let options: [String]; let layout: Int?; let status: String
    var selectionSignature: SelectionSignature { SelectionSignature(id: id, stem: stem, options: options, layout: layout) }
}
struct SelectionSignature: Equatable { let id: String; let stem: String; let options: [String]; let layout: Int? }
struct AnswerReceipt: Decodable { let itemId: String; let attemptId: String; let choice: Int; let status: String; let keyState: String }
struct PendingAnswer: Codable {
    let course: String; let week: Int; let itemId: String; let choice: Int; let layout: Int?; let submissionId: String; let server: String; let deviceId: String
    var itemStem: String? = nil; var optionTexts: [String]? = nil
}
struct ConfirmedAnswer: Codable {
    let course: String; let week: Int; let itemId: String; let server: String; let deviceId: String
    let stem: String; let options: [String]; let choiceText: String; let status: String; let keyState: String

    func matches(item: PracticeItem, course: String, week: Int, server: String, deviceId: String) -> Bool {
        self.course == course && self.week == week && itemId == item.id && self.server == server &&
        self.deviceId == deviceId && stem == item.stem && options.sorted() == item.options.sorted() &&
        item.options.filter { $0 == choiceText }.count == 1
    }
}
struct KeychainRecord<Value: Codable> {
    let service: String
    var account: String = "record"
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
    func load() throws -> Value? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status) }
        guard let data = item as? Data else { throw ConnectionError.invalidResponse }
        return try JSONDecoder().decode(Value.self, from: data)
    }
    func save(_ value: Value) throws {
        let data = try JSONEncoder().encode(value)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw KeychainError(status) }
        var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(q as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainError(added) }
    }
    func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status) }
    }
}
// Keep the original account name so pending submissions from older builds survive upgrades.
struct PendingAnswerStore {
    private let record = KeychainRecord<PendingAnswer>(service: "coach.ton1411.pending-answer", account: "pending")
    func load() throws -> PendingAnswer? { try record.load() }
    func save(_ value: PendingAnswer) throws { try record.save(value) }
    func delete() throws { try record.delete() }
}
private struct AnswerBody: Encodable { let itemId: String; let choice: Int; let layout: Int?; let submissionId: String }

@MainActor
final class LearningModel: ObservableObject {
    @Published var course = UserDefaults.standard.string(forKey: "coach.learning.course") ?? ""
    @Published var weeks: [WeekSummary] = []
    @Published var week: PracticeWeek?
    @Published var selectedWeek: Int?
    @Published var receipt: AnswerReceipt?
    @Published private(set) var confirmedAnswer: ConfirmedAnswer?
    @Published var pending: PendingAnswer?
    @Published var message: String?
    @Published var busy = false
    @Published private(set) var refreshNeeded = false
    @Published private(set) var selectionRevision = 0
    private var loadedCourse: String?
    private var lastVersions: [String: Int] = [:]
    private var subscription: AnyCancellable?
    private var refreshTask: Task<Void, Never>?
    private let connection: ConnectionModel
    private let pendingStore = PendingAnswerStore()
    private let confirmedStore = KeychainRecord<ConfirmedAnswer>(service: "coach.ton1411.confirmed-answer")
    private var storageAvailable = true
    private let session: URLSession

    init(connection: ConnectionModel) {
        self.connection = connection
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.urlCache = nil
        config.httpCookieStorage = nil
        session = URLSession(configuration: config, delegate: LearningNoRedirects(), delegateQueue: nil)
        do {
            pending = try pendingStore.load()
            confirmedAnswer = try confirmedStore.load()
            if let pending { course = pending.course }
        } catch {
            storageAvailable = false
            message = "无法读取待确认答案，请检查设备解锁状态。"
        }
        lastVersions = connection.ledgerVersions
        subscription = connection.$ledgerVersions.dropFirst().sink { [weak self] versions in
            guard let self else { return }
            let active = self.loadedCourse
            let changed = active.map { versions[$0, default: 0] != self.lastVersions[$0, default: 0] } ?? false
            self.lastVersions = versions
            if changed, self.selectedWeek != nil, self.course == active {
                self.refreshNeeded = true
                self.scheduleRefresh()
            }
        }
    }

    private func scheduleRefresh() {
        guard refreshNeeded, !busy, refreshTask == nil else { return }
        refreshTask = Task { [weak self] in
            await self?.refreshWeek()
        }
    }

    private func finishOperation() {
        busy = false
        scheduleRefresh()
    }

    func refreshWeek() async {
        guard !busy, let active = loadedCourse, active == course, let number = selectedWeek else {
            refreshTask = nil; return
        }
        refreshTask = nil
        refreshNeeded = false
        busy = true
        do {
            let updated = try await request(PracticeWeek.self, path: ["v1", "courses", active, "mainline", String(number)], query: [URLQueryItem(name: "layout", value: "shuffled-v1")])
            if loadedCourse == active, course == active, selectedWeek == number {
                // Never silently apply a previously selected index to different options.
                if let old = week, old.items.map(\.selectionSignature) != updated.items.map(\.selectionSignature) {
                    selectionRevision += 1
                }
                week = updated
            }
            finishOperation()
        } catch {
            // Avoid an immediate retry loop. A later ledger event or the visible
            // retry control retries without losing the pending answer.
            busy = false; refreshNeeded = true; fail(error)
        }
    }

    private func request<T: Decodable>(_ type: T.Type, path: [String], query: [URLQueryItem] = [], body: Data? = nil) async throws -> T {
        guard let (address, credential) = connection.learningCredentials else { throw ConnectionError.unauthorized }
        var url = address.url
        for component in path { url.appendPathComponent(component) }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        guard let finalURL = components.url else { throw ConnectionError.invalidAddress }
        var req = URLRequest(url: finalURL)
        req.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
        req.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
        if let body {
            req.httpMethod = "POST"; req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
        if http.statusCode == 401 { throw ConnectionError.unauthorized }
        guard (200...299).contains(http.statusCode) else { throw ConnectionError.httpStatus(http.statusCode) }
        return try JSONDecoder().decode(type, from: data)
    }

    private func fail(_ error: Error) {
        if case ConnectionError.unauthorized = error { connection.markRevoked(); return }
        if case ConnectionError.httpStatus(409) = error {
            message = "提交与服务器记录冲突。请保留当前状态并联系支持。"
        } else { message = "未能从 Mac 确认结果，请检查网络后重试。" }
    }

    func loadCourse() async {
        guard !busy else { return }
        let name = course.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.utf8.count <= 128,
              name.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_.")).contains($0) }),
              name != ".", name != ".." else { message = "请输入有效的课程标识。"; return }
        guard pending == nil || pending?.course == name else { message = "请先确认上一次答题。"; return }
        course = name; UserDefaults.standard.set(name, forKey: "coach.learning.course")
        busy = true; defer { finishOperation() }; message = nil
        loadedCourse = nil; refreshNeeded = false
        weeks = []; week = nil; receipt = nil; selectedWeek = nil
        do {
            let list = try await request(WeekList.self, path: ["v1", "courses", name, "mainline"])
            weeks = list.weeks; week = nil; receipt = nil; selectedWeek = nil; loadedCourse = name
            if weeks.isEmpty { message = "这门课程还没有可用练习。" }
        } catch { fail(error) }
    }

    func openWeek(_ number: Int) async {
        guard !busy else { return }
        guard pending == nil || pending?.week == number else { message = "请先确认上一次答题。"; return }
        let version = connection.ledgerVersions[course, default: 0]
        busy = true; defer { finishOperation() }; message = nil
        do {
            week = try await request(PracticeWeek.self, path: ["v1", "courses", course, "mainline", String(number)], query: [URLQueryItem(name: "layout", value: "shuffled-v1")])
            selectedWeek = number; receipt = nil; selectionRevision += 1
            if connection.ledgerVersions[course, default: 0] != version { refreshNeeded = true }
        } catch { fail(error) }
    }

    func submit(item: PracticeItem, choice: Int) async {
        guard !busy, storageAvailable, pending == nil, let selectedWeek, let (server, credential) = connection.learningCredentials, item.options.indices.contains(choice) else { return }
        let value = PendingAnswer(course: course, week: selectedWeek, itemId: item.id, choice: choice, layout: item.layout, submissionId: UUID().uuidString, server: server.url.absoluteString, deviceId: credential.deviceId, itemStem: item.stem, optionTexts: item.options)
        do {
            try pendingStore.save(value)
            pending = value
            await retryPending()
        } catch { message = "无法保存待确认的答案，请重试。" }
    }

    func discardPending() {
        do {
            try pendingStore.delete()
            pending = nil
            message = "已清除本机待确认记录。请重新打开课程检查服务器上的状态。"
        } catch { message = "无法清除待确认记录，请检查设备解锁状态。" }
    }

    func retryPending() async {
        guard !busy, let value = pending else { return }
        guard let (server, credential) = connection.learningCredentials, value.server == server.url.absoluteString, value.deviceId == credential.deviceId else {
            message = "待确认答案属于另一台 Mac 或设备，请恢复原连接后重试。"; return
        }
        busy = true; defer { finishOperation() }; message = nil
        do {
            let body = try JSONEncoder().encode(AnswerBody(itemId: value.itemId, choice: value.choice, layout: value.layout, submissionId: value.submissionId))
            let result = try await request(AnswerReceipt.self, path: ["v1", "courses", value.course, "mainline", String(value.week), "answer"], body: body)
            guard result.itemId == value.itemId, result.choice == value.choice, !result.attemptId.isEmpty else { throw ConnectionError.invalidResponse }
            if let stem = value.itemStem, let options = value.optionTexts, options.indices.contains(value.choice) {
                let confirmed = ConfirmedAnswer(course: value.course, week: value.week, itemId: value.itemId, server: value.server, deviceId: value.deviceId, stem: stem, options: options, choiceText: options[value.choice], status: result.status, keyState: result.keyState)
                // Store the confirmed result before deleting the retry record. If storage
                // fails, the same submission ID remains available for a safe retry.
                try confirmedStore.save(confirmed)
                confirmedAnswer = confirmed
            }
            try pendingStore.delete()
            pending = nil; receipt = result
        } catch is KeychainError {
            message = "已收到服务器回复，但无法保存本机记录；请解锁设备后重试同一次提交。"
        } catch { fail(error) }
    }

    func confirmedAnswer(for item: PracticeItem) -> ConfirmedAnswer? {
        guard let saved = confirmedAnswer, let number = selectedWeek,
              let (server, credential) = connection.learningCredentials,
              saved.matches(item: item, course: course, week: number, server: server.url.absoluteString, deviceId: credential.deviceId) else { return nil }
        return saved
    }

    func pendingChoice(for item: PracticeItem) -> Int? {
        guard let saved = pending, let number = selectedWeek,
              let (server, credential) = connection.learningCredentials,
              saved.course == course, saved.week == number, saved.itemId == item.id,
              saved.server == server.url.absoluteString, saved.deviceId == credential.deviceId,
              saved.itemStem == item.stem, let options = saved.optionTexts,
              options.indices.contains(saved.choice), options.sorted() == item.options.sorted() else { return nil }
        let selected = options[saved.choice]
        guard item.options.filter({ $0 == selected }).count == 1 else { return nil }
        return item.options.firstIndex(of: selected)
    }
}

struct LearningView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject var connection: ConnectionModel
    @StateObject private var model: LearningModel
    @State private var selections: [String: Int] = [:]
    @State private var showDiscard = false
    @FocusState private var courseFieldFocused: Bool
    init(connection: ConnectionModel, model: LearningModel? = nil) {
        self.connection = connection
        _model = StateObject(wrappedValue: model ?? LearningModel(connection: connection))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: CoachV7.Space.s4) {
            Text("接着学习").font(Appearance.serif(.title1, size: 28, weight: .semibold, dynamicTypeSize: dynamicTypeSize))
            Text("输入课程标识，打开已有练习。").foregroundStyle(Appearance.secondary)
            TextField("课程标识", text: $model.course).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder).disabled(model.pending != nil)
                .focused($courseFieldFocused)
                .accessibilityIdentifier("courseID")
                .submitLabel(.go)
                .onSubmit {
                    guard !model.busy else { return }
                    courseFieldFocused = false
                    Task { await model.loadCourse() }
                }
            Button("打开课程") {
                courseFieldFocused = false
                Task { await model.loadCourse() }
            }.buttonStyle(.bordered).disabled(model.busy).accessibilityIdentifier("openCourse")
            if model.pending != nil {
                Text("上次答案尚未确认。重试会使用同一次提交。")
                Button("确认上次答案") { Task { await model.retryPending() } }.disabled(model.busy).accessibilityIdentifier("retryAnswer")
                Button("清除待确认记录", role: .destructive) { showDiscard = true }.disabled(model.busy)
            }
            ForEach(model.weeks) { week in
                Button(week.title) { Task { await model.openWeek(week.week) } }.disabled(model.busy).accessibilityIdentifier("week-\(week.week)")
            }
            if !model.weeks.isEmpty {
                NavigationLink("打开本课程资料") {
                    ReaderView(connection: connection, course: model.course)
                }.accessibilityIdentifier("openReader")
            }
            if let week = model.week {
                Text(week.title).font(Appearance.serif(.title2, size: 22, weight: .semibold, dynamicTypeSize: dynamicTypeSize))
                ForEach(week.items) { item in
                    VStack(alignment: .leading, spacing: CoachV7.Space.s3) {
                        Text(item.stem).font(.system(.headline, design: .serif))
                        Text("状态：\(item.status)").font(.caption).foregroundStyle(Appearance.secondary)
                        if let saved = model.confirmedAnswer(for: item) {
                            Text("本机上次选择：\(saved.choiceText)").accessibilityIdentifier("lastConfirmedChoice")
                            Text("本机上次确认：\(saved.status) · \(saved.keyState)").accessibilityIdentifier("lastConfirmedReceipt")
                        }
                        if let pendingChoice = model.pendingChoice(for: item) {
                            Text("待确认选择：\(item.options[pendingChoice])").accessibilityIdentifier("pendingChoice")
                        }
                        ForEach(item.options.indices, id: \.self) { index in
                            Button { selections[item.id] = index } label: {
                                Label(item.options[index], systemImage: (model.pendingChoice(for: item) ?? selections[item.id]) == index ? "largecircle.fill.circle" : "circle")
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(CoachV7.Space.s4)
                                    .background(Appearance.option, in: RoundedRectangle(cornerRadius: CoachV7.Radius.m))
                                    .overlay(RoundedRectangle(cornerRadius: CoachV7.Radius.m).stroke(Appearance.line, lineWidth: 0.5))
                            }.disabled(model.busy || model.pending != nil)
                        }
                        Button("提交答案") {
                            if let choice = selections[item.id] { Task { await model.submit(item: item, choice: choice) } }
                        }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(Appearance.paper)
                        .disabled(model.busy || model.pending != nil || selections[item.id] == nil)
                    }.modifier(V7Card())
                }
            }
            if model.refreshNeeded {
                Button("刷新练习状态") { Task { await model.refreshWeek() } }.disabled(model.busy)
            }
            if let receipt = model.receipt {
                Text("已收到答案 · \(receipt.status) · \(receipt.keyState)").accessibilityIdentifier("answerReceipt")
            }
            if model.week != nil {
                Button("返回课程") { Task { await model.loadCourse() } }
                    .disabled(model.busy)
                    .accessibilityIdentifier("returnToCourse")
            }
            if let message = model.message { Text(message).foregroundStyle(Appearance.accent).accessibilityIdentifier("learningMessage") }
            Button("移除此设备的连接", role: .destructive) { connection.forget() }
        }
        .onChange(of: model.selectionRevision) { _ in selections = [:] }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("收起键盘") { courseFieldFocused = false }
            }
        }
        .alert("清除待确认记录？", isPresented: $showDiscard) {
            Button("清除记录", role: .destructive) { model.discardPending() }
            Button("取消", role: .cancel) { }
        } message: {
            Text("答案可能已经记录在 Mac 上。清除只影响本机，之后请重新打开课程检查状态。")
        }
    }
}

// Reader uses the existing paired transport and the server's versioned ledger.
private struct ReaderCatalog: Decodable { let documents: [ReaderDocument] }
private struct ReaderDocument: Codable, Identifiable { let id: String; let title: String; let version: String; let pages: Int }
private struct ReaderProgress: Decodable { let version: String; let pdfPage: Int; let revision: Int; let stale: Bool }
private struct ReaderNote: Decodable, Identifiable {
    let id: String; let version: String; let pdfPage: Int; let textSha256: String
    let start: Int; let end: Int; let quote: String; let note: String; let revision: Int; let stale: Bool
}
private struct ReaderState: Decodable { let document: ReaderDocument; let progress: ReaderProgress?; let notes: [ReaderNote] }
private struct ReaderPage: Decodable { let documentId: String; let version: String; let pdfPage: Int; let text: String; let textSha256: String }
private struct ReaderReceipt: Decodable { let revision: Int; let replayed: Bool }
private struct ReaderPending: Codable {
    let server: String; let deviceId: String; let course: String; let document: String
    let operation: String; let body: Data
}
private struct ReaderProgressBody: Encodable { let version: String; let pdfPage: Int; let baseRevision: Int; let mutationId: String }
private struct ReaderNoteBody: Encodable {
    let id: String; let version: String; let pdfPage: Int; let textSha256: String
    let start: Int; let end: Int; let quote: String; let note: String; let deleted: Bool
    let baseRevision: Int; let mutationId: String
}

@MainActor
private final class ReaderModel: ObservableObject {
    @Published var documents: [ReaderDocument] = []
    @Published var state: ReaderState?
    @Published var page: ReaderPage?
    @Published var pageImage: UIImage?
    @Published var selectedRange = NSRange(location: 0, length: 0)
    @Published var draft = ""
    @Published var editing: ReaderNote?
    @Published var pending: ReaderPending?
    @Published var busy = false
    @Published var message: String?
    private let connection: ConnectionModel
    private let course: String
    private let store = KeychainRecord<ReaderPending>(service: "coach.ton1411.reader-pending")
    private let session: URLSession
    private var storageAvailable = true

    init(connection: ConnectionModel, course: String) {
        self.connection = connection; self.course = course
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10; config.urlCache = nil; config.httpCookieStorage = nil
        session = URLSession(configuration: config, delegate: LearningNoRedirects(), delegateQueue: nil)
        do { pending = try store.load() }
        catch { storageAvailable = false; message = "无法读取本机待确认的阅读记录，请解锁设备。" }
    }

    private func request<T: Decodable>(_ type: T.Type, _ path: [String], body: Data? = nil) async throws -> T {
        guard let (address, credential) = connection.learningCredentials else { throw ConnectionError.unauthorized }
        var url = address.url
        for part in ["v1", "courses", course, "reader"] + path { url.appendPathComponent(part) }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
        req.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
        if let body { req.httpMethod = "POST"; req.httpBody = body; req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
        if http.statusCode == 401 { throw ConnectionError.unauthorized }
        guard (200...299).contains(http.statusCode) else { throw ConnectionError.httpStatus(http.statusCode) }
        return try JSONDecoder().decode(T.self, from: data)
    }
    private func report(_ error: Error) {
        if case ConnectionError.unauthorized = error {
            message = "设备身份已失效；本机待确认内容仍保留。"
            connection.markRevoked()
        } else if case ConnectionError.httpStatus(409) = error {
            message = "资料版本或服务器记录已改变；本机待确认内容仍保留，请核对后处理。"
        } else { message = "无法从 Mac 确认阅读记录；本机待确认内容仍保留。" }
    }
    private func sameIdentity(_ value: ReaderPending) -> Bool {
        guard let (address, credential) = connection.learningCredentials else { return false }
        return value.server == address.url.absoluteString && value.deviceId == credential.deviceId && value.course == course
    }
    func loadCatalog() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { documents = try await request(ReaderCatalog.self, []).documents }
        catch { report(error) }
    }
    func open(_ document: ReaderDocument) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        state = nil; page = nil; pageImage = nil
        do {
            let latest = try await request(ReaderState.self, [document.id, "state"])
            guard latest.document.version == document.version else { throw ConnectionError.httpStatus(409) }
            state = latest
            let target = latest.progress.flatMap { !$0.stale && $0.version == document.version ? $0.pdfPage : nil } ?? 1
            try await loadPage(document: document, number: target)
            if let pending, sameIdentity(pending), pending.document == document.id { message = "有未确认的阅读记录，可重试原次保存。" }
        } catch { report(error) }
    }
    private func loadPage(document: ReaderDocument, number: Int) async throws {
        let result = try await request(ReaderPage.self, [document.id, "pages", String(number)])
        guard result.documentId == document.id, result.version == document.version, result.pdfPage == number else { throw ConnectionError.httpStatus(409) }
        guard let (address, credential) = connection.learningCredentials else { throw ConnectionError.unauthorized }
        var imageURL = address.url
        for part in ["v1", "courses", course, "reader", document.id, "pages", String(number), "image"] { imageURL.appendPathComponent(part) }
        var components = URLComponents(url: imageURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "version", value: document.version)]
        guard let url = components.url else { throw ConnectionError.invalidAddress }
        var imageRequest = URLRequest(url: url)
        imageRequest.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
        imageRequest.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
        let (imageData, response) = try await session.data(for: imageRequest)
        guard let http = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
        if http.statusCode == 401 { throw ConnectionError.unauthorized }
        guard (200...299).contains(http.statusCode) else { throw ConnectionError.httpStatus(http.statusCode) }
        guard let image = UIImage(data: imageData) else { throw ConnectionError.invalidResponse }
        page = result; pageImage = image; selectedRange = NSRange(location: 0, length: 0); editing = nil; draft = ""
    }
    func turn(_ number: Int) async {
        guard !busy, pending == nil, let state, (1...state.document.pages).contains(number), storageAvailable else { return }
        busy = true; defer { busy = false }
        do {
            let body = ReaderProgressBody(version: state.document.version, pdfPage: number,
                baseRevision: state.progress?.revision ?? 0, mutationId: UUID().uuidString)
            let bytes = try JSONEncoder().encode(body)
            try prepare(document: state.document.id, operation: "progress", body: bytes)
            try await sendPending()
            let latest = try await request(ReaderState.self, [state.document.id, "state"])
            guard latest.document.version == state.document.version else { throw ConnectionError.httpStatus(409) }
            self.state = latest
            try await loadPage(document: latest.document, number: number)
        } catch { report(error) }
    }
    private func prepare(document: String, operation: String, body: Data) throws {
        guard pending == nil, storageAvailable, let (address, credential) = connection.learningCredentials else { throw ConnectionError.invalidResponse }
        let value = ReaderPending(server: address.url.absoluteString, deviceId: credential.deviceId, course: course,
                                  document: document, operation: operation, body: body)
        try store.save(value); pending = value
    }
    private func sendPending() async throws {
        guard let value = pending, sameIdentity(value) else { throw ConnectionError.invalidResponse }
        _ = try await request(ReaderReceipt.self, [value.document, value.operation], body: value.body)
        try store.delete(); pending = nil
    }
    func retry() async {
        guard !busy, let value = pending, sameIdentity(value) else { message = "待确认记录属于另一连接或课程，请恢复原连接。"; return }
        busy = true; defer { busy = false }
        do {
            try await sendPending()
            let latest = try await request(ReaderState.self, [value.document, "state"])
            state = latest
            let number = latest.progress.flatMap { !$0.stale ? $0.pdfPage : nil } ?? 1
            try await loadPage(document: latest.document, number: number)
            message = "服务器已确认阅读记录。"
        } catch { report(error) }
    }
    func saveNote() async {
        guard !busy, pending == nil, storageAvailable, let state, let page else { return }
        let range = selectedRange
        let note = editing
        guard note != nil || (range.location != NSNotFound && range.location <= (page.text as NSString).length && range.length <= (page.text as NSString).length - range.location) else {
            message = "请先选择本页文字。"; return
        }
        let start = note?.start ?? range.location, end = note?.end ?? (range.location + range.length)
        let text = page.text as NSString
        guard note != nil || (range.length > 0 && range.length <= 4000 && start >= 0 && end <= text.length),
              draft.utf16.count <= 8000 else { message = "请先选择本页文字，批注不超过 8000 字符。"; return }
        let body = ReaderNoteBody(id: note?.id ?? UUID().uuidString, version: note?.version ?? page.version,
            pdfPage: note?.pdfPage ?? page.pdfPage, textSha256: note?.textSha256 ?? page.textSha256,
            start: start, end: end, quote: note?.quote ?? text.substring(with: range), note: draft,
            deleted: false, baseRevision: note?.revision ?? 0, mutationId: UUID().uuidString)
        busy = true; defer { busy = false }
        do {
            try prepare(document: state.document.id, operation: "notes", body: JSONEncoder().encode(body))
            try await sendPending()
            let latest = try await request(ReaderState.self, [state.document.id, "state"])
            guard latest.document.version == state.document.version else { throw ConnectionError.httpStatus(409) }
            guard latest.notes.contains(where: { $0.id == body.id && $0.note == body.note && !$0.stale }) else { throw ConnectionError.invalidResponse }
            self.state = latest; draft = ""; editing = nil; selectedRange = NSRange(location: 0, length: 0)
            message = "已从 Mac 回读批注。"
        } catch { report(error) }
    }
}

private struct ReaderTextSelection: UIViewRepresentable {
    let text: String
    @Binding var selectedRange: NSRange
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(); view.isEditable = false; view.isScrollEnabled = false
        view.backgroundColor = .clear; view.adjustsFontForContentSizeCategory = true
        view.font = .preferredFont(forTextStyle: .body); view.delegate = context.coordinator
        view.accessibilityIdentifier = "readerPageText"; return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.parent = self
        if view.text != text { view.text = text; view.selectedRange = NSRange(location: 0, length: 0) }
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        guard let width = proposal.width else { return nil }
        return uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
    }
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ReaderTextSelection
        init(_ parent: ReaderTextSelection) { self.parent = parent }
        func textViewDidChangeSelection(_ textView: UITextView) { parent.selectedRange = textView.selectedRange }
    }
}

private struct ReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ReaderModel
    init(connection: ConnectionModel, course: String) { _model = StateObject(wrappedValue: ReaderModel(connection: connection, course: course)) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: CoachV7.Space.s4) {
                Button("返回课程和练习") { dismiss() }.accessibilityIdentifier("readerBack")
                Text("课程资料").font(.title2.bold())
                if let message = model.message { Text(message).foregroundStyle(Appearance.accent).accessibilityIdentifier("readerMessage") }
                if model.pending != nil {
                    Text("有未确认的阅读记录，本机已保留。")
                    Button("重试原次保存") { Task { await model.retry() } }.disabled(model.busy)
                }
                ForEach(model.documents) { document in
                    Button(document.title) { Task { await model.open(document) } }.disabled(model.busy)
                        .accessibilityIdentifier("readerDocument-\(document.id)")
                }
                if let state = model.state, let page = model.page {
                    Text("\(state.document.title) · 第 \(page.pdfPage) / \(state.document.pages) 页").accessibilityIdentifier("readerPage")
                    HStack {
                        Button("上一页") { Task { await model.turn(page.pdfPage - 1) } }.disabled(model.busy || model.pending != nil || page.pdfPage <= 1)
                        Button("下一页") { Task { await model.turn(page.pdfPage + 1) } }.disabled(model.busy || model.pending != nil || page.pdfPage >= state.document.pages)
                    }
                    if let image = model.pageImage { Image(uiImage: image).resizable().scaledToFit().accessibilityLabel("PDF 页面图像") }
                    Text("选择提取文字添加批注；文字位置不代表 PDF 上的标记。") .font(.caption)
                    ReaderTextSelection(text: page.text, selectedRange: $model.selectedRange)
                    ForEach(state.notes) { note in
                        VStack(alignment: .leading) {
                            Text("第 \(note.pdfPage) 页\(note.stale ? " · 旧版本" : "")：\(note.quote)")
                            Text(note.note)
                            if !note.stale && note.version == page.version && note.pdfPage == page.pdfPage {
                                Button("编辑批注") { model.editing = note; model.draft = note.note }
                            }
                        }.modifier(V7Card())
                    }
                    TextField("写下批注", text: $model.draft, axis: .vertical).textFieldStyle(.roundedBorder).disabled(model.pending != nil).accessibilityIdentifier("readerNoteDraft")
                    Button(model.editing == nil ? "保存选中文字的批注" : "保存批注修改") { Task { await model.saveNote() } }
                        .disabled(model.busy || model.pending != nil).accessibilityIdentifier("saveReaderNote")
                }
            }.frame(maxWidth: 600, alignment: .leading).padding(CoachV7.Space.s6)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(Appearance.paper.ignoresSafeArea())
            .foregroundStyle(Appearance.ink).task { await model.loadCatalog() }
    }
}
