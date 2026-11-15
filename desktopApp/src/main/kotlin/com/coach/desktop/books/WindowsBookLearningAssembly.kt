package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Only the application trust anchor can authorize an installed runtime. No environment bypass. */
internal class WindowsBookRuntime private constructor(
    private val root: Path,
    private val hashes: Map<String, String>,
    val identity: OwnedBookConsentIdentity,
) {
    companion object {
        private val binaries = setOf("codex.exe", "coach-iso.exe", "coach-auth.exe")

        /** Explicit trust is supplied by the packager; tests use their own ephemeral signing key. */
        fun verify(root: Path, manifest: ByteArray, signature: ByteArray, trust: PublicKey): WindowsBookRuntime? = runCatching {
            require(manifest.size <= 8192 && signature.size == 64)
            require(Signature.getInstance("Ed25519").run { initVerify(trust); update(manifest); verify(signature) })
            val json = Json.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject
            require(json.keys == setOf("contract", "provider", "model", "notice", "sha256"))
            require(json.getValue("contract").jsonPrimitive.content == "windows-owned-book-runtime-v1")
            val identity = OwnedBookConsentIdentity()
            require(json.getValue("provider").jsonPrimitive.content == identity.provider)
            require(json.getValue("model").jsonPrimitive.content == identity.model)
            require(json.getValue("notice").jsonPrimitive.content == identity.notice)
            val hashes = json.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content }
            require(hashes.keys == binaries && hashes.values.all { Regex("[a-f0-9]{64}").matches(it) })
            val resolved = root.toRealPath()
            WindowsBookRuntime(resolved, hashes, identity).also { it.checkFiles(resolved) }
        }.getOrNull()

        /** Absent release assets fail closed before even resolving the user's authentication path. */
        fun installed(): WindowsBookRuntime? = runCatching {
            val key = WindowsBookRuntime::class.java.getResourceAsStream("/windows-book-runtime-trust.der")?.use { it.readNBytes(1024) }
                ?: return null
            val resources = System.getProperty("compose.application.resources.dir") ?: return null
            val root = Path.of(resources).resolve("windows-book-runtime")
            val manifest = root.resolve("manifest.json")
            val signature = root.resolve("manifest.sig")
            require(Files.size(manifest) <= 8192 && Files.size(signature) == 64L)
            verify(root, Files.readAllBytes(manifest), Files.readAllBytes(signature),
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key)))
        }.getOrNull()

        internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun checkFiles(directory: Path) {
        val directoryAttributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(directoryAttributes.isDirectory && !directoryAttributes.isSymbolicLink && !directoryAttributes.isOther)
        for ((name, hash) in hashes) {
            val file = directory.resolve(name)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
            require(file.toRealPath().parent == directory.toRealPath())
            require(sha256(file) == hash)
        }
    }

    /** Check the copied bytes too: a replacement during copying cannot become an unsigned launch. */
    fun snapshot(): Path {
        checkFiles(root)
        val target = Files.createTempDirectory("coach-verified-runtime-")
        try {
            for (name in hashes.keys) Files.copy(root.resolve(name), target.resolve(name))
            checkFiles(target)
            return target
        } catch (error: Exception) { discard(target); throw error }
    }

    internal fun discard(directory: Path) {
        // This directory contains only the three parent-created runtime copies; never recurse.
        val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
        for (name in hashes.keys) Files.deleteIfExists(directory.resolve(name))
        Files.deleteIfExists(directory)
    }
}

internal data class WindowsBookRuntimeEvidence(
    val receipt: (List<String>, Int) -> Unit = { _, _ -> },
    val isolationReceipt: (WindowsBookIsolationReceipt) -> Unit = {},
    val observeRun: (WindowsBookRun) -> AutoCloseable = { AutoCloseable {} },
)

/** The normal host and its off-screen fixture use this same consent/provider/transport assembly. */
internal fun isolatedWindowsBookLearningSupport(
    library: TextbookLibrary,
    runtime: WindowsBookRuntime?,
    authSource: () -> Path = {
        System.getenv("CODEX_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?.resolve("auth.json") ?: Path.of(System.getProperty("user.home"), ".codex", "auth.json")
    },
    evidence: WindowsBookRuntimeEvidence = WindowsBookRuntimeEvidence(),
): BookLearningSupport {
    if (runtime == null) return object : BookLearningSupport {
        private val unavailable = BookLearningReply.Unavailable("这台电脑的受限讲解组件还没有就绪，可以继续读书和做题。")
        override suspend fun explain(request: BookExplanationRequest) = unavailable
        override suspend fun ask(request: BookPassageQuestion) = unavailable
    }
    val transport = OwnedBookCliTransport { input ->
        withContext(Dispatchers.IO) {
            val lease = coroutineContext[BookConsentLease] ?: throw LocalCodexFailure(LocalCodexProblem.FAILED)
            lease.checked { Unit }
            val snapshot = try { runtime.snapshot() } catch (_: Exception) { throw LocalCodexFailure(LocalCodexProblem.MISSING) }
            try {
                // Copying a runtime takes time. Recheck material identity at this final assembly seam,
                // outside the consent lock; the provider also checks the exact selection and reply.
                val book = library.book(lease.book)
                require(book.id == lease.book && book.format in setOf("pdf", "epub", "md", "txt"))
                require(WindowsBookRuntime.sha256(library.directory.resolve("${book.id}.${book.format}")) == lease.book)
                lease.checked { Unit }
                LocalCodexBookTransport(snapshot.resolve("codex.exe"), runtime.identity,
                    isolation = WindowsBookIsolation(snapshot.resolve("coach-iso.exe"), snapshot.resolve("coach-auth.exe"), authSource()),
                    receipt = evidence.receipt, isolationReceipt = evidence.isolationReceipt, observeRun = evidence.observeRun).complete(input)
            } finally { runtime.discard(snapshot) }
        }
    }
    return ConsentedBookLearningSupport(library, OwnedBookConsentStore(library), transport, runtime.identity)
}
