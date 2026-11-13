/// Layout decisions for a non-folding or closed display, in its own container.
public enum FlatReaderLayout: Equatable, Sendable {
    case pdfColumnFocus
    case epubSinglePage
    case epubFacingPages

    public enum Content: Sendable { case pdf, epub }

    public static func resolve(content: Content, width: Double, height: Double) -> Self {
        switch content {
        case .pdf: return .pdfColumnFocus
        case .epub:
            // Each reading column needs 320 points; this is a UI minimum, not
            // a device size, physical hinge threshold, or system split ratio.
            guard width.isFinite, height.isFinite, width >= 640, width > height else {
                return .epubSinglePage
            }
            return .epubFacingPages
        }
    }
}
