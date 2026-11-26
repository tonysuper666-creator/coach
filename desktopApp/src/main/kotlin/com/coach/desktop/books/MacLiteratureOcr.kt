package com.coach.desktop.books

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal fun literatureOcr(configured: BookOcr): BookOcr =
    if (configured === BundledBookOcr && System.getProperty("os.name").startsWith("Mac")) MacLiteratureOcr else configured

/** Vision sees one rendered page region at a time. osascript ships with macOS. */
private object MacLiteratureOcr : BookOcr {
    private const val SCRIPT = """
ObjC.import('Vision')
ObjC.import('Foundation')
function run(args) {
    const url = $.NSURL.fileURLWithPath(args[0])
    const handler = $.VNImageRequestHandler.alloc.initWithURLOptions(url, $({}))
    const request = $.VNRecognizeTextRequest.alloc.init
    request.recognitionLevel = 0
    request.usesLanguageCorrection = false
    const failure = Ref()
    if (!handler.performRequestsError([request], failure)) throw Error('Vision OCR failed')
    const lines = []
    const observations = request.results
    for (let i = 0; i < observations.count; i++) {
        const best = observations.objectAtIndex(i).topCandidates(1)
        if (best.count) lines.push(ObjC.unwrap(best.objectAtIndex(0).string))
    }
    return lines.join('\n')
}
"""
    override fun recognize(image: BookBitmap, control: BookImportControl): String {
        control.check()
        val directory = Files.createTempDirectory("coach-guide-vision-")
        val input = directory.resolve("page.png")
        val output = directory.resolve("text")
        try {
            check(ImageIO.write(image, "png", input.toFile()))
            val process = ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", SCRIPT, input.toString())
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "本机版面识别失败，请检查扫描页。" }
                control.check()
                return Files.readString(output)
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(input)
            Files.deleteIfExists(directory)
        }
    }
}
