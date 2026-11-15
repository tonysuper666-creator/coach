package com.coach.desktop.books

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A fixed-purpose local auth transaction helper, never a Codex/shell launch or login discovery. */
internal class WindowsAuthAtomicCommit(private val executable: Path) : AuthAtomicReplace {
    override fun replace(staged: Path, source: Path, expectedHash: String, expectedModified: Long, refreshedHash: String): AuthCommitStatus {
        val process = ProcessBuilder(executable.toAbsolutePath().toString(), source.toString(), staged.toString(),
            expectedHash, expectedModified.toString(), refreshedHash).apply {
            environment().clear()
            System.getenv("SystemRoot")?.let { environment()["SystemRoot"] = it }
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        var interrupted = false
        try {
            process.outputStream.close()
            if (!process.waitFor(15, TimeUnit.SECONDS)) return AuthCommitStatus.OUTCOME_UNKNOWN
            return when (process.exitValue()) {
                0 -> AuthCommitStatus.COMMITTED
                3 -> AuthCommitStatus.SOURCE_CHANGED
                4 -> AuthCommitStatus.ATOMIC_REPLACE_FAILED
                else -> AuthCommitStatus.OUTCOME_UNKNOWN
            }
        } catch (_: InterruptedException) {
            interrupted = true
            return AuthCommitStatus.OUTCOME_UNKNOWN
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                try { process.waitFor(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
