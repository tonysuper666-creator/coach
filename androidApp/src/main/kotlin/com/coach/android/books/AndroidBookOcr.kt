package com.coach.desktop.books

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt

// ML Kit bundled Chinese OCR achieves full accuracy around 180-200 dpi (~2000 px long edge)
// while avoiding 3x pixel/RAM overhead that stalls software/emulator pipelines.
internal const val bookOcrTargetDpi = 200f
internal const val bookOcrMaxSide = 2000f

internal object BookScanImages {
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_SIDE = 8000
    const val MAX_OCR_PAGES = 2000

    fun checkSize(width: Int, height: Int) {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS) {
            "这页图片太大，请缩到 1600 万像素以内，且长宽均不超过 8000 像素后再导入。"
        }
    }

    fun read(path: Path): BookBitmap = requireNotNull(decodeBookBitmap(path.toFile())) {
        "这张图片打不开，请重新保存为 PNG 或 JPG 后导入。"
    }
}

internal object BundledBookOcr : BookOcr {
    private const val PAGE_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 500L
    private val recognizerLock = Any()
    @Volatile private var cachedRecognizer: TextRecognizer? = null

    private fun recognizer(): TextRecognizer = cachedRecognizer ?: synchronized(recognizerLock) {
        cachedRecognizer ?: OcrTiming.measure("client") {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }.also { cachedRecognizer = it }
    }

    /** Produce an isolated, bounded bitmap whose lifetime is owned by the ML Kit task. */
    internal fun prepareBitmap(image: Bitmap): Bitmap {
        val longEdge = maxOf(image.width, image.height)
        val maxSide = bookOcrMaxSide.toInt()
        if (longEdge > maxSide) {
            val scale = bookOcrMaxSide / longEdge
            val targetW = (image.width * scale).roundToInt().coerceAtLeast(32)
            val targetH = (image.height * scale).roundToInt().coerceAtLeast(32)
            return Bitmap.createScaledBitmap(image, targetW, targetH, true)
        }
        return image.copy(Bitmap.Config.ARGB_8888, false)
            ?: Bitmap.createScaledBitmap(image, image.width.coerceAtLeast(32), image.height.coerceAtLeast(32), false)
    }

    override fun recognize(image: BookBitmap, control: BookImportControl): String {
        control.check()
        val owned = prepareBitmap(image)
        OcrTiming.mark("bitmap", "${owned.width}x${owned.height}")
        var handoffToTask = false
        try {
            val client = recognizer()
            val input = OcrTiming.measure("input") { InputImage.fromBitmap(owned, 0) }
            val task = OcrTiming.measure("submit") { client.process(input) }
            handoffToTask = true
            // Recycle the isolated bitmap only after ML Kit finishes, preventing native UAF on cancel/timeout.
            task.addOnCompleteListener(Executor { it.run() }) { done ->
                OcrTiming.mark("callback", when {
                    done.isCanceled -> "cancelled"
                    done.isSuccessful -> "ok"
                    else -> "error"
                })
                owned.recycle()
            }
            val result = OcrTiming.measure("await") {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAGE_TIMEOUT_MS)
                var textResult: com.google.mlkit.vision.text.Text? = null
                while (textResult == null) {
                    control.check()
                    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remainingMs <= 0L) throw TimeoutException("OCR page timed out after ${PAGE_TIMEOUT_MS}ms")
                    try {
                        textResult = Tasks.await(task, minOf(POLL_INTERVAL_MS, remainingMs), TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        // Continue polling so user cancellation is observed promptly.
                    }
                }
                textResult
            }
            control.check()
            return result.text
        } catch (timeout: TimeoutException) {
            throw IllegalArgumentException("这页文字识别超时，请提高图片清晰度或拆分后重试。", timeout)
        } finally {
            if (!handoffToTask) owned.recycle()
        }
    }
}
