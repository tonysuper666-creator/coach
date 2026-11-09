/// A layout suggestion, not a claim about the device model or display.
public enum ReaderPose: Equatable, Sendable {
    case compact
    case openPortrait
    case openLandscape
    case book
    case laptop
    case tabletop
}

/// Values supplied by a platform adapter. Unknown input must stay unknown: a
/// small window alone cannot tell us whether a foldable device is closed.
public struct ReaderGeometry: Equatable, Sendable {
    public enum Hinge: Equatable, Sendable {
        case unavailable, closed, open, partiallyOpen
    }

    /// The direction of an active system-provided division in container space.
    public enum Division: Equatable, Sendable {
        case none, vertical, horizontal
    }

    public enum Surface: Equatable, Sendable {
        case unknown, faceUp, other
    }

    public var width: Double
    public var height: Double
    public var hinge: Hinge
    public var division: Division
    public var surface: Surface
    public var compactWidth: Bool

    public init(width: Double, height: Double, hinge: Hinge,
                division: Division, surface: Surface, compactWidth: Bool) {
        self.width = width
        self.height = height
        self.hinge = hinge
        self.division = division
        self.surface = surface
        self.compactWidth = compactWidth
    }
}

public enum ReaderPoseResolver {
    /// Manual choice changes the suggestion only. The rendering adapter must
    /// still respect all active divisions and occlusions in every mode.
    public static func resolve(_ geometry: ReaderGeometry, manual: ReaderPose? = nil) -> ReaderPose {
        if let manual { return manual }
        guard geometry.width.isFinite, geometry.height.isFinite,
              geometry.width > 0, geometry.height > 0 else { return .compact }

        // An active division is authoritative even if hinge events arrive late.
        switch geometry.division {
        case .vertical: return .book
        case .horizontal: return .laptop
        case .none: break
        }

        switch geometry.hinge {
        case .closed: return .compact
        case .partiallyOpen:
            // Wait for the division rather than guessing its axis or width.
            return .compact
        case .open:
            if geometry.surface == .faceUp { return .tabletop }
        case .unavailable:
            // A non-folding screen uses the same branch as a closed foldable,
            // regardless of orientation or the window's size class.
            return .compact
        }

        if geometry.compactWidth { return .compact }
        return geometry.width > geometry.height ? .openLandscape : .openPortrait
    }
}
