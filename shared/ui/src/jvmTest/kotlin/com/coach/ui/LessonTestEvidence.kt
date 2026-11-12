package com.coach.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only the invented lesson fixtures use this directory; never collect the private corpus screenshots. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.captureLessonEvidence(name: String, width: Int? = null, height: Int? = null,
                                                captureRoot: SemanticsNodeInteraction? = null) {
    val directory = File(requireNotNull(System.getProperty("coach.testScreens")) { "Missing Gradle lesson evidence directory" })
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create lesson evidence directory" }
    val output = File(directory, "$name.png")
    assertTrue(ImageIO.write((captureRoot ?: onRoot()).captureToImage().toAwtImage(), "png", output), "PNG evidence writer must exist")
    val decoded = requireNotNull(ImageIO.read(output)) { "PNG evidence could not be read back" }
    if (width != null) assertEquals(width, decoded.width, "Actual evidence width")
    if (height != null) assertEquals(height, decoded.height, "Actual evidence height")
    File(directory, "$name.txt").writeText((captureRoot ?: onRoot(useUnmergedTree = true)).printToString(maxDepth = 50))
    println("COACH_LESSON_EVIDENCE=${output.absolutePath} width=${decoded.width} height=${decoded.height}")
}
