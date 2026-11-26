package com.coach.desktop.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperButton

internal data class BookFigurePreview(val bitmap: ImageBitmap, val item: BookIllustration)

internal val PageVisualKind.readerLabel: String get() = when (this) {
    PageVisualKind.ILLUSTRATION -> "插图"
    PageVisualKind.TABLE -> "表格"
    PageVisualKind.FORMULA -> "公式"
    PageVisualKind.CALLOUT -> "知识框"
    PageVisualKind.SIDEBAR -> "边栏"
    PageVisualKind.DIAGRAM -> "示意图"
    PageVisualKind.COMPLEX_REGION -> "原页裁图"
}

@Composable
internal fun BookFigureDialog(figure: BookFigurePreview, onOriginal: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val colors = CoachTheme.colors
    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        BoxWithConstraints(Modifier.fillMaxSize().background(paper).testTag("figure-preview")
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Escape -> {
                        if (event.type == KeyEventType.KeyDown) onDismiss()
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                    Key.PageDown, Key.PageUp, Key.MoveHome, Key.MoveEnd -> true
                    else -> false
                }
            }) {
            Box(Modifier.matchParentSize().testTag("figure-backdrop").pointerInput(onDismiss) {
                detectTapGestures { onDismiss() }
            })
            val maxCaptionHeight = maxHeight / 3
            val padding = if (colors.dark && figure.item.kind == PageVisualKind.ILLUSTRATION) 8.dp else 0.dp
            Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    val aspect = figure.bitmap.width.toFloat() / figure.bitmap.height
                    val width = minOf(figure.bitmap.width.dp, maxWidth - padding * 2,
                        (maxHeight - padding * 2) * aspect).coerceAtLeast(0.dp)
                    val backing = if (colors.dark) Modifier.clip(RoundedCornerShape(CoachV7.Radius.s))
                        .background(CoachV7.Light.pgPaper).padding(padding) else Modifier
                    Image(figure.bitmap, figure.item.caption ?: "第 ${figure.item.page} 页${figure.item.kind.readerLabel}",
                        Modifier.testTag("figure-full-image").pointerInput(Unit) { detectTapGestures {} }
                            .then(backing).size(width, width / aspect), contentScale = ContentScale.Fit)
                }
                figure.item.caption?.takeIf(String::isNotBlank)?.let { caption ->
                    Spacer(Modifier.height(8.dp))
                    Text(caption, Modifier.widthIn(max = figure.bitmap.width.dp).heightIn(max = maxCaptionHeight)
                        .verticalScroll(rememberScrollState()).testTag("figure-caption"),
                        style = MaterialTheme.typography.bodyMedium, fontSize = CoachV7.Size.note,
                        textAlign = TextAlign.Start, color = colors.fgSecondary)
                }
            }
            if (onOriginal != null) PaperButton(onOriginal, Modifier.align(Alignment.TopStart).padding(16.dp)
                .testTag("figure-original")) { Text("查看原页") }
            PaperButton(onDismiss, Modifier.align(Alignment.TopEnd).padding(16.dp)
                .focusRequester(closeFocus).testTag("figure-close")) { Text("关闭") }
            LaunchedEffect(Unit) { closeFocus.requestFocus() }
        }
    }
}
