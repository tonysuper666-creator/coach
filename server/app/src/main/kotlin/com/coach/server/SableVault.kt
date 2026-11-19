package com.coach.server

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The service's owner-only key lives outside the ledger, its backups and release directories. */
internal class SableVault(private val directory: Path) {
    private val random = SecureRandom()
    private val key by lazy {
        val path = directory.resolve("grant.key")
        if (!Files.exists(path, NOFOLLOW_LINKS)) privateFiles.writeNew(path, ByteArray(32).also(random::nextBytes))
        privateFiles.secureFile(path)
        SecretKeySpec(Files.readAllBytes(path).also { check(it.size == 32) }, "AES")
    }
    fun seal(device: String, friend: String, token: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, device, friend, nonce)
        return nonce + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    }
    fun open(device: String, friend: String, sealed: ByteArray): String {
        require(sealed.size >= 28)
        return cipher(Cipher.DECRYPT_MODE, device, friend, sealed.copyOf(12))
            .doFinal(sealed, 12, sealed.size - 12).toString(Charsets.UTF_8)
    }
    private fun cipher(mode: Int, device: String, friend: String, nonce: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD("$device:$friend".toByteArray(Charsets.UTF_8))
        }
}
