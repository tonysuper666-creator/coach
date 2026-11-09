import Foundation
import Security

public struct CredentialStore {
    private let service: String

    public init(service: String) { self.service = service }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: "coach-device"]
    }

    /// Store address and credentials together, so a token is never silently reused for another host.
    public func save(address: ServerAddress, credential: DeviceCredential) throws {
        let data = try JSONEncoder().encode(StoredCredential(address: address.url.absoluteString, credential: credential))
        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw KeychainError(updated) }
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainError(added) }
    }

    public func load() throws -> (ServerAddress, DeviceCredential)? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status) }
        guard let data = result as? Data else { throw ConnectionError.invalidResponse }
        let stored = try JSONDecoder().decode(StoredCredential.self, from: data)
        return (try ServerAddress(stored.address), stored.credential)
    }

    public func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status) }
    }
}

public struct KeychainError: Error {
    public let status: OSStatus
    init(_ status: OSStatus) { self.status = status }
}

private struct StoredCredential: Codable {
    let address: String
    let credential: DeviceCredential
}
