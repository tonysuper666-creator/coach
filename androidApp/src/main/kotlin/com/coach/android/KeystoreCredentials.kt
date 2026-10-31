package com.coach.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.coach.client.PairedCredentials
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES key is non-exportable in Android Keystore; preferences contain only GCM ciphertext. */
class KeystoreCredentials(context: Context, private val serverUrl: String, storageName: String = "paired-device") {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val alias = "com.coach.$storageName"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(credentials: PairedCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(serverUrl.toByteArray()) }
        val plain = JSONObject().put("deviceId", credentials.deviceId).put("token", credentials.token).toString().toByteArray()
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)).toString()
        check(preferences.edit().putString("credentials", envelope).commit()) { "Could not save pairing" }
    }
    fun load(): PairedCredentials? {
        val envelope = preferences.getString("credentials", null) ?: return null
        return try {
            val value = JSONObject(envelope)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(value.getString("iv"), Base64.NO_WRAP)))
                updateAAD(serverUrl.toByteArray())
            }
            val plain = JSONObject(String(cipher.doFinal(Base64.decode(value.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
            PairedCredentials(plain.getString("deviceId"), plain.getString("token"))
        } catch (_: Exception) {
            // Reinstall/key invalidation/server change requires pairing again, never plaintext fallback.
            clear()
            null
        }
    }
    fun clear() { check(preferences.edit().clear().commit()) }
}
