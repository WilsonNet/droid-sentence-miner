package io.github.wilsonnet.droidsentenceminer.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys with an AES key that lives in the Android Keystore, so the preferences file
 * only holds ciphertext (a backup or a copied data folder can't be read without this device).
 */
object AiKeyStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "dsm_ai_api_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SEPARATOR = ":"

    /** [plain] as `iv:ciphertext` (Base64); empty stays empty. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return iv + SEPARATOR + data
    }

    /** The key [stored] by [encrypt]; empty when there is none or it can't be read (Keystore reset). */
    fun decrypt(stored: String?): String {
        val parts = stored.orEmpty().split(SEPARATOR)
        val plain =
            if (parts.size != 2) {
                ""
            } else {
                runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                    String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
                }.getOrDefault("")
            }
        return plain
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        return existing ?: generate()
    }

    private fun generate(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }
}
