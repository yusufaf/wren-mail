package dev.yusufaf.wren.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [PasswordCipher] backed by an AES-256-GCM key in the Android Keystore. The
 * key needs no user authentication, so [dev.yusufaf.wren.sync.SyncWorker] can
 * decrypt in the background.
 */
class KeystorePasswordCipher(private val alias: String = "wren-account-password") : PasswordCipher {

    override fun encrypt(plaintext: String): EncryptedPassword {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore requires randomized encryption, so it picks the IV.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedPassword(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), cipher.iv)
    }

    override fun decrypt(encrypted: EncryptedPassword): String {
        val key = loadKey() ?: throw InvalidKeyException("No Keystore key under $alias")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.iv))
        return String(cipher.doFinal(encrypted.ciphertext), Charsets.UTF_8)
    }

    private fun loadKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) { loadKey() ?: generateKey() }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        // Process-wide: two first-time encrypts (UI and SyncWorker migrating at once)
        // must not each generate a key and overwrite the other's.
        val keyLock = Any()
    }
}
