package com.coach.desktop.books

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

internal const val bookOcrTargetDpi = 300f
internal const val bookOcrMaxSide = 4000f

internal object BookScanImages {
    const val MAX_PIXELS = 16_000_000L
    const val MAX_SIDE = 8000
    const val MAX_OCR_PAGES = 2000

    fun checkSize(width: Int, height: Int) {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS) {
            "这页图片太大，请缩到 1600 万像素以内，且长宽均不超过 8000 像素后再导入。"
        }
    }

    /** Read metadata before allocating the decoded raster (including damaged/decompression bombs). */
    fun read(path: Path): BufferedImage = ImageIO.createImageInputStream(path.toFile()).use { input ->
        requireNotNull(input) { "这张图片打不开，请重新保存为 PNG 或 JPG 后导入。" }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "这张图片打不开，请重新保存为 PNG 或 JPG 后导入。" }
        val reader = readers.next()
        try {
            reader.input = input
            checkSize(reader.getWidth(0), reader.getHeight(0))
            requireNotNull(reader.read(0)) { "这张图片已损坏，请重新保存后导入。" }
        } finally { reader.dispose() }
    }
}

/** Minimal C API bridge: bundled Windows engine + fast Simplified Chinese/English models.
 * No optional Windows language pack, system executable, network or Python at runtime.
 */
internal object BundledBookOcr : BookOcr {
    private interface CancelCallback : Callback { fun invoke(context: Pointer?, words: Int): Byte }
    private interface Api : Library {
        fun TessBaseAPICreate(): Pointer
        fun TessBaseAPIDelete(api: Pointer)
        fun TessBaseAPIInit2(api: Pointer, data: String, language: String, mode: Int): Int
        fun TessBaseAPISetPageSegMode(api: Pointer, mode: Int)
        fun TessBaseAPISetImage(api: Pointer, bytes: ByteArray, width: Int, height: Int, depth: Int, stride: Int)
        fun TessBaseAPISetSourceResolution(api: Pointer, ppi: Int)
        fun TessBaseAPIRecognize(api: Pointer, monitor: Pointer): Int
        fun TessBaseAPIGetUTF8Text(api: Pointer): Pointer?
        fun TessDeleteText(text: Pointer)
        fun TessMonitorCreate(): Pointer
        fun TessMonitorDelete(monitor: Pointer)
        fun TessMonitorSetCancelFunc(monitor: Pointer, callback: CancelCallback)
        fun TessMonitorSetDeadlineMSecs(monitor: Pointer, deadline: Int)
    }
    private enum class Engine(val archive: String, val lept: String, val tess: String) {
        X64("windows-x64", "libleptonica1870.dll", "libtesseract552.dll"),
        ARM64("windows-arm64", "libleptonica-1.87.0.dll", "libtesseract55.dll"),
    }
    private data class NativeRuntime(val api: Api, val tessdata: String, val languages: String)
    private val engine by lazy {
        val arch = System.getProperty("os.arch").lowercase()
        require(System.getProperty("os.name").startsWith("Windows") &&
            arch in setOf("amd64", "x86_64", "aarch64", "arm64")) {
            "扫描文字识别目前支持 Windows 64 位，请在 Windows 上导入这份扫描材料。"
        }
        if (arch == "aarch64" || arch == "arm64") Engine.ARM64 else Engine.X64
    }
    private val runtime by lazy { unpack() }
    // Retain the dependency handle for the entire process lifetime.
    private val leptonica by lazy { NativeLibrary.getInstance(runtime.resolve(engine.lept).toString()) }
    private val nativeRuntime: NativeRuntime by lazy {
        val os = System.getProperty("os.name")
        if (os.startsWith("Windows")) {
            // Set before loading the engine/OpenMP runtime. Process-local; never changes Windows settings.
            if (engine == Engine.X64) {
                NativeLibrary.getInstance("kernel32").getFunction("SetEnvironmentVariableW")
                    .invokeInt(arrayOf(WString("OMP_THREAD_LIMIT"), WString("2")))
            }
            leptonica
            val loaded = Native.load(runtime.resolve(engine.tess).toString(), Api::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            NativeRuntime(loaded, runtime.resolve("tessdata").toString(), "chi_sim+eng")
        } else if (os.startsWith("Mac")) {
            val lib = listOf("/opt/homebrew/lib/libtesseract.dylib", "/usr/local/lib/libtesseract.dylib")
                .firstOrNull { Files.isRegularFile(Path.of(it)) }
                ?: throw IllegalArgumentException("当前 Mac 未安装 Tesseract 共享库，无法执行本地 OCR。")
            val tessdataDir = listOfNotNull(
                System.getenv("TESSDATA_PREFIX")?.takeIf { it.isNotBlank() },
                "/opt/homebrew/share/tessdata",
                "/usr/local/share/tessdata",
            ).firstOrNull { Files.isDirectory(Path.of(it)) }
                ?: throw IllegalArgumentException("当前 Mac 未找到 tessdata 目录，无法执行本地 OCR。")
            val hasChi = Files.isRegularFile(Path.of(tessdataDir, "chi_sim.traineddata"))
            val loaded = Native.load(lib, Api::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            NativeRuntime(loaded, tessdataDir, if (hasChi) "chi_sim+eng" else "eng")
        } else {
            throw IllegalArgumentException("扫描文字识别目前支持 Windows 64 位，请在 Windows 上导入这份扫描材料。")
        }
    }

    override fun recognize(image: BufferedImage, control: BookImportControl): String =
        recognize(image, control, englishOnly = false, singleBlock = false)

    override fun recognize(image: BufferedImage, control: BookImportControl, englishOnly: Boolean, singleBlock: Boolean): String {
        control.check()
        BookScanImages.checkSize(image.width, image.height)
        // Composite transparent PNGs onto white, then send a compact grayscale raster.
        val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        gray.createGraphics().let { g ->
            try { g.color = Color.WHITE; g.fillRect(0, 0, gray.width, gray.height); g.drawImage(image, 0, 0, null) }
            finally { g.dispose() }
        }
        try {
            val loaded = try { nativeRuntime } catch (e: LinkageError) {
                throw IllegalArgumentException("文字识别组件无法启动，请重新安装 LearningCoach 后重试。", e)
            }
            val bridge = loaded.api
            val handle = bridge.TessBaseAPICreate()
            val lang = if (englishOnly) "eng" else loaded.languages
            try {
                require(bridge.TessBaseAPIInit2(handle, loaded.tessdata, lang, 1) == 0) {
                    "文字识别模型无法读取，请重新安装 LearningCoach 后重试。"
                }
                control.check()
                bridge.TessBaseAPISetPageSegMode(handle, if (singleBlock) 6 else 3)
                val bytes = (gray.raster.dataBuffer as java.awt.image.DataBufferByte).data
                bridge.TessBaseAPISetImage(handle, bytes, gray.width, gray.height, 1, gray.width)
                bridge.TessBaseAPISetSourceResolution(handle, 300)
                val monitor = bridge.TessMonitorCreate()
                // C bool is one byte; JNA Boolean would use the four-byte Windows BOOL ABI.
                val callback = object : CancelCallback {
                    override fun invoke(context: Pointer?, words: Int): Byte = if (control.isCancelled()) 1 else 0
                }
                try {
                    bridge.TessMonitorSetCancelFunc(monitor, callback)
                    bridge.TessMonitorSetDeadlineMSecs(monitor, 60_000)
                    val result = bridge.TessBaseAPIRecognize(handle, monitor)
                    java.lang.ref.Reference.reachabilityFence(callback)
                    control.check()
                    require(result == 0) { "这页识别未能完成或识别超时（60 秒），请提高图片清晰度或拆分后重试。" }
                    val text = bridge.TessBaseAPIGetUTF8Text(handle) ?: throw IllegalArgumentException("这页文字识别失败，请重新保存图片后重试。")
                    try { return TextbookExtraction.clean(text.getString(0, "UTF-8")) }
                    finally { bridge.TessDeleteText(text) }
                } finally { bridge.TessMonitorDelete(monitor) }
            } finally { bridge.TessBaseAPIDelete(handle) }
        } finally { gray.flush() }
    }

    /** Content-checked shared cache, locked across processes; models never enter learner data. */
    private fun unpack(): Path {
        val manifest = checkNotNull(javaClass.getResourceAsStream("/ocr/sha256.txt")).use { it.readBytes() }
        val version = MessageDigest.getInstance("SHA-256").digest(manifest).take(12).joinToString("") { "%02x".format(it) }
        val root = Path.of(System.getProperty("java.io.tmpdir"), "learningcoach-ocr-${engine.archive}-$version")
        Files.createDirectories(root)
        FileChannel.open(root.resolve("extract.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            val hashes = manifest.decodeToString().lineSequence().filter { it.isNotBlank() }
                .associate { it.substringAfter("  ") to it.substringBefore("  ") }
            fun valid(name: String): Boolean {
                val file = root.resolve(name)
                return Files.isRegularFile(file) && MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) } == hashes[name]
            }
            if (!hashes.keys.all(::valid)) {
                ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/ocr/${engine.archive}.zip"))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(entry.name in hashes)
                        val target = root.resolve(entry.name)
                        Files.createDirectories(target.parent)
                        if (!valid(entry.name)) Files.newOutputStream(target).use { zip.copyTo(it) }
                    }
                }
                require(hashes.keys.all(::valid)) { "文字识别组件不完整，请重新安装 LearningCoach。" }
            }
        } }
        return root
    }
}
