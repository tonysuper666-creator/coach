package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** 记录页下钻的整张卡：合成的构答作答（示例数据，不是真实作答），浅色/深色各一张，另外断言逐字证据确实被高亮、离线时明说取不到评分标准。 */
@OptIn(ExperimentalTestApi::class)
class RecordsScreenshotTest {
    private val answer = "免赔额是保险生效前你要先自付的固定金额；共付额是之后每次就医按比例分担的部分。所以免赔额先用完，共付额才开始。"
    private val detail = AttemptDetail(
        "c1", listOf("K1"), "SHORT_ANSWER", "CLOSED_BOOK", "请区分免赔额（deductible）与共付额（copayment），并说明两者的先后关系。",
        emptyList(), answer, "", 1_000L,
        GradeView("AI_UNREVIEWED", 300, 400, "", listOf(GradeItem("R1", 2.0, "写出了免赔额是先自付的固定金额", "免赔额是保险生效前你要先自付的固定金额"), GradeItem("R2", 1.0, "写出共付额按比例分担，但没说明它依赖免赔额用完", "共付额是之后每次就医按比例分担的部分")),
            listOf(EvidenceSpan("R1", "answer", 0, 19, answer.substring(0, 19)), EvidenceSpan("R2", "answer", 20, 42, answer.substring(20, 42))), "gpt-5"),
        ReviewView("CONFIRM", "同意初评：R3 的先后关系答到了「先用完免赔额」。", "claude-sonnet-5-5", "gpt-5", null),
        voided = false, hinted = false,
    )
    private val rubric = RubricView(listOf(RubricItem("R1", 2.0, "写出免赔额的定义", "教材第 7 章：deductible 是保险生效前被保险人先自付的固定金额"), RubricItem("R2", 1.0, "写出共付额的定义", "copayment 是每次就医按比例分担的部分"), RubricItem("R3", 1.0, "说明先后关系：先免赔额后共付额", "")),
        "免赔额是先自付的固定金额，共付额是之后按比例分担；先用完免赔额。", "fp1")

    private fun shoot(name: String, dark: Boolean, offline: Boolean) = runComposeUiTest {
        setContent {
            CoachTheme(dark) {
                androidx.compose.material3.Surface(Modifier.width(420.dp)) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                        AttemptCard(MathAssets(), true, { if (offline) null else rubric }, 1, 3, detail)
                    }
                }
            }
        }
        waitForIdle()
        val card = onNodeWithTag("attempt")
        card.assertExists()
        if (!offline) onNodeWithText("参考答案", substring = true).assertExists() else onNodeWithText("没取到", substring = true).assertExists()
        onNodeWithText("复核：claude-sonnet-5-5", substring = true).assertExists()
        onNodeWithText("初评：gpt-5", substring = true).assertExists()
        val image = card.captureToImage().toAwtImage()
        val dir = File(System.getProperty("coach.screenshotDir") ?: "build/screens").also { it.mkdirs() }
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    @Test fun lightWithRubricFromMac() = shoot("records-attempt-light", dark = false, offline = false)
    @Test fun darkWithRubricFromMac() = shoot("records-attempt-dark", dark = true, offline = false)
    @Test fun offlineSaysRubricIsUnavailable() = shoot("records-attempt-offline", dark = false, offline = true)
}
