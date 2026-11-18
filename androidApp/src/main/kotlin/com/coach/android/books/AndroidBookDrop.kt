package com.coach.desktop.books

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import com.coach.android.copyPickedBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.nio.file.Path

private tailrec fun Context.bookDropActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.bookDropActivity()
    else -> null
}

internal fun Modifier.textbookFileDrop(onDrop: (List<Path>) -> Unit): Modifier = composed {
    val context = LocalContext.current
    val activity = context.bookDropActivity() ?: return@composed this
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onDrop)
    val target = remember(activity, scope) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val native = event.toAndroidDragEvent()
                val data = native.clipData ?: return false
                if (data.itemCount != 1) {
                    Toast.makeText(activity, "请一次拖入一份教材。", Toast.LENGTH_SHORT).show()
                    return true
                }
                val source = data.getItemAt(0).uri?.takeIf { it.scheme == "content" } ?: return false
                val permission = activity.requestDragAndDropPermissions(native)
                scope.launch {
                    try { callback.value(listOf(copyPickedBook(activity, source))) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { Toast.makeText(activity, "无法读取这份教材，请从文件选择器重新导入。", Toast.LENGTH_SHORT).show() }
                    finally { permission?.release() }
                }
                return true
            }
        }
    }
    this.dragAndDropTarget(shouldStartDragAndDrop = { event ->
        event.mimeTypes().any { it.startsWith("application/") || it.startsWith("image/") || it.startsWith("text/") }
    }, target = target)
}

@Composable
internal fun BookWorkspaceBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled, onBack)
}
