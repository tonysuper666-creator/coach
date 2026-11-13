// V7 案头 · 定稿：V6 案头打底：宋体标题、周页大号周数、正文上标对页边、专心读即白描
// 由 build/tokens.mjs 生成，数值与审阅稿截图同源。单位：pt（Fold8 尺寸为示意估算）。
// 字体全部 SIL OFL 1.1，可随包分发。西中混排用 UIFontDescriptor 的 .cascadeList：西文字体为主，中文字体排在回落链里。
// Color(hex:alpha:) 定义在 CoachColor+Hex.swift。
import SwiftUI

enum CoachV7 {
    enum Light {
        static let tocBg = Color(hex: 0xE8E2D6)
        static let railBg = Color(hex: 0xE8E2D6)
        static let pgPaper = Color(hex: 0xFFFFFF)
        static let pgTxt = Color(hex: 0xE3DCCF)
        static let menuBg = Color(hex: 0xFFFFFF)
        static let menuInk = Color(hex: 0x24211C)
        static let menuSep = Color(hex: 0xE3DCCF)
        static let optBg = Color(hex: 0xFFFFFF)
        static let pineWash = Color(hex: 0x2F6A4B, alpha: 0.1)
        static let zhuWash = Color(hex: 0xA63E2C, alpha: 0.08)
        static let btnBg = Color(hex: 0xFFFFFF)
        static let btnPriBg = Color(hex: 0x24211C)
        static let btnPriInk = Color(hex: 0xFCFAF6)
        static let hingeBg = Color(hex: 0xD6CFC1)
        static let noteInk = Color(hex: 0x665F55)
        static let hlSolid = Color(hex: 0xEED596)
        static let selBg = Color(hex: 0xA63E2C, alpha: 0.09)
        static let desk = Color(hex: 0xE8E2D6)
        static let sheet = Color(hex: 0xFCFAF6)
        static let card = Color(hex: 0xFBF8F2)
        static let ink = Color(hex: 0x24211C)
        static let sub = Color(hex: 0x665F55)
        static let faint = Color(hex: 0x857D71)
        static let line = Color(hex: 0xE3DCCF)
        static let zhu = Color(hex: 0xA63E2C)
        static let pine = Color(hex: 0x2F6A4B)
        static let hl = Color(hex: 0xF0D282, alpha: 0.55)
    }
    enum Dark {
        static let tocBg = Color(hex: 0x0E0D0B)
        static let railBg = Color(hex: 0x0E0D0B)
        static let pgPaper = Color(hex: 0x24211D)
        static let pgTxt = Color(hex: 0x34302A)
        static let menuBg = Color(hex: 0x2A2722)
        static let menuInk = Color(hex: 0xE6E0D5)
        static let menuSep = Color(hex: 0x34302A)
        static let optBg = Color(hex: 0x23201C)
        static let pineWash = Color(hex: 0x7FB394, alpha: 0.15)
        static let zhuWash = Color(hex: 0xE07A68, alpha: 0.13)
        static let btnBg = Color(hex: 0x2A2722)
        static let btnPriBg = Color(hex: 0xE6E0D5)
        static let btnPriInk = Color(hex: 0x1B1916)
        static let hingeBg = Color(hex: 0x000000)
        static let noteInk = Color(hex: 0xA0978A)
        static let hlSolid = Color(hex: 0x6A5730)
        static let selBg = Color(hex: 0xE07A68, alpha: 0.16)
        static let desk = Color(hex: 0x0E0D0B)
        static let sheet = Color(hex: 0x1B1916)
        static let card = Color(hex: 0x23201C)
        static let ink = Color(hex: 0xE6E0D5)
        static let sub = Color(hex: 0xA0978A)
        static let faint = Color(hex: 0x8F8A80)
        static let line = Color(hex: 0x34302A)
        static let zhu = Color(hex: 0xE07A68)
        static let pine = Color(hex: 0x7FB394)
        static let hl = Color(hex: 0xC8A050, alpha: 0.26)
    }
    enum Size { // 内屏
        static let body: CGFloat = 18
        static let ui: CGFloat = 13.5
        static let cap: CGFloat = 12
        static let note: CGFloat = 15
        static let handnote: CGFloat = 16
        static let title: CGFloat = 30
        static let deck: CGFloat = 18
        static let stem: CGFloat = 19
        static let opt: CGFloat = 17
        static let row: CGFloat = 17
    }
    enum LineHeight { static let body: CGFloat = 1.85; static let title: CGFloat = 1.3 } // 倍数；SwiftUI 用 .lineSpacing(size × (倍数 − 1))
    enum Weight {
        static let body: CGFloat = 400
        static let ui: CGFloat = 400
        static let uiStrong: CGFloat = 600
        static let cap: CGFloat = 500
        static let title: CGFloat = 600
        static let deck: CGFloat = 400
        static let h3: CGFloat = 600
        static let stem: CGFloat = 500
        static let rowNow: CGFloat = 600
        static let bodyDark: CGFloat = 370
    }
    enum Tracking { static let capEm: CGFloat = 0.02; static let titleEm: CGFloat = 0.01 } // .tracking(size × em)
    enum Space {
        static let s1: CGFloat = 4
        static let s2: CGFloat = 8
        static let s3: CGFloat = 12
        static let s4: CGFloat = 16
        static let s5: CGFloat = 20
        static let s6: CGFloat = 24
        static let s7: CGFloat = 32
        static let s8: CGFloat = 48
    }
    enum Radius { static let s: CGFloat = 6; static let m: CGFloat = 10; static let btn: CGFloat = 8; static let sheet: CGFloat = 14 }
    enum Motion { // 像 Things：打开一项时它“变成一张纸”；accessibilityReduceMotion 打开时全部改纯淡入或 0
        static let page = Animation.timingCurve(0.34, 1.2, 0.64, 1, duration: 0.3)
        static let expand = Animation.timingCurve(0.34, 1.2, 0.64, 1, duration: 0.26)
    }
    enum Font { // PostScript 族名以随包字体为准
        static let body = ["Literataopsz-wght", "NotoSerifSCwght"] // Literata + 思源宋体 400
        static let ui = ["IBMPlexSanswdth-wght", "NotoSansSCwght"] // IBM Plex Sans + 思源黑体
        static let cap = ["IBMPlexSanswdth-wght", "NotoSansSCwght"] // 思源黑体 500
        static let title = ["Literataopsz-wght", "NotoSerifSCwght"] // 思源宋体 600 + Literata，30px（取 V2/V4：标题和正文同一家）
        static let deck = ["Literataopsz-wght", "NotoSerifSCwght"] // 思源宋体 400 + Literata
        static let note = ["IBMPlexSanswdth-wght", "LXGWWenKai-Regular"] // 霞鹜文楷（自己的批注）
        static let num = ["ibmplexmono_IBMPlexMono-Regular", "NotoSansSCwght"] // IBM Plex Mono（时间戳、页码）
        static let mono = ["ibmplexmono_IBMPlexMono-Regular", "NotoSansSCwght"] // IBM Plex Mono
    }
}
