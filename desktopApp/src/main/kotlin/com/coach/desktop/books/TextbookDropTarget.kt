package com.coach.desktop.books

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

/** Attach inside Compose: its Skia surface owns the native window's drop target. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.textbookFileDrop(onFiles: (List<Path>) -> Unit): Modifier {
    val current by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = try {
                val files = (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    .orEmpty().mapNotNull { (it as? File)?.toPath() }
                current(files)
                true
            } catch (_: Exception) { false }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}
