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
        val tocBg = Color(0xFFE8E2D6)
        val railBg = Color(0xFFE8E2D6)
        val pgPaper = Color(0xFFFFFFFF)
        val pgTxt = Color(0xFFE3DCCF)
        val menuBg = Color(0xFFFFFFFF)
        val menuInk = Color(0xFF24211C)
        val menuSep = Color(0xFFE3DCCF)
        val optBg = Color(0xFFFFFFFF)
        val pineWash = Color(0x1A2F6A4B)
        val zhuWash = Color(0x14A63E2C)
        val btnBg = Color(0xFFFFFFFF)
        val btnPriBg = Color(0xFF24211C)
        val btnPriInk = Color(0xFFFCFAF6)
        val hingeBg = Color(0xFFD6CFC1)
        val noteInk = Color(0xFF665F55)
        val hlSolid = Color(0xFFEED596)
        val selBg = Color(0x17A63E2C)
        val desk = Color(0xFFE8E2D6)
        val sheet = Color(0xFFFCFAF6)
        val card = Color(0xFFFBF8F2)
        val ink = Color(0xFF24211C)
        val sub = Color(0xFF665F55)
        val faint = Color(0xFF857D71)
        val line = Color(0xFFE3DCCF)
        val zhu = Color(0xFFA63E2C)
        val pine = Color(0xFF2F6A4B)
        val hl = Color(0x8CF0D282)
    }
    object Dark {
        val tocBg = Color(0xFF0E0D0B)
        val railBg = Color(0xFF0E0D0B)
        val pgPaper = Color(0xFF24211D)
        val pgTxt = Color(0xFF34302A)
        val menuBg = Color(0xFF2A2722)
        val menuInk = Color(0xFFE6E0D5)
        val menuSep = Color(0xFF34302A)
        val optBg = Color(0xFF23201C)
        val pineWash = Color(0x267FB394)
        val zhuWash = Color(0x21E07A68)
        val btnBg = Color(0xFF2A2722)
        val btnPriBg = Color(0xFFE6E0D5)
        val btnPriInk = Color(0xFF1B1916)
        val hingeBg = Color(0xFF000000)
        val noteInk = Color(0xFFA0978A)
        val hlSolid = Color(0xFF6A5730)
        val selBg = Color(0x29E07A68)
        val desk = Color(0xFF0E0D0B)
        val sheet = Color(0xFF1B1916)
        val card = Color(0xFF23201C)
        val ink = Color(0xFFE6E0D5)
        val sub = Color(0xFFA0978A)
        val faint = Color(0xFF8F8A80)
        val line = Color(0xFF34302A)
        val zhu = Color(0xFFE07A68)
        val pine = Color(0xFF7FB394)
        val hl = Color(0x42C8A050)
    }
    object Size { // 内屏；外屏与 Duo 见 V7.json → size
        val body = 18.sp
        val ui = 13.5.sp
        val cap = 12.sp
        val note = 15.sp
        val handnote = 16.sp
        val title = 30.sp
        val deck = 18.sp
        val stem = 19.sp
        val opt = 17.sp
        val row = 17.sp
    }
    object LineHeight { val body = 1.85f; val title = 1.3f } // 倍数：lineHeight = fontSize × 倍数
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
        val s2 = 8.dp
        val s3 = 12.dp
        val s4 = 16.dp
        val s5 = 20.dp
        val s6 = 24.dp
        val s7 = 32.dp
        val s8 = 48.dp
    }
    object Radius { val s = 6.dp; val m = 10.dp; val btn = 8.dp; val sheet = 14.dp }
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
    object Motion { // 像 Things：打开一项时它“变成一张纸”
        const val pageMs = 300 // 300ms 纸张上滑 8px + 淡入，轻微回弹
        const val expandMs = 260 // 260ms 卡片展开
        val easing = CubicBezierEasing(0.34f, 1.2f, 0.64f, 1f)
        // 系统“移除动画”打开时（Settings.Global.ANIMATOR_DURATION_SCALE == 0）全部改 0ms
    }
    object Grain { const val light = 0f; const val dark = 0f } // 纸纹不透明度，0 = 不用
}
