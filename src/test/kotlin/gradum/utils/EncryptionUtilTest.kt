/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EncryptionUtilTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.utils

import java.lang.reflect.Field
import java.util.*
import kotlin.test.*

class EncryptionUtilTest {

  /** Clears the volatile key caches so each test starts from a fresh KDF run. */
  @BeforeTest
  fun resetCaches() {
    clearStaticField(name = "cachedEncryptionKey")
    clearStaticField(name = "cachedAuthenticationKey")
  }

  @AfterTest
  fun tearDownCaches() {
    clearStaticField(name = "cachedEncryptionKey")
    clearStaticField(name = "cachedAuthenticationKey")
  }

  private fun clearStaticField(name: String) {
    val field: Field = EncryptionUtilTest::class.java.classLoader
      .loadClass("gradum.utils.EncryptionUtilKt")
      .getDeclaredField(name)
    field.isAccessible = true
    field.set(null, null)
  }


  @Test
  fun `roundtrip preserves plaintext`() {
    val plaintext = "Hello, Gradum!"
    val ciphertext: String = encryptMessageContent(plaintext)
    val decrypted: String = decryptMessageContent(encodedCiphertext = ciphertext)
    assertEquals(
      plaintext,
      decrypted
    )
  }

  @Test
  fun `empty string roundtrips to empty string via encrypt-then-decrypt`() {
    val ciphertext: String = encryptMessageContent(plaintext = "")
    assertEquals(
      "",
      decryptMessageContent(encodedCiphertext = ciphertext)
    )
  }

  @Test
  fun `decryptMessageContent short-circuits empty input`() {
    assertEquals(
      "",
      decryptMessageContent("")
    )
  }

  @Test
  fun `unicode plaintext roundtrips byte-perfectly`() {
    val plaintext = "你好，Gradum！こんにちは 🌍 — émoji"
    val ciphertext: String = encryptMessageContent(plaintext)
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = ciphertext)
    )
  }

  @Test
  fun `multiblock CTR roundtrips for input larger than one block`() {
    val plaintext = "a".repeat(n = 33) + "尾巴"
    val ciphertext: String = encryptMessageContent(plaintext)
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = ciphertext)
    )
  }

  @Test
  fun `exact block boundary roundtrips`() {
    // 32 bytes = one block exactly (no partial second block).
    val plaintext = "a".repeat(n = 32)
    val ciphertext: String = encryptMessageContent(plaintext)
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = ciphertext)
    )
  }

  @Test
  fun `large plaintext roundtrips across many CTR blocks`() {
    val plaintext = "x".repeat(n = 10_000)
    val ciphertext: String = encryptMessageContent(plaintext)
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = ciphertext)
    )
  }

  @Test
  fun `two encryptions of the same plaintext produce different ciphertexts`() {
    val plaintext = "identical input"
    val a: String = encryptMessageContent(plaintext)
    val b: String = encryptMessageContent(plaintext)
    assertNotEquals(illegal = a, actual = b, message = "nonce must be fresh on every encrypt")
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = a)
    )
    assertEquals(
      plaintext,
      decryptMessageContent(encodedCiphertext = b)
    )
  }

  @Test
  fun `flipping a single ciphertext bit invalidates the HMAC tag`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
    val tamperIndex: Int = 1 + 16 + 5

    tokenBytes[tamperIndex] = (tokenBytes[tamperIndex].toInt() xor 0x01).toByte()
    val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
    assertFailsWith<SecurityException>(message = "HMAC verification must reject tampered ciphertext") {
      decryptMessageContent(encodedCiphertext = tampered)
    }
  }

  @Test
  fun `flipping a single nonce bit invalidates the HMAC tag`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)

    tokenBytes[1] = (tokenBytes[1].toInt() xor 0x01).toByte()
    val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
    assertFailsWith<SecurityException> {
      decryptMessageContent(encodedCiphertext = tampered)
    }
  }

  @Test
  fun `flipping a single HMAC tag bit causes verification failure`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)

    tokenBytes[tokenBytes.size - 1] = (tokenBytes[tokenBytes.size - 1].toInt() xor 0x80).toByte()
    val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
    assertFailsWith<SecurityException> {
      decryptMessageContent(encodedCiphertext = tampered)
    }
  }

  @Test
  fun `truncating the ciphertext body is rejected`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
    val truncatedBytes: ByteArray = tokenBytes.copyOf(newSize = tokenBytes.size - 1)
    val truncated: String = Base64.getEncoder().encodeToString(truncatedBytes)
    assertFailsWith<SecurityException> {
      decryptMessageContent(encodedCiphertext = truncated)
    }
  }

  @Test
  fun `appending garbage to the ciphertext is rejected`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
    val extended: ByteArray = tokenBytes + 0x42
    val tampered: String = Base64.getEncoder().encodeToString(extended)
    assertFailsWith<SecurityException> {
      decryptMessageContent(encodedCiphertext = tampered)
    }
  }

  @Test
  fun `swapping the version byte is rejected`() {
    val plaintext = "a tamper-prone message"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
    tokenBytes[0] = (tokenBytes[0].toInt() xor 0xFF).toByte()
    val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)

    val error: Throwable = assertFails {
      decryptMessageContent(encodedCiphertext = tampered)
    }

    assertTrue(
      (error.message ?: "").contains(other = "version byte"),
      "version-byte mismatch should produce a clear error; got: ${error.message}"
    )
  }

  @Test
  fun `token shorter than minimum is rejected with clear error`() {
    val tooShort = ByteArray(size = 10)
    val token: String = Base64.getEncoder().encodeToString(tooShort)
    val error: Throwable = assertFails {
      decryptMessageContent(encodedCiphertext = token)
    }
    assertTrue(
      (error.message ?: "").contains(other = "Token too short"),
      "short-token error must be specific; got: ${error.message}"
    )
  }

  @Test
  fun `non-base64 ciphertext is rejected`() {
    // The function should not crash on garbage input — it should
    // surface a clear error.
    val error: Throwable = assertFails {
      decryptMessageContent(encodedCiphertext = "not!valid!base64!@#$")
    }
    assertTrue(
      error is IllegalArgumentException || error is SecurityException,
      "expected IllegalArgumentException or SecurityException, got ${error::class.simpleName}"
    )
  }

  @Test
  fun `decryption fails when the key source changes between encrypt and decrypt`() {
    val plaintext = "secret context payload"
    val ciphertext: String = encryptMessageContent(plaintext)

    // Inject a different 32-byte key into both caches.
    injectDifferentKey(fieldName = "cachedEncryptionKey")
    injectDifferentKey(fieldName = "cachedAuthenticationKey")

    assertFailsWith<SecurityException>(message = "rotated key must reject old ciphertext") {
      decryptMessageContent(encodedCiphertext = ciphertext)
    }
  }

  private fun injectDifferentKey(fieldName: String) {
    val field: Field = EncryptionUtilTest::class.java.classLoader
      .loadClass("gradum.utils.EncryptionUtilKt")
      .getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(null, ByteArray(size = 32) { 0xAA.toByte() })
  }

  @Test
  fun `encryption and authentication keys are different (domain separation)`() {
    encryptMessageContent(plaintext = "trigger KDF + cache")

    val encryptionKeyField: Field = EncryptionUtilTest::class.java.classLoader
      .loadClass("gradum.utils.EncryptionUtilKt")
      .getDeclaredField("cachedEncryptionKey")
    val authenticationKeyField: Field = EncryptionUtilTest::class.java.classLoader
      .loadClass("gradum.utils.EncryptionUtilKt")
      .getDeclaredField("cachedAuthenticationKey")
    encryptionKeyField.isAccessible = true
    authenticationKeyField.isAccessible = true
    val encryptionKey: ByteArray = encryptionKeyField.get(null) as ByteArray
    val authenticationKey: ByteArray = authenticationKeyField.get(null) as ByteArray

    assertNotNull(encryptionKey)
    assertNotNull(authenticationKey)
    assertEquals(
      32,
      encryptionKey.size,
      "encryption key must be 32 bytes"
    )
    assertEquals(
      32,
      authenticationKey.size,
      "authentication key must be 32 bytes"
    )
    assertFalse(
      encryptionKey.contentEquals(authenticationKey),
      "encrypt / authenticate keys must differ (KDF domain separation)"
    )
  }

  @Test
  fun `ciphertext is base64-encoded`() {
    val ciphertext: String = encryptMessageContent(plaintext = "anything")
    val decoded: ByteArray = Base64.getDecoder().decode(ciphertext)
    assertTrue(decoded.isNotEmpty())
  }

  @Test
  fun `decoded token layout is version+nonce+ciphertext+hmac`() {
    val plaintext = "format invariant check"
    val ciphertext: String = encryptMessageContent(plaintext)
    val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)

    val versionByte: Byte = tokenBytes[0]
    val nonceLength = 16
    val hmacLength = 32
    val ciphertextLength: Int = plaintext.toByteArray(Charsets.UTF_8).size // CTR preserves length
    assertEquals(
      1 + nonceLength + ciphertextLength + hmacLength,
      tokenBytes.size,
      "token size must match version+nonce+ct+hmac"
    )

    assertEquals(
      0x81.toByte(),
      versionByte,
      "version byte must be 0x81"
    )
  }
}
