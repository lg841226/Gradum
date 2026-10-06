package gradum.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("ServerAuth")

/**
 * Bearer-token auth for the embedded Gradum HTTP server.
 *
 * The server binds to loopback by default, but loopback does not stop
 * other local processes (or a DNS-rebinding browser page) from calling
 * the dangerous routes (`POST /skills/deploy`, `POST /events`). Every
 * request therefore has to carry a per-machine token, and the `Host`
 * header has to name a loopback host.
 *
 * The token is a 32-byte [SecureRandom] value encoded as unpadded
 * base64url. It is persisted to `~/.gradum/server.token` with `0600`
 * permissions so the plugin can read the same value; a container or a
 * test can override it with the `GRADUM_SERVER_TOKEN` environment
 * variable. Comparison is constant-time to avoid leaking the token
 * through response timing.
 */
object ServerAuth {

  /** Environment variable that overrides the on-disk token. */
  const val ENV_TOKEN: String = "GRADUM_SERVER_TOKEN"

  /** File name inside [ServerSettingsStore.configDir]. */
  const val TOKEN_FILE_NAME: String = "server.token"

  /** Query parameter that carries the token when opening the editor page. */
  const val TOKEN_QUERY_PARAM: String = "token"

  /** Cookie the editor page sets so its static assets pass auth. */
  const val TOKEN_COOKIE_NAME: String = "gradum_token"

  /** Prefix of the `Authorization` header value. */
  const val BEARER_PREFIX: String = "Bearer "

  /** Hosts the `Host` header may name; anything else is a rebinding attempt. */
  private val ALLOWED_HOST_NAMES: Set<String> =
    setOf("localhost", "127.0.0.1", "::1", "::ffff:127.0.0.1")

  private val secureRandom: SecureRandom = SecureRandom()

  private val base64UrlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

  /** Generates a fresh token: 32 random bytes, base64url without padding. */
  fun generateToken(): String {
    val tokenBytes = ByteArray(32)
    secureRandom.nextBytes(tokenBytes)
    return base64UrlEncoder.encodeToString(tokenBytes)
  }

  /** The token file inside the resolved config directory (`~/.gradum`). */
  fun tokenFile(): File = File(ServerSettingsStore.configDir(), TOKEN_FILE_NAME)

  /**
   * Resolves the server token. Precedence: [ENV_TOKEN] → existing
   * non-blank token file → freshly generated value (persisted 0600).
   * Never throws: if the file cannot be written the generated token is
   * still returned so the server can start.
   */
  fun resolveToken(): String {
    System.getenv(ENV_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val file: File = tokenFile()
    val existingToken: String? = try {
      if (file.isFile) file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } else null
    } catch (readFailure: Exception) {
      logger.warn("Failed to read server token file {}: {}", file.absolutePath, readFailure.message)
      null
    }
    if (existingToken != null) return existingToken

    val generatedToken: String = generateToken()
    try {
      file.parentFile?.mkdirs()
      file.writeText(generatedToken, Charsets.UTF_8)
      restrictToOwner(file)
      logger.info("Generated new server auth token at {}", file.absolutePath)
    } catch (writeFailure: Exception) {
      logger.warn("Failed to persist server token to {}: {}", file.absolutePath, writeFailure.message)
    }
    return generatedToken
  }

  /** Best-effort `0600`; non-POSIX filesystems (Windows) are left as-is. */
  private fun restrictToOwner(file: File) {
    try {
      val tokenPath = file.toPath()
      if (Files.getFileAttributeView(tokenPath, PosixFileAttributeView::class.java) != null) {
        Files.setPosixFilePermissions(tokenPath, PosixFilePermissions.fromString("rw-------"))
      }
    } catch (unsupported: UnsupportedOperationException) {
      // Non-POSIX filesystem; nothing to restrict.
    } catch (failure: Exception) {
      logger.warn("Failed to restrict server token permissions: {}", failure.message)
    }
  }

  /**
   * Constant-time comparison of a candidate token against the expected
   * one. Blank candidates fail without touching [expectedToken].
   */
  fun tokenMatches(candidate: String?, expectedToken: String): Boolean {
    if (candidate.isNullOrEmpty()) return false
    return MessageDigest.isEqual(
      candidate.toByteArray(Charsets.UTF_8),
      expectedToken.toByteArray(Charsets.UTF_8)
    )
  }

  /**
   * Whether a `Host` header value names a loopback host. Strips the port
   * and unwraps `[::1]` style IPv6 literals before comparing.
   */
  fun isAllowedHostHeader(hostHeader: String?): Boolean {
    if (hostHeader.isNullOrBlank()) return false
    return extractHostName(hostHeader).lowercase() in ALLOWED_HOST_NAMES
  }

  /** Whether a configured bind host stays on the loopback interface. */
  fun isLoopbackBindHost(host: String): Boolean =
    host.trim().lowercase() in ALLOWED_HOST_NAMES

  /** Extracts the host name from a `Host` header, dropping the port. */
  private fun extractHostName(hostHeader: String): String {
    val trimmedHost: String = hostHeader.trim()
    if (trimmedHost.startsWith("[")) {
      val bracketEnd: Int = trimmedHost.indexOf(']')
      if (bracketEnd > 0) return trimmedHost.substring(1, bracketEnd)
    }
    val colonIndex: Int = trimmedHost.indexOf(':')
    // Only strip the suffix when it is the single colon that separates the port.
    if (colonIndex > 0 && trimmedHost.indexOf(':', startIndex = colonIndex + 1) < 0) {
      return trimmedHost.substring(0, colonIndex)
    }
    return trimmedHost
  }
}
