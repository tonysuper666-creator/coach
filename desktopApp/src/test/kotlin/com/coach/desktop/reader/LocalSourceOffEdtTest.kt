package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.syntheticTextbook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/**
 * 本地书来源的阻塞工作（SQLite、PDFBox、图片编码）不能占住 EDT。用真实 AWT 事件线程做调度器，
 * 用"别的线程持有书库监视器"制造受控阻塞：来源还没返回时，EDT 上的下一个事件必须仍能执行。
 */
class LocalSourceOffEdtTest {
    private val edt = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()

    private fun setup(): Triple<TextbookLibrary, LocalBooksReaderSource, String> {
        val root = Files.createTempDirectory("coach-offedt-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("src")))
        return Triple(library, LocalBooksReaderSource(library), book.id)
    }

    private fun holdLibrary(library: TextbookLibrary): () -> Unit {
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val t = Thread { synchronized(library) { held.countDown(); release.await() } }.apply { isDaemon = true; start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        return { release.countDown(); t.join(5000) }
    }

    private fun edtStaysResponsive(): Boolean {
        val ran = CompletableFuture<Unit>()
        EventQueue.invokeLater { ran.complete(Unit) }
        return try { ran.get(3, TimeUnit.SECONDS); true } catch (_: TimeoutException) { false }
    }

    @Test fun everySourceCallLeavesTheEdtFreeWhileTheLibraryIsBusy() {
        val (library, source, id) = setup()
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "catalog" to { source.catalog() }, "state" to { source.state(id) }, "page" to { source.page(id, 2) },
            "study" to { source.study(id, 2) }, "image" to { source.image(id, 2, "1") }, "boxes" to { source.boxes(id, 2, "1") },
        )
        for ((name, call) in calls) {
            val release = holdLibrary(library)
            val done = CompletableFuture<Unit>()
            val job = kotlinx.coroutines.CoroutineScope(edt).launch { try { call() } finally { done.complete(Unit) } }
            try {
                assertTrue(edtStaysResponsive(), "$name：来源还没返回，EDT 下一个事件却被堵住了")
            } finally { release() }
            done.get(10, TimeUnit.SECONDS); runBlocking { job.join() }
        }
    }

    private class Gated(private val inner: ReaderSource, val gatePage: Int, val gate: CompletableDeferred<Unit>, val entered: CompletableDeferred<Unit>) : ReaderSource by inner {
        override suspend fun page(document: String, pdfPage: Int): com.coach.client.ReaderReply {
            if (pdfPage == gatePage) { entered.complete(Unit); gate.await() }
            return inner.page(document, pdfPage)
        }
    }

    @Test fun aLateReplyForAnEarlierPageNeverOverwritesTheNewerPage() {
        val (_, source, id) = setup()
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val remote = ReaderRemote(Gated(source, 2, gate, entered))
        runBlocking(edt) { remote.loadCatalog(); remote.open(id) }
        val first = kotlinx.coroutines.CoroutineScope(edt).launch { remote.show(2) }
        runBlocking { entered.await() }
        runBlocking(edt) { remote.show(3) }
        assertEquals(3, ((remote.page as PageState.Ready).view.pdfPage))
        gate.complete(Unit); runBlocking { first.join() }
        assertEquals(3, ((remote.page as PageState.Ready).view.pdfPage), "迟到的第 2 页回执不能盖掉第 3 页")
    }

    @Test fun aSourceFailureIsStillReportedAccurately() {
        val (library, source, id) = setup()
        val remote = ReaderRemote(source)
        runBlocking(edt) { remote.loadCatalog(); remote.open(id) }
        Files.delete(library.directory.resolve("$id.pdf"))
        runBlocking(edt) { remote.show(2) }
        assertTrue(remote.boxes is BoxesState.Failed, "原件没了：定位信息必须如实失败，实际 ${remote.boxes}")
    }
}
