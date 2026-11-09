import Foundation

@MainActor
final class ConnectionModel: ObservableObject {
    enum State { case loading, unpaired, connected, unavailable, revoked }
    @Published var state: State = .loading
    @Published var message: String?
    @Published var busy = false

    private let store = CredentialStore(service: "coach.ton1411.device")
    private var saved: (ServerAddress, DeviceCredential)?

    var learningCredentials: (ServerAddress, DeviceCredential)? { saved }

    func markRevoked() {
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
            try store.delete()
            saved = nil
            message = nil
            state = .unpaired
        } catch { message = "无法删除设备凭据，请稍后重试。" }
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
