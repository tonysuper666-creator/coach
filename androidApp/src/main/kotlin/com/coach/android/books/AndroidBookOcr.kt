package com.coach.desktop.books

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object BookScanImages {
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_SIDE = 8000
    const val MAX_OCR_PAGES = 200

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
    override fun recognize(image: BookBitmap, control: BookImportControl): String {
        control.check()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 45, TimeUnit.SECONDS)
            control.check()
            return result.text
        } finally { recognizer.close() }
    }
}
