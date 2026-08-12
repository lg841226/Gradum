/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EncryptionUtilTest.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.utils

import java.lang.reflect.Field
import java.util.*
import kotlin.test.*

class EncryptionUtilTest {

    /** Clears the volatile key caches so each test starts from a fresh KDF run. */
    @BeforeTest
    fun resetCaches() {
        clearStaticField("cachedEncryptionKey")
        clearStaticField("cachedAuthenticationKey")
    }

    @AfterTest
    fun tearDownCaches() {
        clearStaticField("cachedEncryptionKey")
        clearStaticField("cachedAuthenticationKey")
    }

    private fun clearStaticField(name: String) {
        val field: Field = EncryptionUtilTest::class.java.classLoader
            .loadClass("gradum.utils.EncryptionUtilKt")
            .getDeclaredField(name)
        field.isAccessible = true
        field.set(null, null)
    }

    // ---- roundtrip ----

    @Test
    fun `roundtrip preserves plaintext`() {
        val plaintext = "Hello, Gradum!"
        val ciphertext: String = encryptMessageContent(plaintext)
        val decrypted: String = decryptMessageContent(ciphertext)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `empty string roundtrips to empty string via encrypt-then-decrypt`() {
        val ciphertext: String = encryptMessageContent("")
        // The empty plaintext still produces version-byte + nonce +
        // 32-byte auth tag, all packed into base64. Decryption must
        // yield the original empty string.
        assertEquals("", decryptMessageContent(ciphertext))
    }

    @Test
    fun `decryptMessageContent short-circuits empty input`() {
        // The decrypt function must return "" for "" without throwing,
        // even though the empty string is not a valid token.
        assertEquals("", decryptMessageContent(""))
    }

    @Test
    fun `unicode plaintext roundtrips byte-perfectly`() {
        val plaintext = "你好，Gradum！こんにちは 🌍 — émoji"
        val ciphertext: String = encryptMessageContent(plaintext)
        assertEquals(plaintext, decryptMessageContent(ciphertext))
    }

    @Test
    fun `multiblock CTR roundtrips for input larger than one block`() {
        // 32 bytes is exactly one CTR block. 33 bytes is the smallest
        // input that exercises the second-block branch in hmacCtrEncrypt.
        val plaintext = "a".repeat(33) + "尾巴"
        val ciphertext: String = encryptMessageContent(plaintext)
        assertEquals(plaintext, decryptMessageContent(ciphertext))
    }

    @Test
    fun `exact block boundary roundtrips`() {
        // 32 bytes = one block exactly (no partial second block).
        val plaintext = "a".repeat(32)
        val ciphertext: String = encryptMessageContent(plaintext)
        assertEquals(plaintext, decryptMessageContent(ciphertext))
    }

    @Test
    fun `large plaintext roundtrips across many CTR blocks`() {
        val plaintext = "x".repeat(10_000)
        val ciphertext: String = encryptMessageContent(plaintext)
        assertEquals(plaintext, decryptMessageContent(ciphertext))
    }

    // ---- nonce uniqueness ----

    @Test
    fun `two encryptions of the same plaintext produce different ciphertexts`() {
        // The nonce is 16 bytes from SecureRandom; the probability of
        // collision is 2^-128 — so two consecutive calls must
        // (probabilistically) differ in both nonce and ciphertext.
        val plaintext = "identical input"
        val a: String = encryptMessageContent(plaintext)
        val b: String = encryptMessageContent(plaintext)
        assertNotEquals(a, b, "nonce must be fresh on every encrypt")
        // But both must decrypt to the same plaintext.
        assertEquals(plaintext, decryptMessageContent(a))
        assertEquals(plaintext, decryptMessageContent(b))
    }

    // ---- tamper detection ----

    @Test
    fun `flipping a single ciphertext bit invalidates the HMAC tag`() {
        val plaintext = "a tamper-prone message"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        // Flip a bit in the ciphertext region (after version + nonce).
        val tamperIndex: Int = 1 + 16 + 5
        tokenBytes[tamperIndex] = (tokenBytes[tamperIndex].toInt() xor 0x01).toByte()
        val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
        assertFailsWith<SecurityException>("HMAC verification must reject tampered ciphertext") {
            decryptMessageContent(tampered)
        }
    }

    @Test
    fun `flipping a single nonce bit invalidates the HMAC tag`() {
        val plaintext = "a tamper-prone message"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        // Flip a bit in the nonce region (bytes 1..16, after version byte).
        tokenBytes[1] = (tokenBytes[1].toInt() xor 0x01).toByte()
        val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
        assertFailsWith<SecurityException> {
            decryptMessageContent(tampered)
        }
    }

    @Test
    fun `flipping a single HMAC tag bit causes verification failure`() {
        val plaintext = "a tamper-prone message"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        // Flip a bit at the very end (inside the 32-byte HMAC tag).
        tokenBytes[tokenBytes.size - 1] = (tokenBytes[tokenBytes.size - 1].toInt() xor 0x80).toByte()
        val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
        assertFailsWith<SecurityException> {
            decryptMessageContent(tampered)
        }
    }

    @Test
    fun `truncating the ciphertext body is rejected`() {
        val plaintext = "a tamper-prone message"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        val truncatedBytes: ByteArray = tokenBytes.copyOf(tokenBytes.size - 1)
        val truncated: String = Base64.getEncoder().encodeToString(truncatedBytes)
        assertFailsWith<SecurityException> {
            decryptMessageContent(truncated)
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
            decryptMessageContent(tampered)
        }
    }

    @Test
    fun `swapping the version byte is rejected`() {
        val plaintext = "a tamper-prone message"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        tokenBytes[0] = (tokenBytes[0].toInt() xor 0xFF).toByte()
        val tampered: String = Base64.getEncoder().encodeToString(tokenBytes)
        // An attacker who guesses a different version byte is by
        // definition not on the supported format; reject loudly.
        val error: Throwable = assertFails {
            decryptMessageContent(tampered)
        }
        // The error must be specific enough that the user (or the LLM,
        // if it sees the message) can tell the format is wrong, not
        // that the data is corrupt.
        assertTrue(
            (error.message ?: "").contains("version byte"),
            "version-byte mismatch should produce a clear error; got: ${error.message}"
        )
    }

    @Test
    fun `token shorter than minimum is rejected with clear error`() {
        // 1 (version) + 16 (nonce) + 32 (HMAC) = 49 bytes minimum.
        val tooShort = ByteArray(10)
        val token: String = Base64.getEncoder().encodeToString(tooShort)
        val error: Throwable = assertFails {
            decryptMessageContent(token)
        }
        assertTrue(
            (error.message ?: "").contains("Token too short"),
            "short-token error must be specific; got: ${error.message}"
        )
    }

    @Test
    fun `non-base64 ciphertext is rejected`() {
        // The function should not crash on garbage input — it should
        // surface a clear error.
        val error: Throwable = assertFails {
            decryptMessageContent("not!valid!base64!@#$")
        }
        // Either IllegalArgumentException from Base64 decoder, or
        // SecurityException from the version-byte check — both are
        // acceptable; a NullPointerException would not be.
        assertTrue(
            error is IllegalArgumentException || error is SecurityException,
            "expected IllegalArgumentException or SecurityException, got ${error::class.simpleName}"
        )
    }

    // ---- key isolation ----

    @Test
    fun `decryption fails when the key source changes between encrypt and decrypt`() {
        // Encrypt with the default key, then simulate a key rotation
        // by injecting a different key into the cached key fields.
        // The on-disk ciphertext must NOT decrypt under the new key —
        // this is the property that "an attacker who steals the
        // ciphertext but not the key can't read the plaintext".
        val plaintext = "secret context payload"
        val ciphertext: String = encryptMessageContent(plaintext)

        // Inject a different 32-byte key into both caches.
        injectDifferentKey("cachedEncryptionKey")
        injectDifferentKey("cachedAuthenticationKey")

        assertFailsWith<SecurityException>("rotated key must reject old ciphertext") {
            decryptMessageContent(ciphertext)
        }
    }

    private fun injectDifferentKey(fieldName: String) {
        val field: Field = EncryptionUtilTest::class.java.classLoader
            .loadClass("gradum.utils.EncryptionUtilKt")
            .getDeclaredField(fieldName)
        field.isAccessible = true
        // A clearly non-default 32-byte value. Doesn't have to be a
        // real derived key — the property under test is "different
        // key bytes → tag verification fails".
        field.set(null, ByteArray(32) { 0xAA.toByte() })
    }

    // ---- KDF domain separation ----

    @Test
    fun `encryption and authentication keys are different (domain separation)`() {
        // The KDF must produce different keys for "encrypt" vs
        // "authenticate" purposes; otherwise an attacker could
        // re-derive the encryption key from a known auth key (or
        // vice versa). The two cached values must never be equal.
        encryptMessageContent("trigger KDF + cache")

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
        assertEquals(32, encryptionKey.size, "encryption key must be 32 bytes")
        assertEquals(32, authenticationKey.size, "authentication key must be 32 bytes")
        assertFalse(
            encryptionKey.contentEquals(authenticationKey),
            "encrypt / authenticate keys must differ (KDF domain separation)"
        )
    }

    // ---- wire-format invariants ----

    @Test
    fun `ciphertext is base64-encoded`() {
        val ciphertext: String = encryptMessageContent("anything")
        // Round-tripping through the decoder must not throw and must
        // yield non-empty bytes — that proves the wire format is
        // base64 and not, say, hex or raw.
        val decoded: ByteArray = Base64.getDecoder().decode(ciphertext)
        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun `decoded token layout is version+nonce+ciphertext+hmac`() {
        val plaintext = "format invariant check"
        val ciphertext: String = encryptMessageContent(plaintext)
        val tokenBytes: ByteArray = Base64.getDecoder().decode(ciphertext)
        // Layout: 1 byte version + 16 bytes nonce + N bytes ciphertext + 32 bytes HMAC.
        val versionByte: Byte = tokenBytes[0]
        val nonceLength = 16
        val hmacLength = 32
        val ciphertextLength: Int = plaintext.toByteArray(Charsets.UTF_8).size // CTR preserves length
        assertEquals(
            1 + nonceLength + ciphertextLength + hmacLength,
            tokenBytes.size,
            "token size must match version+nonce+ct+hmac"
        )
        // The version byte is private, so we hardcode the documented
        // value 0x81 here. If the production code changes the version
        // byte, this assertion must be updated in lockstep.
        assertEquals(0x81.toByte(), versionByte, "version byte must be 0x81")
    }
}
