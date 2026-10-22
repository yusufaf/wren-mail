package dev.yusufaf.wren.account

import java.security.GeneralSecurityException

/** Encrypts the account password at rest. Blocking; call off the main thread. */
interface PasswordCipher {
    fun encrypt(plaintext: String): EncryptedPassword

    /**
     * @throws PasswordUnrecoverableException if the password can never be decrypted (the key
     *   is gone, or the data is damaged or doesn't authenticate). Any other exception may be
     *   transient.
     */
    fun decrypt(encrypted: EncryptedPassword): String
}

/** The stored password can never be decrypted: the key is gone or the data is damaged. */
class PasswordUnrecoverableException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

// Not a data class: arrays compare by identity, so generated equals would mislead.
class EncryptedPassword(val ciphertext: ByteArray, val iv: ByteArray)
