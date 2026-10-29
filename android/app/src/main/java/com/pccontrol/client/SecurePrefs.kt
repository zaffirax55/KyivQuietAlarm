package com.pccontrol.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The pairing secret never leaves Android's Keystore unencrypted on disk. */
object SecurePrefs {
    private const val KEY_ALIAS = "PCControlPairingKeyV1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun store(context: Context, ip: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val data = cipher.iv + ciphertext
        context.getSharedPreferences("saved", Context.MODE_PRIVATE).edit()
            .putString("ip", ip)
            .putString("secret", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }
    fun load(context: Context): Pair<String, String>? {
        val p = context.getSharedPreferences("saved", Context.MODE_PRIVATE)
        val ip = p.getString("ip", null) ?: return null
        val encoded = p.getString("secret", null) ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0,12)))
            ip to String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun clear(context: Context) = context.getSharedPreferences("saved", Context.MODE_PRIVATE).edit().clear().apply()
}
