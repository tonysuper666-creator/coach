import Foundation

private final class RealtimeNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}
private struct RealtimeHello: Decodable { let epoch: String; let lastSeq: Int64 }

@MainActor
final class ConnectionModel: ObservableObject {
    enum State { case loading, unpaired, connected, unavailable, revoked }
    @Published var state: State = .loading
    @Published var message: String?
    @Published var busy = false
    enum RealtimeState { case connecting, online, offline }
    @Published private(set) var realtime: RealtimeState = .connecting
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
            try store.delete()
            saved = nil
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
                while !Task.isCancelled && generation == current {
                    let frame = try await task.receive()
                    let data: Data
                    switch frame {
                    case .string(let value): data = Data(value.utf8)
                    case .data(let value): data = value
                    @unknown default: continue
                    }
                    if let hello = try? JSONDecoder().decode(RealtimeHello.self, from: data), !hello.epoch.isEmpty {
                        realtime = .online
                        delay = 1
                    }
                }
            } catch { /* Verify authorization separately below; a transport error is not a revocation. */ }
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
