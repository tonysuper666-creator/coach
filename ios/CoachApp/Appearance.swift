import SwiftUI
import UIKit

enum Appearance {
    static let name = "学习"

    // Resolve V7's two palettes at render time, including when the device
    // changes appearance while the app is open.
    private static func adaptive(_ light: Color, _ dark: Color) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    static let paper = adaptive(CoachV7.Light.sheet, CoachV7.Dark.sheet)
    static let desk = adaptive(CoachV7.Light.desk, CoachV7.Dark.desk)
    static let card = adaptive(CoachV7.Light.card, CoachV7.Dark.card)
    static let option = adaptive(CoachV7.Light.optBg, CoachV7.Dark.optBg)
    static let ink = adaptive(CoachV7.Light.ink, CoachV7.Dark.ink)
    static let secondary = adaptive(CoachV7.Light.sub, CoachV7.Dark.sub)
    static let line = adaptive(CoachV7.Light.line, CoachV7.Dark.line)
    static let accent = adaptive(CoachV7.Light.zhu, CoachV7.Dark.zhu)
    static let pine = adaptive(CoachV7.Light.pine, CoachV7.Dark.pine)
    static let font = Font.system(.body, design: .serif)
    static let title = Font.system(.title, design: .serif).weight(.semibold)
    static let heading = Font.system(.title2, design: .serif).weight(.semibold)
}

struct V7Card: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(CoachV7.Space.s5)
            .background(Appearance.card, in: RoundedRectangle(cornerRadius: CoachV7.Radius.m))
            .overlay(RoundedRectangle(cornerRadius: CoachV7.Radius.m).stroke(Appearance.line, lineWidth: 0.5))
    }
}
