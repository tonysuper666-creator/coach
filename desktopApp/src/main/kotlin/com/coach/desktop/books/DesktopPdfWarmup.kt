package com.coach.desktop.books

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * 进程内只做一次的 PDF 渲染预热：用内存里生成的一页带字 PDF 走一遍 loadPDF → renderImage，
 * 把 PDFBox 类加载、字体缓存和 JIT 的冷启动代价挪到后台（打开书本/阅读器时就开始），不占目标页的首次绘制。
 * 只在内存里造 PDF，不读用户文件；失败静默（预热不是功能）。[done] 返回预热耗时毫秒，失败为 -1。
 */
internal object PdfWarmup {
    val done = java.util.concurrent.CompletableFuture<Long>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val t0 = System.nanoTime()
            val ms = try {
                val out = java.io.ByteArrayOutputStream()
                org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                    val page = org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page)
                    org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 14f)
                        cs.newLineAtOffset(72f, 700f); cs.showText("Warm up 0123456789"); cs.endText()
                    }
                    doc.save(out)
                }
                Loader.loadPDF(out.toByteArray()).use { pdf -> PDFRenderer(pdf).renderImage(0, 1.5f) }
                (System.nanoTime() - t0) / 1_000_000
            } catch (_: Throwable) { -1L }
            if (java.lang.Boolean.getBoolean("coach.render.trace")) println("COACH_RENDER_WARMUP ms=$ms")
            done.complete(ms)
        }, "pdf-warmup").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }.start()
    }
}
