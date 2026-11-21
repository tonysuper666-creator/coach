// V7 案头 · 定稿：V6 案头打底：宋体标题、周页大号周数、正文上标对页边、专心读即白描
// 由 build/tokens.mjs 生成，数值与审阅稿截图同源。单位：sp/dp（Fold8 内屏为示意估算，S1 实测后校准）。
// 字体全部 SIL OFL 1.1，可随包分发；中文字体建议按常用字子集化（GB2312 一级 + 标点：思源宋体约 2.9 MB、思源黑体约 2.1 MB、霞鹜文楷约 1.9 MB、朱雀仿宋约 2.4 MB），子集外的字回落系统字体。
// 西中混排：Compose 的 FontFamily 不能像 CSS 那样逐字回落，用 Typeface.CustomFallbackBuilder（API 29+）把西文放前、中文放后；
// 西文字体子集化时去掉 U+00B7 U+2014 U+2018–201F U+2026，让间隔号、破折号、中文引号、省略号落到中文字体（与 CSS unicode-range 一致）。
package coach.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object CoachV7 {
    object Light {
        val tocBg = Color(0xFFF7F6F3)
        val railBg = Color(0xFFF7F6F3)
        val pgPaper = Color(0xFFFFFFFF)
        val pgTxt = Color(0xFFE6E4DF)
        val menuBg = Color(0xFFFFFFFF)
        val menuInk = Color(0xFF1A1A18)
        val menuSep = Color(0x14000000)
        val optBg = Color(0xFFFFFFFF)
        val pineWash = Color(0x143E7948)
        val dangerWash = Color(0x14A83A2B)
        val zhuWash = Color(0x1A8A6A44)
        val btnBg = Color(0xFFFFFFFF)
        val btnPriBg = Color(0xFF8A6A44)
        val btnPriInk = Color(0xFFFFFFFF)
        val hingeBg = Color(0xFFE4E2DC)
        val noteInk = Color(0xFF6B6A65)
        val hlSolid = Color(0xFFEED596)
        val selBg = Color(0x268A6A44)
        val desk = Color(0xFFF7F6F3)
        val sheet = Color(0xFFFFFFFF)
        val card = Color(0xFFF0EFEC)
        val ink = Color(0xFF1A1A18)
        val sub = Color(0xFF6B6A65)
        val faint = Color(0xFF7D7B75)
        val line = Color(0x14000000)
        val border = Color(0x14000000)
        val accent = Color(0xFF8A6A44)
        val accentHi = Color(0xFFA5824F)
        val accentLo = Color(0xFF6A5432)
        val accentSoft = Color(0x1A8A6A44)
        val onAccent = Color(0xFFFFFFFF)
        val danger = Color(0xFFA83A2B)
        val dangerSolid = Color(0xFFA83A2B)
        val onDanger = Color(0xFFFFFFFF)
        val ok = Color(0xFF3E7948)
        val warn = Color(0xFFB7791F)
        val zhu = Color(0xFFA83A2B)
        val pine = Color(0xFF3E7948)
        val hl = Color(0x66EED596)
        val scrim = Color(0x8008080A)
    }
    object Dark {
        val tocBg = Color(0xFF0E0E10)
        val railBg = Color(0xFF0E0E10)
        val pgPaper = Color(0xFF17171A)
        val pgTxt = Color(0xFF2B2B30)
        val menuBg = Color(0xFF1F1F23)
        val menuInk = Color(0xFFE8E6E3)
        val menuSep = Color(0x14FFFFFF)
        val optBg = Color(0xFF1F1F23)
        val pineWash = Color(0x217FBF8A)
        val dangerWash = Color(0x21E08A76)
        val zhuWash = Color(0x1FD8B98E)
        val btnBg = Color(0xFF1F1F23)
        val btnPriBg = Color(0xFFD8B98E)
        val btnPriInk = Color(0xFF141210)
        val hingeBg = Color(0xFF000000)
        val noteInk = Color(0xFF8A8A90)
        val hlSolid = Color(0xFF5A4B32)
        val selBg = Color(0x2ED8B98E)
        val desk = Color(0xFF0E0E10)
        val sheet = Color(0xFF17171A)
        val card = Color(0xFF1F1F23)
        val ink = Color(0xFFE8E6E3)
        val sub = Color(0xFF8A8A90)
        val faint = Color(0xFF838389)
        val line = Color(0x14FFFFFF)
        val border = Color(0x14FFFFFF)
        val accent = Color(0xFFD8B98E)
        val accentHi = Color(0xFFEBD4B0)
        val accentLo = Color(0xFF8A6A44)
        val accentSoft = Color(0x1FD8B98E)
        val onAccent = Color(0xFF141210)
        val danger = Color(0xFFE08A76)
        val dangerSolid = Color(0xFFC2543F)
        val onDanger = Color(0xFFFFFFFF)
        val ok = Color(0xFF7FBF8A)
        val warn = Color(0xFFD99A3D)
        val zhu = Color(0xFFE08A76)
        val pine = Color(0xFF7FBF8A)
        val hl = Color(0x47D8B98E)
        val scrim = Color(0x8008080A)
    }
    object Size {
        val micro = 11.sp
        val body = 18.sp
        val ui = 13.sp
        val uiBody = 15.sp
        val input = 16.sp
        val lg = 17.sp
        val cap = 12.sp
        val note = 15.sp
        val handnote = 16.sp
        val heading = 20.sp
        val section = 24.sp
        val title = 30.sp
        val deck = 17.sp
        val stem = 17.sp
        val opt = 16.sp
        val row = 15.sp
    }
    object LineHeight {
        val tight = 1.28f
        val ui = 1.55f
        val doc = 1.75f
        val body = 1.85f
        val title = 1.3f
    }
    object Weight {
        val body = FontWeight(400)
        val ui = FontWeight(400)
        val uiStrong = FontWeight(600)
        val cap = FontWeight(500)
        val title = FontWeight(600)
        val deck = FontWeight(400)
        val h3 = FontWeight(600)
        val stem = FontWeight(500)
        val rowNow = FontWeight(600)
        val bodyDark = FontWeight(370) // 深色正文降字重
    }
    object Tracking { val cap = 0.02.em; val title = 0.01.em }
    object Space {
        val s1 = 4.dp
        val s1b = 6.dp
        val s2 = 8.dp
        val s2b = 10.dp
        val s3 = 12.dp
        val s4 = 16.dp
        val s5 = 20.dp
        val s6 = 24.dp
        val s7 = 32.dp
        val s8 = 48.dp
    }
    object Radius {
        val s = 6.dp
        val m = 10.dp
        val btn = 10.dp
        val sheet = 14.dp
        val lg = 14.dp
        val composer = 28.dp
        val pill = 999.dp
    }
    object Layout { // 内屏
        val barH = 52.dp
        val footH = 52.dp
        val wToc = 200.dp
        val wRail = 324.dp
        val navW = 0.dp
        val colw = 594.dp
        val mainPt = 52.dp
        val mainPx = 64.dp
        val pad = 28.dp
        val optH = 48.dp
        val btnH = 36.dp
    }
    object Motion {
        const val fastMs = 120
        const val baseMs = 160
        const val slowMs = 220
        const val progressMs = 350
        const val arriveMs = 600
        const val blinkMs = 240
        const val breatheMs = 4000
        const val pageMs = 220
        const val expandMs = 160
        val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
    object Grain { const val light = 0f; const val dark = 0f } // 纸纹不透明度，0 = 不用
}
