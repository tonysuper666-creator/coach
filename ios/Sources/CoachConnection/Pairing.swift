import Foundation

public enum ConnectionError: Error, Equatable {
    case invalidAddress, invalidCode, rejected, invalidResponse, unauthorized, httpStatus(Int)
}

/// A numeric tailnet address only. No userinfo, paths, queries or redirects may carry credentials elsewhere.
public struct ServerAddress: Sendable {
    public let url: URL

    public init(_ value: String) throws {
        guard let c = URLComponents(string: value),
              c.scheme == "http" || c.scheme == "https",
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              c.path.isEmpty || c.path == "/", let host = c.host,
              let port = c.port, (1...65535).contains(port) else { throw ConnectionError.invalidAddress }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4,
              parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) && ($0 == "0" || $0.first != "0") }),
              let a = UInt8(parts[0]), let b = UInt8(parts[1]),
              UInt8(parts[2]) != nil, UInt8(parts[3]) != nil,
              a == 100, (64...127).contains(b), let url = c.url else { throw ConnectionError.invalidAddress }
        self.url = url
    }
}

public struct DeviceCredential: Codable, Sendable {
    public let deviceId: String
    public let token: String

    public init(deviceId: String, token: String) {
        self.deviceId = deviceId
        self.token = token
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public final class PairingClient: Sendable {
    private let address: ServerAddress
    private let session: URLSession

    public init(address: ServerAddress, configuration: URLSessionConfiguration = .ephemeral) {
        self.address = address
        configuration.timeoutIntervalForRequest = 10
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    public func pair(code: String, name: String) async throws -> DeviceCredential {
        guard code.utf8.count == 6, code.utf8.allSatisfy({ (48...57).contains($0) }) else {
            throw ConnectionError.invalidCode
        }
        var request = URLRequest(url: address.url.appendingPathComponent("v1/pair"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(PairRequest(code: code, name: name))
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
        guard http.statusCode == 200 else { throw ConnectionError.rejected }
        guard let credential = try? JSONDecoder().decode(DeviceCredential.self, from: data),
              !credential.deviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              credential.token.utf8.count == 43 else { throw ConnectionError.invalidResponse }
        return credential
    }

    /// The server validates both headers on every authenticated HTTP operation.
    public func hello(credential: DeviceCredential) async throws -> Data {
        var request = URLRequest(url: address.url.appendingPathComponent("v1/hello"))
        request.setValue("Bearer \(credential.token)", forHTTPHeaderField: "Authorization")
        request.setValue(credential.deviceId, forHTTPHeaderField: "X-Coach-Device")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ConnectionError.invalidResponse }
        if http.statusCode == 401 { throw ConnectionError.unauthorized }
        guard (200...299).contains(http.statusCode) else { throw ConnectionError.httpStatus(http.statusCode) }
        return data
    }
}

private struct PairRequest: Encodable {
    let code: String
    let name: String
}
