package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*

/**
 * TON-1890: files dropped onto an open course (through the window's AWT drop target); the receipt shows what each file was taken as,
 * and a past paper that was taken for a material is corrected right there. Synthetic, self-written files only.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CourseIntakeLiveWindowTest {
    private val names = listOf("Axial tilt", "Solar wind", "Lunar phase", "Transit depth", "Stellar parallax")

    @Test
    fun draggedPastPaperIsSortedAndAMistakenTypeIsFixedInPlace() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val previousTheme = System.getProperty("coach.windows.theme")
        val shots = System.getenv("COACH_TON1890_SHOTS")?.takeIf { it.isNotBlank() }?.let(Path::of)
        try {
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                runTheme(dark, shots)
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
        }
    }

    private fun runTheme(dark: Boolean, shots: Path?) {
        val theme = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("coach-ton1890-intake-$theme-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val books = listOf("Textbook", "Slides").map { role ->
                val file = root.resolve("$role.md")
                Files.writeString(file, names.joinToString("\n\n") { "## $it\nA synthetic $role example of $it." })
                library.stageBookImport(file).book
            }
            val courseId = library.withCourseDatabase { db ->
                val (id, _) = CourseStore.createCourse(db, "天文学", CourseExamProfile(examEnabled = true), books.map { it.id })
                CourseAutoAligner.alignLocally(db, id)
                id
            }
            val drop = root.resolve("drop").also(Files::createDirectories)
            val paper = drop.resolve("2023 Final Exam.md").also {
                Files.writeString(it, names.take(3).mapIndexed { i, n -> "${i + 1}. Compare $n." }.joinToString("\n"))
            }
            // Named like a material, but is really a past paper: the user fixes it on the spot.
            val quiz = drop.resolve("Week 2 quiz.md").also {
                Files.writeString(it, "1. Explain Solar wind.\n2. Compare Transit depth.\n3. Compare Stellar parallax.")
            }
            val dropped = listOf(paper, quiz)
            val controller = TextbookController(library)
            val offline = object : BookExplainer(library = library) {
                override suspend fun load() { loaded = true }
            }
            runBlocking { controller.load(); controller.openCourse(courseId) }
            val booksBefore = library.catalog().size
            val window = edt { ComposeWindow().apply {
                title = "Coach · 往年题拖放 · $theme"
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                setBounds(screen.x + 20, screen.y + 20, minOf(1280, screen.width - 40), minOf(900, screen.height - 160))
                setContent { WindowsTextbookHost(controller, bookExplainer = offline) { null } }
                isVisible = true
            } }
            try {
                waitFor("course page") { node(window, "course-exam-point-map") != null }
                dropFiles(window, dropped)

                waitFor("receipt after drop; ${controller.state.value.error}") {
                    controller.state.value.let { !it.busy && it.courseIntake?.items?.size == 2 } && node(window, "course-intake-receipt") != null
                }
                assertNull(controller.state.value.error)
                assertEquals("往年题 ▾", text(window, "course-intake-type-2023 Final Exam.md"))
                assertEquals("提取 3 条考法 · 题干不保存", text(window, "course-intake-meta-2023 Final Exam.md"))
                assertNotEquals("往年题 ▾", text(window, "course-intake-type-Week 2 quiz.md"))
                assertEquals(booksBefore + 1, library.catalog().size, "only the quiz was taken into the library")
                assertEquals(3, library.courseDetail(courseId).pastPaperTechniques.size)
                shot(window, shots, "intake-$theme")

                click(window, "course-intake-type-Week 2 quiz.md")
                waitFor("type menu") { node(window, "course-intake-choice-PAST_PAPER") != null }
                shot(window, shots, "intake-menu-$theme")
                click(window, "course-intake-choice-PAST_PAPER")
                waitFor("quiz turned into past paper") {
                    !controller.state.value.busy && text(window, "course-intake-type-Week 2 quiz.md") == "往年题 ▾"
                }
                assertEquals("提取 3 条考法 · 题干不保存", text(window, "course-intake-meta-Week 2 quiz.md"))
                assertEquals(booksBefore, library.catalog().size, "the copy this drop made is removed again")
                val detail = library.courseDetail(courseId)
                assertEquals(2, detail.materials.size)
                assertEquals(5, detail.pastPaperTechniques.size)
                assertTrue(controller.state.value.courseNotice.orEmpty().startsWith("已把 Week 2 quiz.md 改为往年题"))
                shot(window, shots, "intake-fixed-$theme")
                println("TON1890_INTAKE_LIVE theme=$theme dropped=2 pastPaperTechniques=${detail.pastPaperTechniques.size} books=${library.catalog().size}")
            } finally { edt { window.dispose() } }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Delivers the drag through the window's own AWT DropTarget (the one Compose installs), so the real
     * textbookFileDrop modifier and the host's drop handler run. Robot cannot drag in a session without an interactive desktop.
     */
    private fun dropFiles(window: ComposeWindow, files: List<Path>) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = files.map { it.toFile() }
        }
        edt {
            fun targets(c: Component): List<Component> =
                listOfNotNull(c.takeIf { it.dropTarget != null }) + ((c as? Container)?.components.orEmpty().flatMap(::targets))
            val target = requireNotNull(targets(window).lastOrNull()) { "window has no drop target" }
            val dropTarget = target.dropTarget
            val at = Point(target.width / 2, target.height / 2)
            val context = dropTarget.dropTargetContext
            val action = DnDConstants.ACTION_COPY
            val drag = object : DropTargetDragEvent(context, at, action, action) {
                override fun getTransferable() = transferable
                override fun getCurrentDataFlavors() = transferable.transferDataFlavors
                override fun getCurrentDataFlavorsAsList() = transferable.transferDataFlavors.toList()
                override fun isDataFlavorSupported(df: DataFlavor) = transferable.isDataFlavorSupported(df)
                override fun acceptDrag(dragOperation: Int) {}
                override fun rejectDrag() {}
            }
            dropTarget.dragEnter(drag)
            dropTarget.dragOver(drag)
            dropTarget.drop(object : DropTargetDropEvent(context, at, action, action) {
                override fun getTransferable() = transferable
                override fun getCurrentDataFlavors() = transferable.transferDataFlavors
                override fun getCurrentDataFlavorsAsList() = transferable.transferDataFlavors.toList()
                override fun isDataFlavorSupported(df: DataFlavor) = transferable.isDataFlavorSupported(df)
                override fun acceptDrop(dropAction: Int) {}
                override fun rejectDrop() {}
                override fun dropComplete(success: Boolean) { check(success) { "drop rejected" } }
            })
        }
    }
    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> = window.ownedWindows
        .filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }
    private fun node(window: ComposeWindow, tag: String) =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun text(window: ComposeWindow, tag: String): String = edt {
        fun collect(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap(::collect)
        collect(requireNotNull(nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { tag })
            .joinToString(" ")
    }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(250)
    }
    private fun shot(window: ComposeWindow, dir: Path?, name: String) {
        dir ?: return
        Thread.sleep(350)
        edt {
            window.renderImmediately()
            Files.createDirectories(dir)
            VerifyHooks.capture(window, dir.resolve("$name.png").toFile())
        }
    }
    private fun waitFor(label: String, timeoutSeconds: Long = 30, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for: $label" }
            Thread.sleep(50)
        }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
