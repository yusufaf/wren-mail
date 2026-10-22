package dev.yusufaf.wren.account

/** Encrypts the account password at rest. Blocking; call off the main thread. */
interface PasswordCipher {
    fun encrypt(plaintext: String): EncryptedPassword

    /** @throws java.security.GeneralSecurityException if the key is gone or the data doesn't authenticate. */
    fun decrypt(encrypted: EncryptedPassword): String
}

// Not a data class: arrays compare by identity, so generated equals would mislead.
class EncryptedPassword(val ciphertext: ByteArray, val iv: ByteArray)
