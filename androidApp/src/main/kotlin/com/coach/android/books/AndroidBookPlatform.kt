package com.coach.desktop.books

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Path as GraphicsPath
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.coach.ui.pageturn.ScrollReadState
import com.coach.ui.pageturn.ReadScrollbarAndroid
import com.coach.shared.ui.R
import com.coach.ui.theme.CoachTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path

internal const val originalPdfTouchEnabled = true
internal val bookPdfTier1Parallelism: Int = 1

internal typealias BookBitmap = Bitmap
internal typealias BookPdfDocument = com.tom_roush.pdfbox.pdmodel.PDDocument
internal typealias BookPdfRectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle
internal typealias BookPdfCffFont = com.tom_roush.pdfbox.pdmodel.font.PDType1CFont
internal typealias BookPdfRenderingMode = com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
internal typealias BookPdfStripper = com.tom_roush.pdfbox.text.PDFTextStripper
internal typealias BookPdfTextPosition = com.tom_roush.pdfbox.text.TextPosition
internal typealias BookPdfPasswordException = com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
internal typealias BookPdfRenderer = com.tom_roush.pdfbox.rendering.PDFRenderer

private class BoundedPdfResourceCache : com.tom_roush.pdfbox.pdmodel.DefaultResourceCache() {
    // Keep fonts cached across 600+ pages, but never retain page-specific XObjects/images on the Android heap.
    override fun put(indirect: com.tom_roush.pdfbox.cos.COSObject, xobject: com.tom_roush.pdfbox.pdmodel.graphics.PDXObject) = Unit
}

internal object BookPdfLoader {
    fun load(file: File): BookPdfDocument {
        val scratch = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)
        return BookPdfDocument.load(file, scratch).apply { resourceCache = BoundedPdfResourceCache() }
    }
    // Text layers, page aspect lookup and bitmap prefetch may run at the same time.
    // Hold one file-backed parser at a time on the Android app heap.
    @Synchronized fun <T> read(file: File, block: (BookPdfDocument) -> T): T = load(file).use(block)
    fun <T> readCached(file: File, block: (BookPdfDocument) -> T): T = read(file, block)
    fun <P, R> readAndTransfer(
        stageFile: File,
        extract: (BookPdfDocument) -> P,
        commitAndTarget: (P) -> Pair<File?, R>,
    ): R {
        val payload = read(stageFile, extract)
        return commitAndTarget(payload).second
    }
    fun evict(file: File) = Unit
    fun clearCache() = Unit
    fun load(bytes: ByteArray): BookPdfDocument = BookPdfDocument.load(bytes)
}

internal fun readCachedPageBitmap(file: Path): BookBitmap? = null
internal fun writeCachedPageBitmap(file: Path, bitmap: BookBitmap) = Unit

internal fun newBookPdfRenderer(pdf: BookPdfDocument): BookPdfRenderer =
    BookPdfRenderer(pdf).apply { isSubsamplingAllowed = true }

internal fun bookCffHasGlyph(font: BookPdfCffFont, code: Int) = font.hasGlyph(font.codeToName(code))

internal fun measureBookCffInk(font: BookPdfCffFont, code: Int, position: BookPdfTextPosition): BookInkBounds? {
    val path = GraphicsPath(font.getPath(font.codeToName(code)))
    if (path.isEmpty) return null
    fun com.tom_roush.pdfbox.util.Matrix.androidMatrix() = Matrix().apply {
        setValues(floatArrayOf(scaleX, shearX, translateX, shearY, scaleY, translateY, 0f, 0f, 1f))
    }
    val matrix = position.textMatrix.androidMatrix().apply { preConcat(font.fontMatrix.androidMatrix()) }
    path.transform(matrix)
    val bounds = RectF()
    path.computeBounds(bounds, true)
    return BookInkBounds(bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble())
}

internal fun BookBitmap.asBookImageBitmap() = asImageBitmap()
internal fun decodeBookBitmap(file: File): BookBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    BookScanImages.checkSize(bounds.outWidth, bounds.outHeight)
    return BitmapFactory.decodeFile(file.path)
}
internal fun releaseBookBitmap(bitmap: BookBitmap) = bitmap.recycle()
internal fun bookBitmapCropPng(bitmap: BookBitmap, left: Int, top: Int, width: Int, height: Int): ByteArray {
    val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
    try {
        return ByteArrayOutputStream().use { out ->
            check(crop.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
    } finally { if (crop !== bitmap) crop.recycle() }
}
internal fun inspectBookRaster(bytes: ByteArray): BookRaster {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val extension = when (bounds.outMimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        else -> throw IllegalArgumentException("插图格式暂时读不了，请改用 PNG、JPEG 或 GIF。")
    }
    BookScanImages.checkSize(bounds.outWidth, bounds.outHeight)
    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "插图已损坏，请重新保存。" }.recycle()
    return BookRaster(extension, bounds.outWidth, bounds.outHeight)
}

internal fun bookClipboardSelection(entry: ClipEntry?): AnnotatedString? = null
internal fun bookClipboardText(text: String) = ClipEntry(ClipData.newPlainText("教材原文", text))

internal enum class ObsidianPick { Scheduled, Busy, Unsupported }

internal fun bookPickObsidianDirectory(@Suppress("UNUSED_PARAMETER") onResult: (java.nio.file.Path?) -> Unit): ObsidianPick =
    ObsidianPick.Unsupported

internal fun bookSaveNotesMarkdown(
    suggestedFileName: String,
    markdown: String,
    onResult: (NotesFileSave) -> Unit,
): Boolean {
    if (suggestedFileName.isEmpty() && markdown.isEmpty()) {
        onResult(NotesFileSave.Cancelled)
        return false
    }
    onResult(NotesFileSave.Failed("这台设备请先复制"))
    return true
}

@Composable
internal fun BookScrollbar(scroll: ScrollState, modifier: Modifier) {
    val total = scroll.maxValue
    if (total <= 0 || total == Int.MAX_VALUE) return
    val color = CoachTheme.colors.fgSecondary.copy(alpha = 0.55f)
    Canvas(modifier.width(8.dp)) {
        val height = (size.height * size.height / (total + size.height)).coerceIn(12.dp.toPx().coerceAtMost(size.height), size.height)
        val top = (size.height - height) * scroll.value.toFloat() / total
        drawRoundRect(color, Offset(size.width / 2f, top), Size(size.width / 2f, height), CornerRadius(2.dp.toPx()))
    }
}

@Composable
internal fun BoxScope.BookReadScrollbar(scroll: ScrollReadState) = ReadScrollbarAndroid(scroll)

internal typealias BookPdfGraphicsEngine = com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
internal typealias BookPdfName = com.tom_roush.pdfbox.cos.COSName
internal typealias BookPdfPage = com.tom_roush.pdfbox.pdmodel.PDPage
internal typealias BookPdfForm = com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
internal typealias BookPdfImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
internal typealias BookPdfPoint = android.graphics.PointF
internal typealias BookPdfFillRule = android.graphics.Path.FillType
internal fun newBookPdfPoint(x: Float = 0f, y: Float = 0f): BookPdfPoint = BookPdfPoint(x, y)
internal fun bookBitmapPixel(bitmap: BookBitmap, x: Int, y: Int) = bitmap.getPixel(x, y)

internal fun bookWenKaiFontFamily() = FontFamily(Font(R.font.lxgw_wenkai))

internal typealias BookPdfOutlineEntry = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
