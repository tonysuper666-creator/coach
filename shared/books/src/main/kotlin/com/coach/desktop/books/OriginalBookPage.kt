package com.coach.desktop.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.desktop.reader.Located
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import com.coach.ui.theme.CoachTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal sealed interface PageImage {
    data object Loading : PageImage
    data object Failed : PageImage
    data class Ready(val image: ImageBitmap) : PageImage
}

internal val OriginalPageBitmapWidth = SemanticsPropertyKey<Int>("OriginalPageBitmapWidth")

@Composable
internal fun rememberOriginalPageImage(
    library: TextbookLibrary, book: Textbook, page: Int, enabled: Boolean, pixelWidth: Int?, retry: Int,
): PageImage = key(library, book.id, page) {
    val image by produceState<PageImage>(PageImage.Loading, enabled, pixelWidth, retry) {
        if (!enabled || pixelWidth == 0) return@produceState
        // Keep the current raster and all its overlays during an active resize.
        if (value is PageImage.Ready) delay(200)
        try {
            value = withContext(Dispatchers.IO) { PageImage.Ready(library.renderOriginal(book, page, pixelWidth).asBookImageBitmap()) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (value !is PageImage.Ready) value = PageImage.Failed
        }
    }
    image
}

/** The bitmap, exact citation outline and text overlay share the same measured page. */
@Composable
internal fun OriginalBookPage(
    label: String, image: PageImage, placeholderAspect: Float, layer: ReaderTextUnit?,
    pageText: String, selected: ReaderTextRange?, marks: List<ReaderTextRange>,
    citation: CitedPdfLocation?, quote: String?, onResize: (IntSize) -> Unit, onRetry: () -> Unit,
    onSelection: (ReaderTextRange?) -> Unit, modifier: Modifier = Modifier, roundPdfCorners: Boolean = false,
    active: Boolean = true, inactivePage: Int = 0, knownAspect: Float? = null,
) {
    val colors = CoachTheme.colors
    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    val line = if (colors.dark) CoachV7.Dark.line else CoachV7.Light.line
    val highlight = if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
    val selectionColor = if (colors.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg
    val bitmap = (image as? PageImage.Ready)?.image
    // Use the bitmap ratio once known, while the first layout uses this physical page's paper ratio.
    val ratio = knownAspect ?: bitmap?.let { it.width.toFloat() / it.height } ?: placeholderAspect
    val bounds = modifier.fillMaxWidth().aspectRatio(ratio).onSizeChanged(onResize)
    val pageBounds = if (roundPdfCorners && colors.dark) bounds.clip(RoundedCornerShape(CoachV7.Radius.s)) else bounds
    Box(pageBounds) {
        when (image) {
            PageImage.Loading -> Box(Modifier.matchParentSize().background(paper).border(1.dp, line)
                .testTag("reader-page-placeholder"))
            PageImage.Failed -> Column(Modifier.matchParentSize().background(paper).border(1.dp, line)
                .testTag("reader-page-placeholder"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("这一页暂时打不开。", color = colors.fgSecondary)
                TextButton(onClick = onRetry) { Text("重试") }
            }
            is PageImage.Ready -> {
                Image(image.image, label, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize()
                    .testTag(if (active) "remote-image" else "original-pdf-image-$inactivePage")
                    .semantics { this[OriginalPageBitmapWidth] = image.image.width })
                if (citation?.located is Located.Found) Canvas(Modifier.testTag("remote-highlight")
                    .semantics { contentDescription = "原句：$quote" }.matchParentSize()) {
                    val boxes = citation.boxes
                    val lines = mergePdfLineBoxes(citation.located.rects.map { rect ->
                        ReaderRect(rect.x0 / boxes.width, rect.y0 / boxes.height,
                            (rect.x1 - rect.x0) / boxes.width, (rect.y1 - rect.y0) / boxes.height)
                    }, size.width, size.height)
                    val padding = 2.dp.toPx()
                    for (rect in lines) drawRect(
                        highlight,
                        topLeft = Offset((rect.x * size.width).toFloat() - padding, (rect.y * size.height).toFloat() - padding),
                        size = Size((rect.width * size.width).toFloat() + 2 * padding, (rect.height * size.height).toFloat() + 2 * padding),
                        style = Stroke(padding),
                    )
                }
                if (layer != null && layer.spans.isNotEmpty()) {
                    OriginalPdfSelection(layer, pageText, selected, marks, selectionColor, highlight, onSelection,
                        layerTag = if (active) "original-pdf-text-layer" else "original-pdf-text-layer-$inactivePage")
                }
            }
        }
    }
}
