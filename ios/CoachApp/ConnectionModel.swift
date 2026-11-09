import Foundation
import CryptoKit

private final class RealtimeNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}
private struct RealtimeHello: Decodable { let epoch: String; let lastSeq: Int64 }
private struct LedgerNotice: Decodable { let t: String; let course: String; let epoch: String }

// One atomic file contains both the events and their cursor: advancing the cursor
// separately could permanently lose a page if the app is killed between writes.
struct LedgerReplica: Codable {
    struct Event: Codable { let seq: Int64; let id: String; let json: Data }
    struct Course: Codable {
        var cursor: Int64 = 0
        var events: [Event] = []
        // Older payloads are intentionally discarded; their sequence has already
        // been consumed. Optional keeps previously persisted replicas readable.
        var prunedThrough: Int64?
    }
    static let maxCachedEvents = 256
    static let maxCachedBytes = 512 * 1024
    var epoch: String
    var courses: [String: Course] = [:]

    static func file(address: ServerAddress, credential: DeviceCredential) throws -> URL {
        let identity = address.url.absoluteString + "\n" + credential.deviceId
        let hash = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return directory.appendingPathComponent("ledger-\(hash).json")
    }
    static func load(file: URL, epoch: String) throws -> LedgerReplica {
        guard FileManager.default.fileExists(atPath: file.path) else { return LedgerReplica(epoch: epoch) }
        let previous = try JSONDecoder().decode(LedgerReplica.self, from: Data(contentsOf: file))
        return previous.epoch == epoch ? previous : LedgerReplica(epoch: epoch)
    }
    func save(file: URL) throws {
        try JSONEncoder().encode(self).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    mutating func apply(data: Data, course: String, file: URL) throws -> (Bool, Bool) {
        guard let page = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let pageEpoch = page["epoch"] as? String, pageEpoch == epoch,
              let through = (page["throughSeq"] as? NSNumber)?.int64Value,
              let more = page["hasMore"] as? Bool,
              let rows = page["events"] as? [[String: Any]] else { throw ConnectionError.invalidResponse }
        var updated = courses[course] ?? Course()
        guard through >= updated.cursor, !more || through > updated.cursor else { throw ConnectionError.invalidResponse }
        var ids = Set(updated.events.map(\.id))
        var sequences = Set(updated.events.map(\.seq))
        var added = false
        for row in rows {
            guard let seq = (row["seq"] as? NSNumber)?.int64Value, seq > 0, seq <= through,
                  let id = row["id"] as? String, !id.isEmpty,
                  row["courseId"] as? String == course else { throw ConnectionError.invalidResponse }
            let json = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
            if ids.contains(id) || sequences.contains(seq) {
                guard updated.events.contains(where: { $0.id == id && $0.seq == seq && $0.json == json }) else { throw ConnectionError.invalidResponse }
                continue
            }
            // Old payloads outside the retained window cannot be compared byte
            // for byte, but have already been covered by the committed cursor.
            if seq <= (updated.prunedThrough ?? 0) { continue }
            guard seq > updated.cursor else { throw ConnectionError.invalidResponse }
            let event = Event(seq: seq, id: id, json: json)
            updated.events.append(event); ids.insert(id); sequences.insert(seq); added = true
        }
        updated.cursor = through
        var next = self
        next.courses[course] = updated
        // Bound retained payloads globally, not merely per course. Evict the
        // oldest event in a course first so its watermark remains meaningful.
        var count = next.courses.values.reduce(0) { $0 + $1.events.count }
        var bytes = next.courses.values.reduce(0) { $0 + $1.events.reduce(0) { $0 + $1.json.count } }
        while count > Self.maxCachedEvents || bytes > Self.maxCachedBytes {
            guard let victim = next.courses.keys.filter({ !(next.courses[$0]?.events.isEmpty ?? true) })
                .min(by: { (next.courses[$0]?.events.map(\.seq).min() ?? .max) < (next.courses[$1]?.events.map(\.seq).min() ?? .max) }),
                var entry = next.courses[victim],
                let index = entry.events.indices.min(by: { entry.events[$0].seq < entry.events[$1].seq }) else { break }
            let removed = entry.events.remove(at: index)
            entry.prunedThrough = max(entry.prunedThrough ?? 0, removed.seq)
            next.courses[victim] = entry
            count -= 1; bytes -= removed.json.count
        }
        try next.save(file: file)
        self = next
        return (more, added)
    }
}

@MainActor
final class ConnectionModel: ObservableObject {
    enum State { case loading, unpaired, connected, unavailable, revoked }
    @Published var state: State = .loading
    @Published var message: String?
    @Published var busy = false
    enum RealtimeState { case connecting, online, offline }
    @Published private(set) var realtime: RealtimeState = .connecting
    @Published private(set) var ledgerVersions: [String: Int] = [:]
    @Published private(set) var ledgerMessage: String?
    private(set) var ledgerReplica: LedgerReplica?
    private let ledgerPageSize: Int
    init(ledgerPageSize: Int = 100) { self.ledgerPageSize = ledgerPageSize }
    private var realtimeTask: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    private var generation = UUID()

    private let store = CredentialStore(service: "coach.ton1411.device")
    private var saved: (ServerAddress, DeviceCredential)?

    var learningCredentials: (ServerAddress, DeviceCredential)? { saved }

    func markRevoked() {
        stopRealtime()
        state = .revoked
        message = "此设备的访问已被拒绝，请重新配对。"
    }

    func restore() async {
        do {
            saved = try store.load()
            guard saved != nil else { state = .unpaired; return }
            await reconnect()
        } catch {
            state = .unavailable
            message = "无法读取设备凭据，请检查设备解锁状态。"
        }
    }

    func pair(address: String, code: String) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        message = nil
        do {
            let server = try ServerAddress(address.trimmingCharacters(in: .whitespacesAndNewlines))
            let client = PairingClient(address: server)
            let credential = try await client.pair(code: code.trimmingCharacters(in: .whitespacesAndNewlines), name: "iPhone")
            try store.save(address: server, credential: credential)
            saved = (server, credential)
            try await client.hello(credential: credential)
            state = .connected
            startRealtime()
        } catch ConnectionError.unauthorized {
            state = .revoked
            message = "此设备的访问已被拒绝，请重新配对。"
        } catch {
            state = saved == nil ? .unpaired : .unavailable
            message = errorMessage(error)
        }
    }

    func reconnect() async {
        guard !busy else { return }
        guard let (address, credential) = saved else { await restore(); return }
        busy = true
        defer { busy = false }
        message = nil
        do {
            _ = try await PairingClient(address: address).hello(credential: credential)
            state = .connected
            startRealtime()
        } catch ConnectionError.unauthorized {
            state = .revoked
            message = "此设备的访问已被拒绝，请重新配对。"
        } catch {
            state = .unavailable
            message = errorMessage(error)
        }
    }

    func forget() {
        do {
            stopRealtime()
            if let (address, credential) = saved {
                let file = try LedgerReplica.file(address: address, credential: credential)
                if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
            }
            try store.delete()
            saved = nil
            ledgerReplica = nil
            ledgerVersions = [:]
            ledgerMessage = nil
            message = nil
            state = .unpaired
        } catch { message = "无法删除设备凭据，请稍后重试。" }
    }


    private func stopRealtime() {
        generation = UUID()
        realtimeTask?.cancel()
        realtimeTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        realtime = .offline
    }

    func startRealtime() {
        stopRealtime()
        guard let (address, credential) = saved, state == .connected else { return }
        let current = generation
        realtime = .connecting
        realtimeTask = Task { [weak self] in
            await self?.watchRealtime(address: address, credential: credential, generation: current)
        }
    }

    private func watchRealtime(address: ServerAddress, credential: DeviceCredential, generation current: UUID) async {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.httpCookieStorage = nil
        config.urlCache = nil
        let session = URLSession(configuration: config, delegate: RealtimeNoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var delay: UInt64 = 1
        while !Task.isCancelled && generation == current {
            realtime = delay == 1 ? .connecting : .offline
            var parts = URLComponents(url: address.url.appendingPathComponent("v1/ws"), resolvingAgainstBaseURL: false)!
            parts.scheme = parts.scheme == "https" ? "wss" : "ws"
            guard let url = parts.url else { realtime = .offline; return }
            var request = URLRequest(url: url)
            request.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
            request.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
            let task = session.webSocketTask(with: request)
            socket = task
            task.resume()
            let heartbeat = Task { @MainActor in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    guard !Task.isCancelled else { break }
                    let ping = UUID()
                    var waiting = ping
                    task.sendPing { error in
                        Task { @MainActor in
                            if waiting == ping { waiting = UUID() }
                            if error != nil { task.cancel(with: .goingAway, reason: nil) }
                        }
                    }
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    if !Task.isCancelled && waiting == ping { task.cancel(with: .goingAway, reason: nil) }
                }
            }
            do {
                var epoch: String?
                while !Task.isCancelled && generation == current {
                    let frame = try await task.receive()
                    guard generation == current, !Task.isCancelled else { throw CancellationError() }
                    let data: Data
                    switch frame {
                    case .string(let value): data = Data(value.utf8)
                    case .data(let value): data = value
                    @unknown default: continue
                    }
                    if let hello = try? JSONDecoder().decode(RealtimeHello.self, from: data), !hello.epoch.isEmpty {
                        let file = try LedgerReplica.file(address: address, credential: credential)
                        ledgerReplica = try LedgerReplica.load(file: file, epoch: hello.epoch)
                        epoch = hello.epoch
                        realtime = .online
                        delay = 1
                        for course in ledgerReplica?.courses.keys.sorted() ?? [] {
                            try await catchUp(course: course, epoch: hello.epoch, file: file, address: address, credential: credential, session: session, generation: current)
                        }
                    } else if let notice = try? JSONDecoder().decode(LedgerNotice.self, from: data), notice.t == "ledger" {
                        guard notice.epoch == epoch, let epoch else { throw ConnectionError.invalidResponse }
                        let file = try LedgerReplica.file(address: address, credential: credential)
                        try await catchUp(course: notice.course, epoch: epoch, file: file, address: address, credential: credential, session: session, generation: current)
                    }
                }
            } catch {
                if generation == current && !Task.isCancelled { ledgerMessage = "学习记录尚未同步，正在重试。" }
                /* Verify authorization separately below; a transport error is not a revocation. */
            }
            heartbeat.cancel()
            let closeCode = task.closeCode
            task.cancel(with: .goingAway, reason: nil)
            if generation != current || Task.isCancelled { return }
            socket = nil
            realtime = .offline
            if closeCode == .policyViolation { markRevoked(); return }
            do {
                _ = try await PairingClient(address: address).hello(credential: credential)
            } catch ConnectionError.unauthorized {
                if generation == current { markRevoked() }
                return
            } catch { /* Offline: keep the learning view and pending answer intact. */ }
            guard generation == current, !Task.isCancelled else { return }
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            delay = min(delay * 2, 16)
        }
    }

    private func catchUp(course: String, epoch: String, file: URL, address: ServerAddress, credential: DeviceCredential, session: URLSession, generation current: UUID) async throws {
        guard !course.isEmpty, course.utf8.count <= 128, course != ".", course != "..",
              course.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_.")).contains($0) }) else { throw ConnectionError.invalidResponse }
        var more = false
        repeat {
            guard generation == current, !Task.isCancelled, let cursor = ledgerReplica?.courses[course]?.cursor ?? (ledgerReplica == nil ? nil : 0) else { throw CancellationError() }
            var url = address.url
            for part in ["v1", "courses", course, "events"] { url.appendPathComponent(part) }
            var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
            components.queryItems = [URLQueryItem(name: "after", value: String(cursor)), URLQueryItem(name: "limit", value: String(ledgerPageSize))]
            guard let url = components.url else { throw ConnectionError.invalidAddress }
            var request = URLRequest(url: url)
            request.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
            request.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
            let (data, response) = try await session.data(for: request)
            guard generation == current, !Task.isCancelled else { throw CancellationError() }
            guard let response = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
            if response.statusCode == 401 { throw ConnectionError.unauthorized }
            guard response.statusCode == 200, ledgerReplica?.epoch == epoch else { throw ConnectionError.invalidResponse }
            let result = try ledgerReplica!.apply(data: data, course: course, file: file)
            more = result.0
            if result.1 { ledgerVersions[course, default: 0] += 1 }
        } while more
        ledgerMessage = nil
    }

    private func errorMessage(_ error: Error) -> String {
        switch error {
        case ConnectionError.invalidAddress: "请输入有效的 Tailscale 地址和端口。"
        case ConnectionError.invalidCode: "配对码应为六位数字。"
        case ConnectionError.rejected: "配对被拒绝，请检查配对码是否有效。"
        case ConnectionError.unauthorized: "此设备的访问已被拒绝，请重新配对。"
        case is KeychainError: "无法保存设备凭据，请检查设备解锁状态。"
        default: "无法连接 Mac，请检查地址与网络后重试。"
        }
    }
}
