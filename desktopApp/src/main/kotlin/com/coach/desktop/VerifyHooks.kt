package com.coach.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.coach.client.CourseView
import com.coach.ui.CoachController
import com.coach.ui.threadIdFor
import java.io.File
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container

/**
 * 本机验收辅助（只在显式设置环境变量/系统属性时启用，默认不影响使用）：
 * - `COACH_OPEN_KP` / `COACH_AUTOSTART`：启动即打开某个知识点（可选自动开讲）；
 * - `coach.captures`：`名称@秒,名称@秒`，把窗口当前表面存成 `<coach.captureDir>/<名称>.png`（不需要屏幕录制权限）；
 * - `COACH_AUTOPILOT=test,next,answer`：3 秒后发「出题」、开讲 8 秒后发一次「下一步」；看到未提交的题组，等 3 秒后用第一个选项提交（验证提交与判分链路，不替代真人点按）。
 */
object VerifyHooks {
    private fun Component.skiaLayers(): Sequence<SkiaLayer> = sequence {
        if (this@skiaLayers is SkiaLayer) yield(this@skiaLayers)
        if (this@skiaLayers is Container) for (child in components) yieldAll(child.skiaLayers())
    }

    suspend fun captures(window: ComposeWindow) {
        val plan = System.getProperty("coach.captures")?.split(',')?.mapNotNull {
            val (name, at) = it.split('@').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            name to (at.toDoubleOrNull() ?: return@mapNotNull null)
        }?.sortedBy { it.second } ?: return
        val dir = File(System.getProperty("coach.captureDir") ?: ".").also { it.mkdirs() }
        var elapsed = 0.0
        for ((name, at) in plan) {
            delay(((at - elapsed) * 1000).toLong()); elapsed = at
            val layer = window.skiaLayers().first()
            val bitmap = checkNotNull(layer.screenshot())
            bitmap.use { Image.makeFromBitmap(it).use { image -> image.encodeToData()!!.use { data -> File(dir, "$name.png").writeBytes(data.bytes) } } }
            println("COACH captured $name at ${at}s size=${window.width}x${window.height}")
        }
    }

    suspend fun autopilot(c: CoachController, kpId: String) {
        val steps = System.getenv("COACH_AUTOPILOT")?.split(',').orEmpty()
        if (steps.isEmpty()) return
        val thread = threadIdFor(kpId)
        val submitted = HashSet<String>()
        if ("test" in steps) {
            delay(3000)
            println("COACH autopilot test -> ${c.tutor.next(thread, c.courseId, c.packFor(kpId), true)}")
        }
        if ("next" in steps) {
            delay(8000)
            println("COACH autopilot next -> ${c.tutor.next(thread, c.courseId, c.packFor(kpId), false)}")
        }
        if ("answer" !in steps) return
        while (true) {
            delay(1000)
            val view = CourseView(c.sync.localEvents(c.courseId), c.sync.projection(c.courseId))
            val group = view.pendingGroup(thread) ?: continue
            if (!submitted.add(group.groupId)) continue
            delay(3000)
            val answers = group.cards.associate { it.attemptId to it.options.first().first }
            println("COACH autopilot answering ${group.groupId} -> ${c.tutor.answer(thread, c.courseId, group.groupId, answers, "ui-${group.groupId}")}")
        }
    }
}
