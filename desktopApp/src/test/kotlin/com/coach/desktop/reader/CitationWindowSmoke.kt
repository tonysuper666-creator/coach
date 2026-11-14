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

/** Self-contained native window + real loopback HTTP + generated three-page PDF. No pairing/config. */
@OptIn(InternalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
object CitationWindowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if (System.getProperty("os.name").startsWith("Windows")) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
        SyntheticCitationServer().use { fixture ->
            val session = fixture.session()
            var failure: Throwable? = null
            var passed = false
            println("SYNTHETIC citation fixture ready on ${fixture.base}; question fruit1; PDF pages=3")
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
                                ImageIO.write(robot.createScreenCapture(window.bounds), "png", output)
                            }
                            window.toFront(); window.requestFocus(); delay(700)
                            until("HTTP question") { session.question != null }
                            click("explanation-choice-1")
                            until("chosen answer") { session.choice == 1 }
                            click("explanation-draft")
                            delay(500)
                            val draft = "Why two unsubmitted draft"
                            draft.forEach { ch ->
                                val key = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                                if (ch.isUpperCase()) robot.keyPress(KeyEvent.VK_SHIFT)
                                robot.keyPress(key); robot.keyRelease(key)
                                if (ch.isUpperCase()) robot.keyRelease(KeyEvent.VK_SHIFT)
                                delay(15)
                            }
                            until("typed draft") { session.draft == draft && session.choice == 1 }
                            click("explanation-ask")
                            until("real HTTP source") { session.citations.size == 1 }
                            screenshot("question")
                            click("explanation-source-0")
                            until("PDF content drawn") { session.clickToDrawMs != null && node("reader-back") != null }
                            val view = (session.opened!!.remote!!.page as PageState.Ready).view
                            check(view.pdfPage == 1 && view.document == "synthetic-book" && view.image != null)
                            until("visible block highlight") { node("remote-highlight") != null }
                            check(session.opened!!.state.flashAnchor == fixture.blockText)
                            screenshot("page")
                            click("reader-back")
                            until("return draft") { session.opened == null && node("explanation-draft") != null }
                            check(session.owner == ExplanationOwner("SYNTHETIC", 1, "fruit1"))
                            check(session.choice == 1 && session.draft == draft && fixture.bridgeRequests.get() == 1)
                            screenshot("returned")
                            passed = true
                            println("NATIVE_CLICK_PASS os=${System.getProperty("os.name")} physicalPage=1 block=true ownerRetained=true choiceRetained=true draftRetained=true clickToDrawMs=${session.clickToDrawMs}")
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
        }
    }
}
