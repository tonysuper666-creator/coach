import Foundation
import XCTest
@testable import CoachConnection

final class StubProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try XCTUnwrap(Self.handler)(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class PairingTests: XCTestCase {
    func testOnlyLiteralTailnetAddressWithExplicitPortIsAccepted() throws {
        for address in ["http://100.64.0.1:1234", "https://100.127.255.255:443/"] {
            XCTAssertNoThrow(try ServerAddress(address))
        }
        for address in ["http://100.63.0.1:1234", "http://100.128.0.1:1234", "http://127.0.0.1:1234",
                        "http://example.com:1234", "http://100.64.0.1", "http://100.64.0.1:1234/path",
                        "http://100.64.0.1:1234/?token=x", "http://u:p@100.64.0.1:1234",
                        "http://100.064.0.1:1234", "http://100.64.0.1:99999"] {
            XCTAssertThrowsError(try ServerAddress(address), address)
        }
    }

    func testPairAndHelloUseExistingProtocolAndReportRevocation() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        let client = PairingClient(address: try ServerAddress("http://100.64.0.1:1234"), configuration: configuration)
        let token = String(repeating: "a", count: 43)
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/v1/pair")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            let body: Data
            if let bytes = request.httpBody { body = bytes }
            else {
                let stream = try XCTUnwrap(request.httpBodyStream)
                stream.open()
                defer { stream.close() }
                var bytes = [UInt8](repeating: 0, count: 1024)
                var data = Data()
                while stream.hasBytesAvailable {
                    let count = stream.read(&bytes, maxLength: bytes.count)
                    guard count > 0 else { break }
                    data.append(contentsOf: bytes.prefix(count))
                }
                body = data
            }
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(json, ["code": "123456", "name": "iPhone"])
            return (200, Data("{\"deviceId\":\"dev\",\"token\":\"\(token)\"}".utf8))
        }
        let credential = try await client.pair(code: "123456", name: "iPhone")
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/v1/hello")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Coach-Device"), "dev")
            return (401, Data())
        }
        do { _ = try await client.hello(credential: credential); XCTFail("revoked device accepted") }
        catch { XCTAssertEqual(error as? ConnectionError, .unauthorized) }
    }

    func testRejectsInvalidCodeAndMalformedCredentials() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        let client = PairingClient(address: try ServerAddress("http://100.64.0.1:1234"), configuration: configuration)
        do { _ = try await client.pair(code: "１２３４５６", name: "iPhone"); XCTFail("invalid code accepted") }
        catch { XCTAssertEqual(error as? ConnectionError, .invalidCode) }
        StubProtocol.handler = { _ in (200, Data("{\"deviceId\":\"dev\",\"token\":\"short\"}".utf8)) }
        do { _ = try await client.pair(code: "123456", name: "iPhone"); XCTFail("invalid credential accepted") }
        catch { XCTAssertEqual(error as? ConnectionError, .invalidResponse) }
    }
}
