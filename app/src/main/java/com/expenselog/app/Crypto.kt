package com.expenselog.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * #48: encrypts what the app keeps on the phone that matters (Microsoft sign-in tokens, saved bank
 * messages, the local copy of the sheet) with an AES key held in the phone's secure hardware
 * (Android Keystore). The key never leaves the phone and is not readable even with USB debugging.
 * Values written before encryption existed are still read, and get encrypted the next time they are saved.
 */
object Crypto {
    private const val ALIAS = "expenselog-data"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypted form of [plain]; if the keystore is unavailable the value is kept as it was rather than lost. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    /** Reads a value written by [seal] (or an older unencrypted one). Returns "" if it cannot be decrypted. */
    fun open(stored: String?): String {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return stored.orEmpty()
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (e: Exception) {
            ""
        }
    }
}
