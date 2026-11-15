package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Independent generator for the two assets [WindowsBookRuntime.installed] discovers: the application trust
 * anchor (a classpath resource) and the signed `windows-book-runtime` directory (under the application
 * resources directory). It never touches a release key, real auth or any packaging output; callers hand in
 * their own temporary directories and a freshly generated test key.
 */
internal object WindowsBookRuntimeResourceTool {
    const val TRUST_RESOURCE = "windows-book-runtime-trust.der"
    const val RUNTIME_DIRECTORY = "windows-book-runtime"
    val components = listOf("codex.exe", "coach-iso.exe", "coach-auth.exe")

    fun newTestKey(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Writes `<resources>/windows-book-runtime/{codex.exe,coach-iso.exe,coach-auth.exe,manifest.json,manifest.sig}`. */
    fun writeRuntime(resources: Path, sources: Map<String, Path>, signer: KeyPair): Path {
        require(sources.keys == components.toSet())
        val root = Files.createDirectories(resources.resolve(RUNTIME_DIRECTORY))
        for ((name, source) in sources) Files.copy(source, root.resolve(name))
        val identity = OwnedBookConsentIdentity()
        val manifest = buildJsonObject {
            put("contract", "windows-owned-book-runtime-v1")
            put("provider", identity.provider); put("model", identity.model); put("notice", identity.notice)
            putJsonObject("sha256") { components.forEach { put(it, WindowsBookRuntime.sha256(root.resolve(it))) } }
        }.toString().toByteArray()
        Files.write(root.resolve("manifest.json"), manifest)
        Files.write(root.resolve("manifest.sig"), sign(manifest, signer))
        return root
    }

    fun sign(bytes: ByteArray, signer: KeyPair): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(signer.private); update(bytes); sign() }

    /** The trust resource is the public half only, X.509 DER, exactly what `installed()` decodes. */
    fun writeTrust(classpathDirectory: Path, publicKey: java.security.PublicKey): Path =
        Files.write(classpathDirectory.resolve(TRUST_RESOURCE), publicKey.encoded)

    /** A test-output resource directory that `WindowsBookRuntime::class.java` can see. */
    fun testClasspathResourceDirectory(): Path = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        .map { Path.of(it) }.first { Files.isDirectory(it) && it.endsWith(Path.of("resources", "test")) }
}
