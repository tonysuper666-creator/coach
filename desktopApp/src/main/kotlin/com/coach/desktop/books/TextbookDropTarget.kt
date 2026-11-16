package com.coach.desktop.books

import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Path

/** Native file drops use exactly the same import transaction and draft guard as the picker. */
internal fun installTextbookDropTarget(component: Component, onFiles: (List<Path>) -> Unit): AutoCloseable {
    val previous = component.dropTarget
    val target = DropTarget(component, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY)
            else event.rejectDrag()
        }
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            try {
                val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    .orEmpty().mapNotNull { (it as? File)?.toPath() }
                onFiles(files)
                event.dropComplete(true)
            } catch (_: Exception) { event.dropComplete(false) }
        }
    }, true)
    return AutoCloseable {
        target.isActive = false
        if (component.dropTarget === target) component.dropTarget = previous
    }
}
