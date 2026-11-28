package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.coach.ui.pageturn.ReadScrollbarDesktop
import com.coach.ui.pageturn.ScrollReadState
import com.coach.ui.v7.V7Scrollbar
import org.apache.pdfbox.Loader
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.text.rtf.RTFEditorKit

internal const val originalPdfTouchEnabled = false

internal typealias BookBitmap = BufferedImage
internal typealias BookPdfDocument = org.apache.pdfbox.pdmodel.PDDocument
internal typealias BookPdfRectangle = org.apache.pdfbox.pdmodel.common.PDRectangle
internal typealias BookPdfCffFont = org.apache.pdfbox.pdmodel.font.PDType1CFont
internal typealias BookPdfRenderingMode = org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
internal typealias BookPdfStripper = org.apache.pdfbox.text.PDFTextStripper
internal typealias BookPdfTextPosition = org.apache.pdfbox.text.TextPosition
internal typealias BookPdfPasswordException = org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
internal typealias BookPdfRenderer = org.apache.pdfbox.rendering.PDFRenderer

internal val bookPdfTier1Parallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

internal object BookPdfLoader {
    private const val IN_MEMORY_PDF_LIMIT = 16L * 1024 * 1024
    private const val MAX_CACHED_DOCS = 2
    private val mixedMemory = org.apache.pdfbox.io.MemoryUsageSetting.setupMixed(16L * 1024 * 1024).streamCache

    private class CachedPdfEntry(
        val key: String,
        val length: Long,
        val lastModified: Long,
        val document: BookPdfDocument,
    ) {
        val lock = java.util.concurrent.locks.ReentrantLock()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var retired = false

        fun closeOnce() {
            if (closed.compareAndSet(false, true)) {
                runCatching { document.close() }
            }
        }
    }

    private val cacheLock = Any()
    private val docCache = LinkedHashMap<String, CachedPdfEntry>(4, 0.75f, true)

    private fun cacheKey(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun loadFast(file: File): BookPdfDocument =
        if (file.isFile && file.length() in 1L..IN_MEMORY_PDF_LIMIT) {
            Loader.loadPDF(Files.readAllBytes(file.toPath()))
        } else {
            Loader.loadPDF(file, mixedMemory)
        }

    fun <T> read(file: File, block: (BookPdfDocument) -> T): T = loadFast(file).use(block)

    fun <T> readCached(file: File, block: (BookPdfDocument) -> T): T {
        if (!file.isFile) return read(file, block)
        val key = cacheKey(file)
        val len = file.length()
        val mod = file.lastModified()
        var entry: CachedPdfEntry?
        var toClose: CachedPdfEntry? = null
        synchronized(cacheLock) {
            val existing = docCache[key]
            if (existing != null && existing.length == len && existing.lastModified == mod && !existing.retired) {
                entry = existing
            } else {
                if (existing != null) {
                    docCache.remove(key)
                    existing.retired = true
                    toClose = existing
                }
                entry = null
            }
        }
        toClose?.let(::closeEntrySafely)
        val active: CachedPdfEntry = entry ?: run {
            val freshDoc = loadFast(file)
            val fresh = CachedPdfEntry(key, len, mod, freshDoc)
            val evicted = mutableListOf<CachedPdfEntry>()
            var useExisting: CachedPdfEntry? = null
            synchronized(cacheLock) {
                val race = docCache[key]
                if (race != null && race.length == len && race.lastModified == mod && !race.retired) {
                    useExisting = race
                } else {
                    if (race != null) {
                        docCache.remove(key)
                        race.retired = true
                        evicted += race
                    }
                    docCache[key] = fresh
                    while (docCache.size > MAX_CACHED_DOCS) {
                        val eldestKey = docCache.keys.iterator().next()
                        if (eldestKey == key) break
                        val removed = docCache.remove(eldestKey)
                        if (removed != null) {
                            removed.retired = true
                            evicted += removed
                        }
                    }
                }
            }
            val raceWinner = useExisting
            if (raceWinner != null) {
                runCatching { freshDoc.close() }
                raceWinner
            } else {
                evicted.forEach(::closeEntrySafely)
                fresh
            }
        }
        active.lock.lock()
        var succeeded = false
        try {
            if (active.retired || active.length != file.length() || active.lastModified != file.lastModified()) {
                synchronized(cacheLock) {
                    if (docCache[key] === active) docCache.remove(key)
                    active.retired = true
                }
                active.lock.unlock()
                active.closeOnce()
                return read(file, block)
            }
            val result = block(active.document)
            succeeded = true
            return result
        } finally {
            if (!succeeded && active.lock.isHeldByCurrentThread) {
                synchronized(cacheLock) {
                    if (docCache[key] === active) docCache.remove(key)
                    active.retired = true
                }
            }
            if (active.lock.isHeldByCurrentThread) {
                val shouldClose = active.retired
                active.lock.unlock()
                if (shouldClose) active.closeOnce()
            }
        }
    }

    private fun closeEntrySafely(entry: CachedPdfEntry) {
        entry.lock.lock()
        try {
            entry.closeOnce()
        } finally {
            entry.lock.unlock()
        }
    }

    fun <P, R> readAndTransfer(
        stageFile: File,
        extract: (BookPdfDocument) -> P,
        commitAndTarget: (P) -> Pair<File?, R>,
    ): R {
        val inMemory = stageFile.isFile && stageFile.length() in 1L..IN_MEMORY_PDF_LIMIT
        val doc = if (inMemory) {
            Loader.loadPDF(Files.readAllBytes(stageFile.toPath()))
        } else {
            Loader.loadPDF(stageFile, mixedMemory)
        }
        var transferred = false
        try {
            val payload = extract(doc)
            if (!inMemory) {
                // File-backed RandomAccessReadBufferedFile keeps stageFile's Path for per-thread rafCopies;
                // close it before moving stageFile so no handle is held and docCache never points at a moved path.
                runCatching { doc.close() }
            }
            val (target, result) = commitAndTarget(payload)
            if (inMemory && target != null && target.isFile) {
                seedCache(target, doc)
                transferred = true
            }
            return result
        } finally {
            if (!transferred) runCatching { doc.close() }
        }
    }

    private fun seedCache(file: File, doc: BookPdfDocument) {
        val key = cacheKey(file)
        val len = file.length()
        val mod = file.lastModified()
        val entry = CachedPdfEntry(key, len, mod, doc)
        val evicted = mutableListOf<CachedPdfEntry>()
        synchronized(cacheLock) {
            docCache.remove(key)?.let {
                it.retired = true
                evicted += it
            }
            docCache[key] = entry
            while (docCache.size > MAX_CACHED_DOCS) {
                val eldestKey = docCache.keys.iterator().next()
                if (eldestKey == key) break
                val removed = docCache.remove(eldestKey)
                if (removed != null) {
                    removed.retired = true
                    evicted += removed
                }
            }
        }
        evicted.forEach(::closeEntrySafely)
    }

    fun evict(file: File) {
        val key = cacheKey(file)
        val removed = synchronized(cacheLock) {
            docCache.remove(key)?.also { it.retired = true }
        }
        if (removed != null) closeEntrySafely(removed)
    }

    fun clearCache() {
        val removed = synchronized(cacheLock) {
            docCache.values.toList().also { list ->
                list.forEach { it.retired = true }
                docCache.clear()
            }
        }
        removed.forEach(::closeEntrySafely)
    }

    fun load(file: File): BookPdfDocument = Loader.loadPDF(file, mixedMemory)
    fun load(file: File, password: String): BookPdfDocument = Loader.loadPDF(file, password, tempFileOnly)
    fun load(bytes: ByteArray): BookPdfDocument = Loader.loadPDF(bytes)

    /** One document, spilled to disk. Used for 200+ page imports so the heap stays bounded. */
    fun <T> readLarge(file: File, block: (BookPdfDocument) -> T): T =
        Loader.loadPDF(file, tempFileOnly).use(block)
}

private val tempFileOnly = org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().streamCache

/** Rewrite [file] without its password. The password is not stored. */
internal fun unlockBookPdf(file: File, password: String) {
    val tmp = File(file.parentFile, "${file.name}.decrypted")
    try {
        Loader.loadPDF(file, password, tempFileOnly).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(tmp)
        }
        java.nio.file.Files.move(
            tmp.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        if (tmp.exists()) runCatching { tmp.delete() }
    }
}

internal fun newBookPdfRenderer(pdf: BookPdfDocument): BookPdfRenderer =
    BookPdfRenderer(pdf).apply { isSubsamplingAllowed = true }

internal fun bookCffHasGlyph(font: BookPdfCffFont, code: Int) = font.hasGlyph(code)

internal fun measureBookCffInk(font: BookPdfCffFont, code: Int, position: BookPdfTextPosition): BookInkBounds? {
    val path = font.getPath(code)
    if (path.bounds2D.isEmpty) return null
    val iterator = path.getPathIterator(null)
    var segments = 0
    while (!iterator.isDone) {
        if (++segments > 10_000) return null
        iterator.next()
    }
    val transform = position.textMatrix.createAffineTransform()
    transform.concatenate(font.fontMatrix.createAffineTransform())
    val bounds = transform.createTransformedShape(path).bounds2D
    return BookInkBounds(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY)
}

internal fun BookBitmap.asBookImageBitmap(): ImageBitmap {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return toComposeImageBitmap()
    val pixelCount = w.toLong() * h.toLong()
    if (pixelCount > Int.MAX_VALUE / 4) return toComposeImageBitmap()
    val count = pixelCount.toInt()
    val imgType = type
    val sampleModel = raster.sampleModel as? java.awt.image.SinglePixelPackedSampleModel
    val rawBuf = if (
        (imgType == BufferedImage.TYPE_INT_RGB || imgType == BufferedImage.TYPE_INT_ARGB) &&
        sampleModel != null && sampleModel.scanlineStride == w &&
        raster.sampleModelTranslateX == 0 && raster.sampleModelTranslateY == 0
    ) {
        raster.dataBuffer as? java.awt.image.DataBufferInt
    } else null
    if (rawBuf == null || rawBuf.offset != 0 || rawBuf.size != count) return toComposeImageBitmap()
    val pixels = rawBuf.data
    val bytes = ByteArray(count * 4)
    val alphaType: org.jetbrains.skia.ColorAlphaType
    if (imgType == BufferedImage.TYPE_INT_RGB) {
        alphaType = org.jetbrains.skia.ColorAlphaType.OPAQUE
        var o = 0
        for (i in 0 until count) {
            val argb = pixels[i]
            bytes[o] = argb.toByte()
            bytes[o + 1] = (argb ushr 8).toByte()
            bytes[o + 2] = (argb ushr 16).toByte()
            bytes[o + 3] = -1
            o += 4
        }
    } else {
        alphaType = org.jetbrains.skia.ColorAlphaType.UNPREMUL
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, count)
    }
    val skia = org.jetbrains.skia.Bitmap()
    val info = org.jetbrains.skia.ImageInfo(
        w, h, org.jetbrains.skia.ColorType.BGRA_8888, alphaType, org.jetbrains.skia.ColorSpace.sRGB,
    )
    if (!skia.allocPixels(info) || !skia.installPixels(bytes)) {
        skia.close()
        return toComposeImageBitmap()
    }
    skia.setImmutable()
    return skia.asComposeImageBitmap()
}
internal fun decodeBookBitmap(file: File): BookBitmap? = ImageIO.read(file)
internal fun releaseBookBitmap(bitmap: BookBitmap) = bitmap.flush()

private const val BITMAP_CACHE_MAGIC = 0x43424D31

internal fun readCachedPageBitmap(file: Path): BookBitmap? = runCatching {
    if (!Files.isRegularFile(file)) return null
    val bytes = Files.readAllBytes(file)
    if (bytes.size < 16) return null
    val header = java.nio.ByteBuffer.wrap(bytes, 0, 16)
    if (header.int != BITMAP_CACHE_MAGIC) return null
    val w = header.int
    val h = header.int
    val imgType = header.int
    if (w !in 1..bookPdfRasterLimit || h !in 1..bookPdfRasterLimit) return null
    if (imgType != BufferedImage.TYPE_INT_RGB && imgType != BufferedImage.TYPE_INT_ARGB) return null
    val pixelCount = w.toLong() * h.toLong()
    if (bytes.size.toLong() != 16L + pixelCount * 4L) return null
    val image = BufferedImage(w, h, imgType)
    val dst = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
    java.nio.ByteBuffer.wrap(bytes, 16, (pixelCount * 4L).toInt()).asIntBuffer().get(dst)
    image
}.getOrNull()

internal fun writeCachedPageBitmap(file: Path, bitmap: BookBitmap) {
    runCatching {
        val w = bitmap.width
        val h = bitmap.height
        if (w !in 1..bookPdfRasterLimit || h !in 1..bookPdfRasterLimit) return
        val pixelCount = w * h
        val imgType = if (bitmap.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val rawBuf = if (bitmap.type == BufferedImage.TYPE_INT_RGB || bitmap.type == BufferedImage.TYPE_INT_ARGB) {
            bitmap.raster.dataBuffer as? java.awt.image.DataBufferInt
        } else null
        val pixels = if (rawBuf != null && rawBuf.size == pixelCount) rawBuf.data else bitmap.getRGB(0, 0, w, h, null, 0, w)
        val bytes = ByteArray(16 + pixelCount * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes)
        buf.putInt(BITMAP_CACHE_MAGIC)
        buf.putInt(w)
        buf.putInt(h)
        buf.putInt(imgType)
        buf.asIntBuffer().put(pixels)
        file.parent?.let(Files::createDirectories)
        val tmp = file.resolveSibling(".${file.fileName}.${System.nanoTime()}.tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
internal fun bookBitmapCropPng(bitmap: BookBitmap, left: Int, top: Int, width: Int, height: Int): ByteArray {
    val crop = bitmap.getSubimage(left, top, width, height)
    try {
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(crop, "png", out)); out.toByteArray()
        }
    } finally { crop.flush() }
}

internal fun cropBookBitmap(bitmap: BookBitmap, left: Int, top: Int, width: Int, height: Int): BookBitmap {
    val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    copy.createGraphics().let { g ->
        try { g.drawImage(bitmap, 0, 0, width, height, left, top, left + width, top + height, null) }
        finally { g.dispose() }
    }
    return copy
}

internal fun inspectBookRaster(bytes: ByteArray): BookRaster = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
    val readers = ImageIO.getImageReaders(input)
    require(readers.hasNext()) { "插图格式暂时读不了，请改用 PNG、JPEG 或 GIF。" }
    val reader = readers.next()
    try {
        reader.input = input
        val width = reader.getWidth(0); val height = reader.getHeight(0)
        BookScanImages.checkSize(width, height)
        val extension = when (reader.formatName.lowercase()) {
            "png" -> "png"; "jpeg", "jpg" -> "jpg"; "gif" -> "gif"
            else -> throw IllegalArgumentException("插图格式暂时读不了，请改用 PNG、JPEG 或 GIF。")
        }
        requireNotNull(reader.read(0)) { "插图已损坏，请重新保存。" }.flush()
        BookRaster(extension, width, height)
    } finally { reader.dispose() }
}

internal fun readBookRtf(path: Path): String {
    val kit = RTFEditorKit()
    val document = kit.createDefaultDocument()
    Files.newInputStream(path).use { kit.read(it, document, 0) }
    return document.getText(0, document.length)
}

private val bookTextFlavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
@OptIn(ExperimentalComposeUiApi::class)
internal fun bookClipboardSelection(entry: ClipEntry?): AnnotatedString? {
    val data = entry?.asAwtTransferable ?: return null
    return if (data.isDataFlavorSupported(bookTextFlavor)) data.getTransferData(bookTextFlavor) as? AnnotatedString else null
}
@OptIn(ExperimentalComposeUiApi::class)
internal fun bookClipboardText(text: String) = ClipEntry(StringSelection(text))

private val notesExportDialogOpen = java.util.concurrent.atomic.AtomicBoolean(false)
private val obsidianDirectoryDialogOpen = java.util.concurrent.atomic.AtomicBoolean(false)

internal enum class ObsidianPick { Scheduled, Busy, Unsupported }

/** 选一个 Obsidian 库或其中的文件夹。对话框放到下一个 AWT 事件里开，避免同一次点击开两个。 */
internal fun bookPickObsidianDirectory(
    dialogTitle: String = "导出到 Obsidian",
    approveButtonText: String = "导出到这里",
    onResult: (java.nio.file.Path?) -> Unit,
): ObsidianPick {
    if (!obsidianDirectoryDialogOpen.compareAndSet(false, true)) return ObsidianPick.Busy
    java.awt.EventQueue.invokeLater {
        val picked = try {
            val chooser = javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                this.dialogTitle = dialogTitle
                this.approveButtonText = approveButtonText
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
        } catch (_: Exception) {
            null
        } finally {
            obsidianDirectoryDialogOpen.set(false)
        }
        onResult(picked)
    }
    return ObsidianPick.Scheduled
}

/**
 * 保存框放到下一个 AWT 事件里开。若在 Compose 点击回调里直接打开，模态框会把同一次点击再派发一遍，出现两个保存框。
 * 返回 false 表示这次没有排上（已经有一个框）。
 */
internal fun bookSaveNotesMarkdown(
    suggestedFileName: String,
    markdown: String,
    onResult: (NotesFileSave) -> Unit,
): Boolean {
    if (!notesExportDialogOpen.compareAndSet(false, true)) {
        onResult(NotesFileSave.Busy)
        return false
    }
    java.awt.EventQueue.invokeLater {
        val result = try {
            showNotesSaveDialog(suggestedFileName, markdown)
        } catch (_: Exception) {
            NotesFileSave.Failed("没有写成文件")
        } finally {
            notesExportDialogOpen.set(false)
        }
        onResult(result)
    }
    return true
}

private fun showNotesSaveDialog(suggestedFileName: String, markdown: String): NotesFileSave {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "导出笔记", java.awt.FileDialog.SAVE)
    dialog.file = suggestedFileName
    dialog.isVisible = true
    val chosenName = dialog.file ?: return NotesFileSave.Cancelled
    val directory = dialog.directory ?: return NotesFileSave.Cancelled
    val path = java.nio.file.Path.of(directory, chosenName)
    val parent = path.parent ?: return NotesFileSave.Failed("保存位置无效")
    if (generateSequence(parent) { it.parent }.any { java.nio.file.Files.exists(it.resolve(".git")) }) {
        return NotesFileSave.Failed("请选 Git 仓库以外的文件夹")
    }
    return try {
        java.nio.file.Files.write(path, notesMarkdownUtf8Bytes(markdown))
        NotesFileSave.Saved(path.fileName.toString())
    } catch (_: Exception) {
        NotesFileSave.Failed("没有写成文件")
    }
}

@Composable
internal fun BookScrollbar(scroll: ScrollState, modifier: Modifier) =
    V7Scrollbar(rememberScrollbarAdapter(scroll), modifier)

@Composable
internal fun BoxScope.BookReadScrollbar(scroll: ScrollReadState) = ReadScrollbarDesktop(scroll)

internal fun bookWorkspaceThemeOverride(): Boolean? = com.coach.desktop.windowsThemeOverride(System.getProperty("coach.windows.theme"))

internal typealias BookPdfGraphicsEngine = org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
internal typealias BookPdfName = org.apache.pdfbox.cos.COSName
internal typealias BookPdfPage = org.apache.pdfbox.pdmodel.PDPage
internal typealias BookPdfForm = org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
internal typealias BookPdfImage = org.apache.pdfbox.pdmodel.graphics.image.PDImage
internal typealias BookPdfImageObject = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
internal typealias BookPdfPoint = java.awt.geom.Point2D
internal typealias BookPdfFillRule = Int
internal fun newBookPdfPoint(x: Float = 0f, y: Float = 0f): BookPdfPoint = java.awt.geom.Point2D.Float(x, y)
internal fun bookBitmapPixel(bitmap: BookBitmap, x: Int, y: Int) = bitmap.getRGB(x, y)

@Composable
internal fun BookWorkspaceBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

internal fun bookWenKaiFontFamily() = FontFamily(Font("font/lxgw_wenkai.ttf"))

internal typealias BookPdfOutlineEntry = org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
