# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""
Encryption utilities for context persistence.

This module provides a custom authenticated encryption scheme using only
Python standard library modules (hashlib, hmac, os). The scheme provides:

- Confidentiality: HMAC-based stream cipher (HMAC-SHA256 CTR mode)
- Integrity: HMAC-SHA256 authentication tag
- Randomness: 16-byte random nonce per encryption
- Versioning: Version byte for future algorithm upgrades

Token format (version 0x81):
┌─────────┬──────────┬─────────────┬──────────────┐
│ Version │  Nonce   │ Ciphertext  │    HMAC      │
│ (1 B)   │ (16 B)   │ (N B)       │   (32 B)     │
└─────────┴──────────┴─────────────┴──────────────┘

Purpose: Prevent attackers from forging valid assistant messages in
context.json without knowing the encryption key.
"""

import base64
import hashlib
import hmac
import logging
import os
import struct

_logger = logging.getLogger(__name__)

# Version byte for token format
_VERSION = b"\x81"

# Built-in key source
_BUILTIN_KEY_SOURCE = b"gradum-context-v1-encryption-key-2026"

# Nonce size in bytes
_NONCE_SIZE = 16

# HMAC size in bytes (SHA256)
_HMAC_SIZE = 32


def _derive_key(key_source: bytes, purpose: bytes = b"enc") -> bytes:
    """Derive a 32-byte key from key source using HKDF-like construction.

    Args:
        key_source: Raw key material
        purpose: Key purpose identifier (for key separation)

    Returns:
        32-byte derived key
    """
    # PRK = HMAC-SHA256(salt, IKM)
    prk = hmac.new(b"gradum-key-derivation", key_source, hashlib.sha256).digest()

    # OKM = HMAC-SHA256(PRK, purpose || 0x01)
    info = purpose + b"\x01"
    return hmac.new(prk, info, hashlib.sha256).digest()


def _hmac_ctr_encrypt(plaintext: bytes, key: bytes, nonce: bytes) -> bytes:
    """Encrypt using HMAC-SHA256 in CTR (counter) mode.

    This generates a pseudorandom stream by repeatedly HMAC-ing the nonce
    with an incrementing counter, then XORs with plaintext.

    Args:
        plaintext: Data to encrypt
        key: 32-byte encryption key
        nonce: 16-byte random nonce

    Returns:
        Encrypted bytes (same length as plaintext)
    """
    ciphertext = bytearray(len(plaintext))
    block_size = 32  # SHA256 output size

    for i in range(0, len(plaintext), block_size):
        # Counter = i // block_size (as 4-byte big-endian)
        counter = struct.pack(">I", i // block_size)

        # Stream block = HMAC-SHA256(key, nonce || counter)
        stream_block = hmac.new(key, nonce + counter, hashlib.sha256).digest()

        # XOR plaintext block with stream block
        block_len = min(block_size, len(plaintext) - i)
        for j in range(block_len):
            ciphertext[i + j] = plaintext[i + j] ^ stream_block[j]

    return bytes(ciphertext)


def _compute_hmac(key: bytes, data: bytes) -> bytes:
    """Compute HMAC-SHA256 for authentication.

    Args:
        key: HMAC key (32 bytes)
        data: Data to authenticate

    Returns:
        32-byte HMAC tag
    """
    return hmac.new(key, data, hashlib.sha256).digest()


def _get_key_source() -> bytes:
    """Get key source from environment variable or built-in default.

    Returns:
        Key source bytes
    """
    env_key = os.environ.get("GRADUM_CONTEXT_KEY")
    return env_key.encode("utf-8") if env_key else _BUILTIN_KEY_SOURCE


def encrypt_content(plaintext: str) -> str:
    """Encrypt plaintext using custom authenticated encryption scheme.

    Process:
    1. Derive encryption key and HMAC key from key source
    2. Generate 16-byte random nonce
    3. Encrypt using HMAC-CTR mode
    4. Compute HMAC for integrity
    5. Return Base64-encoded token

    Args:
        plaintext: String to encrypt

    Returns:
        Base64-encoded token: version (1B) + nonce (16B) + ciphertext (NB) + HMAC (32B)
    """
    key_source = _get_key_source()

    # Derive separate keys for encryption and authentication
    enc_key = _derive_key(key_source, purpose=b"encrypt")
    auth_key = _derive_key(key_source, purpose=b"authenticate")

    # Generate random nonce
    nonce = os.urandom(_NONCE_SIZE)

    # Encrypt plaintext
    plaintext_bytes = plaintext.encode("utf-8")
    ciphertext = _hmac_ctr_encrypt(plaintext_bytes, enc_key, nonce)

    # Compute HMAC over version + nonce + ciphertext
    token_body = _VERSION + nonce + ciphertext
    mac = _compute_hmac(auth_key, token_body)

    # Combine: token_body + HMAC
    full_token = token_body + mac

    return base64.b64encode(full_token).decode("ascii")


def decrypt_content(ciphertext: str) -> str:
    """Decrypt ciphertext using custom authenticated encryption scheme.

    Token format: version (1B) + nonce (16B) + ciphertext (NB) + HMAC (32B)

    Args:
        ciphertext: Base64-encoded token

    Returns:
        Decrypted plaintext string, or empty string if input is empty

    Raises:
        TypeError: If ciphertext is not a string
        ValueError: If ciphertext is invalid, too short, or HMAC verification fails
        UnicodeDecodeError: If decrypted bytes are not valid UTF-8
    """
    if ciphertext is None:
        raise TypeError("ciphertext cannot be None")
    if not isinstance(ciphertext, str):
        raise TypeError(f"ciphertext must be str, got {type(ciphertext).__name__}")
    if not ciphertext:
        return ""

    # Decode Base64
    try:
        raw = base64.b64decode(ciphertext.encode("ascii"))
    except Exception as e:
        raise ValueError(f"Invalid Base64: {e}") from e

    # Validate minimum length: version (1) + nonce (16) + HMAC (32) = 49 bytes minimum
    min_length = 1 + _NONCE_SIZE + _HMAC_SIZE
    if len(raw) < min_length:
        raise ValueError(f"Token too short: {len(raw)} bytes (minimum {min_length})")

    # Validate version byte
    if raw[0:1] != _VERSION:
        raise ValueError(f"Invalid version byte: {raw[0]:#04x} (expected {_VERSION[0]:#04x})")

    # Extract components
    version = raw[0:1]
    nonce = raw[1 : 1 + _NONCE_SIZE]
    mac = raw[-_HMAC_SIZE:]
    ciphertext_bytes = raw[1 + _NONCE_SIZE : -_HMAC_SIZE]

    # Derive keys
    key_source = _get_key_source()
    enc_key = _derive_key(key_source, purpose=b"encrypt")
    auth_key = _derive_key(key_source, purpose=b"authenticate")

    # Verify HMAC
    token_body = version + nonce + ciphertext_bytes
    expected_mac = _compute_hmac(auth_key, token_body)

    if not hmac.compare_digest(mac, expected_mac):
        raise ValueError("HMAC verification failed - content may be tampered")

    # Decrypt
    plaintext_bytes = _hmac_ctr_encrypt(ciphertext_bytes, enc_key, nonce)
    return plaintext_bytes.decode("utf-8")
