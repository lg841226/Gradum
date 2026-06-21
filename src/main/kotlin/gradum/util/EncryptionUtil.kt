/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EncryptionUtil.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.util

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextEncryption")

private val VERSION_BYTE: Byte = 0x81.toByte()
private const val NONCE_SIZE_BYTES: Int = 16
private const val HMAC_SIZE_BYTES: Int = 32
private const val BLOCK_SIZE_BYTES: Int = 32

private val builtinKeySource: ByteArray = "gradum-context-v1-encryption-key-2026".toByteArray(Charsets.UTF_8)
private val secureRandom: SecureRandom = SecureRandom()

@Volatile
private var cachedEncryptionKey: ByteArray? = null

@Volatile
private var cachedAuthenticationKey: ByteArray? = null

private fun deriveKey(keySource: ByteArray, purpose: String): ByteArray {
    val hmac: Mac = Mac.getInstance("HmacSHA256")
    hmac.init(SecretKeySpec("gradum-key-derivation".toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val pseudorandomKey: ByteArray = hmac.doFinal(keySource)

    hmac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
    return hmac.doFinal("$purpose\u0001".toByteArray(Charsets.UTF_8))
}

private fun getEncryptionKey(): ByteArray {
    cachedEncryptionKey?.let { return it }
    val keySource: ByteArray = getKeySource()
    val key: ByteArray = deriveKey(keySource, "encrypt")
    cachedEncryptionKey = key
    return key
}

private fun getAuthenticationKey(): ByteArray {
    cachedAuthenticationKey?.let { return it }
    val keySource: ByteArray = getKeySource()
    val key: ByteArray = deriveKey(keySource, "authenticate")
    cachedAuthenticationKey = key
    return key
}

private fun hmacCtrEncrypt(plaintext: ByteArray, encryptionKey: ByteArray, nonce: ByteArray): ByteArray {
    val ciphertext: ByteArray = ByteArray(plaintext.size)
    val hmac: Mac = Mac.getInstance("HmacSHA256")
    hmac.init(SecretKeySpec(encryptionKey, "HmacSHA256"))

    for (offset in plaintext.indices step BLOCK_SIZE_BYTES) {
        val counter: Int = offset / BLOCK_SIZE_BYTES
        val counterBytes: ByteArray = byteArrayOf(
            (counter shr 24).toByte(),
            (counter shr 16).toByte(),
            (counter shr 8).toByte(),
            counter.toByte(),
        )

        hmac.update(nonce)
        val streamBlock: ByteArray = hmac.doFinal(counterBytes)

        val blockLength: Int = minOf(BLOCK_SIZE_BYTES, plaintext.size - offset)
        for (byteIndex in 0 until blockLength) {
            ciphertext[offset + byteIndex] = (plaintext[offset + byteIndex].toInt() xor streamBlock[byteIndex].toInt()).toByte()
        }
    }

    return ciphertext
}

private fun computeHmac(hmacKey: ByteArray, data: ByteArray): ByteArray {
    val hmac: Mac = Mac.getInstance("HmacSHA256")
    hmac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
    return hmac.doFinal(data)
}

private fun getKeySource(): ByteArray {
    val environmentKey: String? = System.getenv("GRADUM_CONTEXT_KEY")
    return environmentKey?.toByteArray(Charsets.UTF_8) ?: builtinKeySource
}

fun encryptMessageContent(plaintext: String): String {
    val encryptionKey: ByteArray = getEncryptionKey()
    val authenticationKey: ByteArray = getAuthenticationKey()

    val nonce: ByteArray = ByteArray(NONCE_SIZE_BYTES).also { array ->
        secureRandom.nextBytes(array)
    }

    val plaintextBytes: ByteArray = plaintext.toByteArray(Charsets.UTF_8)
    val ciphertext: ByteArray = hmacCtrEncrypt(plaintextBytes, encryptionKey, nonce)

    val tokenBody: ByteArray = byteArrayOf(VERSION_BYTE) + nonce + ciphertext
    val authenticationTag: ByteArray = computeHmac(authenticationKey, tokenBody)

    val fullToken: ByteArray = tokenBody + authenticationTag

    return java.util.Base64.getEncoder().encodeToString(fullToken)
}

fun decryptMessageContent(encodedCiphertext: String): String {
    if (encodedCiphertext.isEmpty()) return ""

    val rawBytes: ByteArray = java.util.Base64.getDecoder().decode(encodedCiphertext)

    val minimumLength: Int = 1 + NONCE_SIZE_BYTES + HMAC_SIZE_BYTES
    if (rawBytes.size < minimumLength) {
        throw IllegalArgumentException("Token too short: ${rawBytes.size} bytes (minimum $minimumLength)")
    }

    if (rawBytes[0] != VERSION_BYTE) {
        throw IllegalArgumentException("Invalid version byte: ${rawBytes[0]} (expected $VERSION_BYTE)")
    }

    val version: Byte = rawBytes[0]
    val nonce: ByteArray = rawBytes.copyOfRange(1, 1 + NONCE_SIZE_BYTES)
    val authenticationTag: ByteArray = rawBytes.copyOfRange(rawBytes.size - HMAC_SIZE_BYTES, rawBytes.size)
    val ciphertextBytes: ByteArray = rawBytes.copyOfRange(1 + NONCE_SIZE_BYTES, rawBytes.size - HMAC_SIZE_BYTES)

    val encryptionKey: ByteArray = getEncryptionKey()
    val authenticationKey: ByteArray = getAuthenticationKey()

    val tokenBody: ByteArray = byteArrayOf(version) + nonce + ciphertextBytes
    val expectedTag: ByteArray = computeHmac(authenticationKey, tokenBody)

    if (!MessageDigest.isEqual(authenticationTag, expectedTag)) {
        throw SecurityException("HMAC verification failed - content may be tampered")
    }

    val plaintextBytes: ByteArray = hmacCtrEncrypt(ciphertextBytes, encryptionKey, nonce)

    return plaintextBytes.toString(Charsets.UTF_8)
}
