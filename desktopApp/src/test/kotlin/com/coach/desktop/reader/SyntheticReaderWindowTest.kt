package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/**
 * Windows 桌面阅读器：合成样例文档（orchard-text）开卷、翻页与位置定位验证。
 * 验证假数据窗口替换为真实资料消费、学到某一处按位置直接打开对应页。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class SyntheticReaderWindowTest {
    private class Rig(val session: ReaderSession, widthDp: Int, heightDp: Int = 700) {
        var t = 0L
        val scene = ImageComposeScene(widthDp, heightDp, Density(1f)) { ReaderWindow(session) }
        init { frames(8) }
        fun frames(n: Int = 4) { repeat(n) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(2) } }
        fun key(k: Key) {
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp))
            frames(8)
        }
        fun wheel(dy: Float, xDp: Int = 400, yDp: Int = 300) {
            val pos = Offset(xDp.toFloat(), yDp.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, pos)
            scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = Offset(0f, dy))
            frames(8)
        }
        fun shot(name: String) {
            val dir = File("build/reader-shots").also { it.mkdirs() }
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { File(dir, "$name.png").writeBytes(it.bytes) } }
        }
        fun close() = scene.close()
    }

    private fun rig(source: ReaderSource = SyntheticReaderSource(), width: Int = 1200): Pair<Rig, ReaderSource> {
        val remote = ReaderRemote(source)
        val session = ReaderSession(ReaderFixture("资料", emptyList(), emptyList()), width, remote)
        return Rig(session, width) to source
    }

    private fun Rig.text(): String? = (session.remote?.page as? PageState.Ready)?.view?.text
    private fun Rig.page(): Int? = (session.remote?.page as? PageState.Ready)?.view?.pdfPage

    @Test
    fun opensSyntheticDocumentAndFlipsPages() {
        val (r, source) = rig()
        try {
            runBlocking {
                r.session.remote!!.loadCatalog()
                r.session.openRemote("orchard-text")
            }
            r.frames(10)

            // 初始在第 1 页
            assertEquals(1, r.page())
            assertEquals(0, r.session.state.position.page)
            assertEquals(5, r.session.state.pageCount)
            assertTrue(r.text()?.contains("果园 建设 与 管理 基础") == true, "第一页文本来自真实资料结构")

            // 键盘 PageDown 翻页到第 2 页
            r.session.focusPane(ReaderPane.Body)
            r.frames()
            r.key(Key.PageDown)
            assertEquals(2, r.page())
            assertEquals(1, r.session.state.position.page)
            assertTrue(r.text()?.contains("果树 修剪 与 花果 管理") == true, "翻至第 2 页正文更新")

            // 检查服务端/数据源进度已保存至第 2 页
            val stateAfterNext = runBlocking { source.state("orchard-text") }
            val savedPage = (stateAfterNext.body?.get("progress") as? kotlinx.serialization.json.JsonObject)
                ?.get("pdfPage")?.toString()?.toIntOrNull()
            assertEquals(2, savedPage, "翻页后真实保存了进度页码")

            // 使用 session.nextPage() 翻到第 3 页
            r.session.nextPage()
            r.frames(10)
            assertEquals(3, r.page())
            assertTrue(r.text()?.contains("病虫害 综合 防治 技术") == true, "nextPage() 翻至第 3 页")

            // 使用 session.prevPage() 翻回第 2 页
            r.session.prevPage()
            r.frames(10)
            assertEquals(2, r.page())
            assertTrue(r.text()?.contains("果树 修剪 与 花果 管理") == true, "prevPage() 翻回第 2 页")

            r.shot("synthetic-reader-open-flip")
        } finally {
            r.close()
        }
    }

    @Test
    fun opensAtLearnedLocationDirectly() {
        val (r, _) = rig()
        try {
            runBlocking {
                r.session.remote!!.loadCatalog()
                // 学到某一处（例如第 3 章节核心考点）：按位置直接打开第 3 页
                r.session.openRemoteAt("orchard-text", targetPdfPage = 3, anchor = "预防 为主 ， 综合 防治")
            }
            r.frames(10)

            // 验证直接定位至第 3 页（0 索引为 2）
            assertEquals(3, r.page(), "直接打开至学到处的第 3 页")
            assertEquals(2, r.session.state.position.page)
            assertEquals("预防 为主 ， 综合 防治", r.session.state.flashAnchor)
            assertTrue(r.text()?.contains("病虫害 综合 防治 技术") == true)

            // 从学到处继续翻页至第 4 页
            r.session.nextPage()
            r.frames(10)
            assertEquals(4, r.page())
            assertTrue(r.text()?.contains("节水 灌溉 与 水肥 一体化") == true)

            // 直接按位置跳到拓展阅读第 2 页
            runBlocking {
                r.session.openRemoteAt("orchard-read", targetPdfPage = 2, anchor = "果园 巡检 机器视觉")
            }
            r.frames(10)
            assertEquals(2, r.page())
            assertEquals("orchard-read", r.session.remote?.current?.id)
            assertTrue(r.text()?.contains("智慧 农业 与 无人 农机 应用") == true)

            r.shot("synthetic-reader-learned-location")
        } finally {
            r.close()
        }
    }

    @Test
    fun sourceResolverFallsBackToSyntheticSource() {
        val source = ReaderSourceResolver.resolve(
            env = { null },
            prop = { null },
        )
        assertTrue(source is SyntheticReaderSource, "无环境变量时自动回落至合成样例文档源")
        val catalog = runBlocking { source.catalog() }
        assertEquals(200, catalog.status)
        val docs = (catalog.body?.get("documents") as? kotlinx.serialization.json.JsonArray)
        assertNotNull(docs)
        assertTrue(docs.size >= 2, "包含 orchard-text 与 orchard-read 两份合成文档")
    }

    @Test
    fun reopeningSamePageDoesNotStayIdle() {
        val (r, _) = rig()
        try {
            runBlocking {
                r.session.remote!!.loadCatalog()
                r.session.openRemote("orchard-text")
            }
            r.frames(10)
            assertEquals(1, r.page())
            assertIs<PageState.Ready>(r.session.remote!!.page)

            // 首次跨页打开第 3 页
            runBlocking { r.session.openRemoteAt("orchard-text", 3, "q-orchard-text-3-1") }
            r.frames(10)
            assertEquals(3, r.page())
            assertIs<PageState.Ready>(r.session.remote!!.page)
            assertIs<StudyState.Ready>(r.session.remote!!.study)

            // 再次重复调用打开同一文档同一页
            runBlocking { r.session.openRemoteAt("orchard-text", 3, "q-orchard-text-3-1") }
            r.frames(10)
            assertEquals(3, r.page())
            // 关键回归断言：修复同页重开停在 Idle 缺陷，状态必须处于 Ready
            assertIs<PageState.Ready>(r.session.remote!!.page, "同书同页重开后正文必须正常加载就绪，不能停在 Idle")
            assertIs<StudyState.Ready>(r.session.remote!!.study, "同书同页重开后题目必须就绪")
        } finally {
            r.close()
        }
    }

    @Test
    fun rawAnchorPhraseHighlightsWhenMatchedAndReportsWhenUnlocated() {
        val (r, _) = rig()
        try {
            runBlocking {
                r.session.remote!!.loadCatalog()
                // 传纯原句文本（非题目 ID），第 3 页包含该短语
                r.session.openRemoteAt("orchard-text", 3, "预防 为主 ， 综合 防治")
            }
            r.frames(10)
            assertEquals(3, r.page())
            // 关键回归断言：原句短语能够被正确解释并高亮
            assertEquals("已在页上标出原句", r.session.uiHighlight)

            // 传不存在的未映射原句短语：清楚提示，不假装已高亮
            runBlocking {
                r.session.openRemoteAt("orchard-text", 3, "不存在 的 原句 文本 无法 匹配")
            }
            r.frames(10)
            assertEquals("没找到这句原文，无法确认位置", r.session.uiHighlight, "缺少映射时给出明确提示")
        } finally {
            r.close()
        }
    }
}
