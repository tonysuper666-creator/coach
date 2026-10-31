package com.coach.server

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Switch files on the next write after midnight; never rotate an open pipe. */
internal class ServiceLog(private val directory: Path, private val name: String) : OutputStream() {
    private var day: LocalDate? = null
    private var output: OutputStream? = null

    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val today = LocalDate.now()
        if (day != today) {
            output?.close()
            Files.createDirectories(directory)
            output = Files.newOutputStream(directory.resolve("$name-$today.log"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            day = today
            Files.list(directory).use { paths -> paths.forEach { path ->
                val file = path.fileName.toString()
                if (file.startsWith("$name-") && file.endsWith(".log")) {
                    val date = runCatching { LocalDate.parse(file.removePrefix("$name-").removeSuffix(".log")) }.getOrNull()
                    if (date != null && date < today.minusDays(13)) Files.deleteIfExists(path)
                }
            } }
        }
        output!!.write(bytes, offset, length)
        output!!.flush()
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    @Synchronized override fun close() { output?.close(); output = null; day = null }

    companion object {
        fun install(directory: Path) {
            val stream = PrintStream(ServiceLog(directory, "server"), true, Charsets.UTF_8)
            System.setOut(stream)
            System.setErr(stream)
        }
    }
}
