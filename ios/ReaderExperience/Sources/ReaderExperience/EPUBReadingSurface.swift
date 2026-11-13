#if canImport(UIKit) && canImport(ReadiumNavigator)
import Foundation
import SwiftUI
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

/// This adapter uses Readium's publication, search and Locator APIs. It does
/// not infer a printed page from a synthetic pagination position.
@MainActor public final class EPUBReadingService {
    public let publication: Publication
    public init(publication: Publication) { self.publication = publication }

    public static func open(url: URL) async throws -> EPUBReadingService {
        let client = DefaultHTTPClient()
        let retriever = AssetRetriever(httpClient: client)
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: client, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
        let asset = try await retriever.retrieve(url: url.anyURL.absoluteURL!).get()
        let publication = try await opener.open(asset: asset, allowUserInteraction: false).get()
        return EPUBReadingService(publication: publication)
    }

    public func exactLocator(sentence: String) async throws -> Locator? {
        guard !sentence.isEmpty, publication.isSearchable else { return nil }
        let iterator = try await publication.search(query: sentence, options: SearchOptions(caseSensitive: true)).get()
        var matches: [Locator] = []
        while let page = try await iterator.next().get() {
            matches.append(contentsOf: page.locators.filter { $0.text.highlight == sentence })
            if matches.count > 1 { return nil }
        }
        return matches.count == 1 ? matches[0] : nil
    }

    public func navigator(locator: Locator? = nil, fontSize: Double = 1) throws -> EPUBNavigatorViewController {
        try EPUBNavigatorViewController(publication: publication, initialLocation: locator,
            config: .init(preferences: EPUBPreferences(columnCount: .one, fontSize: fontSize, scroll: false)))
    }

    public static func decodeLocator(_ data: Data) throws -> Locator? {
        try Locator(json: JSONValue(jsonData: data), warnings: nil)
    }
}

public struct EPUBReadingSurface: UIViewControllerRepresentable {
    public let navigator: EPUBNavigatorViewController
    public init(navigator: EPUBNavigatorViewController) { self.navigator = navigator }
    public func makeUIViewController(context: Context) -> EPUBNavigatorViewController { navigator }
    public func updateUIViewController(_ controller: EPUBNavigatorViewController, context: Context) {}
}
#endif
