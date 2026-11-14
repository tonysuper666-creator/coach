package com.coach.desktop.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/** Real native clicks through the host connection contract to a Mac synthetic HTTP/PDF fixture. */
@OptIn(InternalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
object CitationFallbackWindowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if (System.getProperty("os.name").startsWith("Windows")) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
        check(System.getenv("COACH_COURSE") == "SYNTHETIC") { "Explicit synthetic server configuration required" }
        val item = System.getenv("COACH_READER_ITEM") ?: error("Explicit synthetic question required")
        val (http, session) = explanationConnection(item)
        try {
            var failure: Throwable? = null
            var passed = false
            println("SYNTHETIC native client; question fruit1; Mac server configured")
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Coach · Synthetic citation navigation", state = rememberWindowState(width = 1200.dp, height = 900.dp)) {
                    ReaderExplanationWorkspace(session)
                    LaunchedEffect(Unit) { System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { delay(it * 1000); exitApplication() } }
                    LaunchedEffect(window) {
                        if (!java.lang.Boolean.getBoolean("coach.citation.auto")) return@LaunchedEffect
                        try {
                            fun node(tag: String): SemanticsNode? {
                                fun find(n: SemanticsNode): SemanticsNode? =
                                    if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n
                                    else n.children.firstNotNullOfOrNull(::find)
                                return window.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
                            }
                            suspend fun until(label: String, test: () -> Boolean) {
                                val end = System.nanoTime() + 25_000_000_000
                                while (!test()) { check(System.nanoTime() < end) { "Timed out: $label; ${session.error}" }; delay(30) }
                            }
                            val robot = Robot().apply { autoDelay = 50 }
                            suspend fun click(tag: String) {
                                until(tag) { node(tag) != null }
                                val bounds = checkNotNull(node(tag)).boundsInRoot
                                val origin = window.contentPane.locationOnScreen
                                val scale = window.graphicsConfiguration.defaultTransform
                                robot.mouseMove(origin.x + (bounds.center.x / scale.scaleX).toInt(), origin.y + (bounds.center.y / scale.scaleY).toInt())
                                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                delay(80)
                            }
                            fun screenshot(name: String) {
                                val output = File("build/screens/citation-$name.png"); output.parentFile.mkdirs()
                                ImageIO.write(robot.createScreenCapture(java.awt.Rectangle(window.contentPane.locationOnScreen, window.contentPane.size)), "png", output)
                            }
                            window.toFront(); window.requestFocus(); delay(700)
                            until("HTTP question") { session.question != null }
                            click("explanation-choice-1")
                            until("chosen answer") { session.choice == 1 }
                            click("explanation-draft")
                            delay(500)
                            val draft = "why two unsubmitted draft"
                            suspend fun typeDraft() {
                                draft.forEach { ch ->
                                    val key = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                                    robot.keyPress(key); robot.keyRelease(key); delay(15)
                                }
                                delay(400)
                            }
                            // Own synthetic TextField only: Microsoft Pinyin uses Shift to switch English.
                            robot.keyPress(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_SHIFT); delay(300)
                            typeDraft()
                            if (session.draft != draft) {
                                // If it was English already, the first Shift selected Chinese. Restore once.
                                robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
                                robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_A)
                                robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_CONTROL)
                                robot.keyPress(KeyEvent.VK_BACK_SPACE); robot.keyRelease(KeyEvent.VK_BACK_SPACE)
                                robot.keyPress(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_SHIFT); delay(300)
                                typeDraft()
                            }
                            until("typed draft") { session.draft == draft && session.choice == 1 }
                            click("explanation-ask")
                            until("real HTTP source") { session.citations.size == 1 }
                            val first = session.citations.single()
                            check(!first.isBlock && first.document == "synthetic-book" && first.sourceVersion == "a".repeat(64) && first.pdfPage == 1)
                            click("explanation-source-0")
                            until("authorized page drawn") {
                                session.clickToDrawMs != null && node("reader-back") != null &&
                                    node("citation-mode") != null && node("line-0") != null &&
                                    session.opened?.remote?.page is PageState.Ready
                            }
                            val view = (session.opened!!.remote!!.page as PageState.Ready).view
                            check(view.document == first.document && view.pdfPage == 1 && view.image == null && view.text == first.text)
                            check(node("line-0")!!.config.getOrNull(SemanticsProperties.Text)?.any { it.text == first.text } == true)
                            screenshot("fallback")
                            println("NATIVE_FALLBACK_PASS doc=${first.document} sourceVersion=${first.sourceVersion} pdfPage=${first.pdfPage} drawMs=${session.clickToDrawMs}")
                            click("reader-back")
                            until("return from fallback") { session.opened == null && node("explanation-draft") != null }
                            check(session.owner == ExplanationOwner("SYNTHETIC", 1, "fruit1") && session.choice == 1 && session.draft == draft)
                            click("explanation-ask")
                            until("changed-version citation") { session.citations.size == 1 && session.citations.single().isBlock }
                            val changed = session.citations.single()
                            click("explanation-source-0")
                            until("visible version refusal") { session.error != null && node("explanation-error") != null }
                            check(session.opened == null && node("reader-back") == null && node("line-0") == null)
                            check(session.owner == ExplanationOwner("SYNTHETIC", 1, "fruit1") && session.choice == 1 && session.draft == draft)
                            val response = ReaderHttpSource(http.reader).page(changed.document, changed.pdfPage)
                            check(!response.ok) { "Changed PDF should be refused over HTTP, got ${response.status}" }
                            screenshot("refused")
                            println("NATIVE_CHANGED_PDF_REFUSED httpStatus=${response.status} error=${session.error} oldPage=false owner=true choice=true draft=true")
                            val receipt = """{"fallback":true,"doc":"synthetic-book","sourceVersion":"${first.sourceVersion}","pdfPage":1,"changedPdfHttpStatus":${response.status},"oldPageVisible":false,"ownerRetained":true,"choiceRetained":true,"draftRetained":true}"""
                            File("build/screens/fallback-native-receipt.json").writeText(receipt)
                            passed = true
                            println("NATIVE_FALLBACK_SEQUENCE_PASS os=${System.getProperty("os.name")} $receipt")
                        } catch (e: Throwable) {
                            failure = e
                            System.err.println("NATIVE_CLICK_FAIL choice=${session.choice} draftLength=${session.draft.length} error=${session.error}")
                            val capture = File("build/screens/citation-failed.png"); capture.parentFile.mkdirs()
                            runCatching { ImageIO.write(Robot().createScreenCapture(window.bounds), "png", capture) }
                            e.printStackTrace()
                        }
                        finally { exitApplication() }
                    }
                }
            }
            println("SYNTHETIC return owner=${session.owner} choice=${session.choice} draftRetained=${session.draft.isNotEmpty()} firstReadyDrawMs=${session.clickToDrawMs}")
            failure?.let { throw it }
            check(!java.lang.Boolean.getBoolean("coach.citation.auto") || passed) { "Native automatic click did not complete" }
        } finally { http.close() }
    }
}
