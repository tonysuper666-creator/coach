package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
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

internal typealias BookBitmap = BufferedImage
internal typealias BookPdfDocument = org.apache.pdfbox.pdmodel.PDDocument
internal typealias BookPdfRectangle = org.apache.pdfbox.pdmodel.common.PDRectangle
internal typealias BookPdfCffFont = org.apache.pdfbox.pdmodel.font.PDType1CFont
internal typealias BookPdfRenderingMode = org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
internal typealias BookPdfStripper = org.apache.pdfbox.text.PDFTextStripper
internal typealias BookPdfTextPosition = org.apache.pdfbox.text.TextPosition
internal typealias BookPdfPasswordException = org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
internal typealias BookPdfRenderer = org.apache.pdfbox.rendering.PDFRenderer

internal object BookPdfLoader {
    fun load(file: File): BookPdfDocument = Loader.loadPDF(file)
    fun load(bytes: ByteArray): BookPdfDocument = Loader.loadPDF(bytes)
}

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

internal fun BookBitmap.asBookImageBitmap() = toComposeImageBitmap()
internal fun decodeBookBitmap(file: File): BookBitmap? = ImageIO.read(file)
internal fun releaseBookBitmap(bitmap: BookBitmap) = bitmap.flush()
internal fun bookBitmapCropPng(bitmap: BookBitmap, left: Int, top: Int, width: Int, height: Int): ByteArray {
    val crop = bitmap.getSubimage(left, top, width, height)
    try {
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(crop, "png", out)); out.toByteArray()
        }
    } finally { crop.flush() }
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
internal fun bookClipboardSelection(entry: ClipEntry?): AnnotatedString? {
    val data = entry?.asAwtTransferable ?: return null
    return if (data.isDataFlavorSupported(bookTextFlavor)) data.getTransferData(bookTextFlavor) as? AnnotatedString else null
}
internal fun bookClipboardText(text: String) = ClipEntry(StringSelection(text))

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
internal typealias BookPdfPoint = java.awt.geom.Point2D
internal typealias BookPdfFillRule = Int
internal fun newBookPdfPoint(x: Float = 0f, y: Float = 0f): BookPdfPoint = java.awt.geom.Point2D.Float(x, y)
internal fun bookBitmapPixel(bitmap: BookBitmap, x: Int, y: Int) = bitmap.getRGB(x, y)

@Composable
internal fun BookWorkspaceBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
