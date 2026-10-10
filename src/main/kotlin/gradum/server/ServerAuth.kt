package gradum.server

import gradum.server.ServerAuth.ALLOWED_HOST_NAMES
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
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
 * header has to name this machine: a loopback name always, and once
 * `server.allowRemote` lifts the bind past loopback, the machine's own
 * LAN addresses and names as well.
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

  /** The POST that trades a pairing code for the auth cookie. */
  const val PAIR_PATH: String = "/skills/pair"

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

  /** The token file inside the resolved config directory (`/.gradum`). */
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
    val existingToken: String? =
      try {
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
    } catch (_: UnsupportedOperationException) {
    } catch (failure: Exception) {
      logger.warn("Failed to restrict server token permissions: {}", failure.message)
    }
  }

  /**
   * Constant-time comparison of a candidate token against the expected
   * one. Blank candidates fail without touching [expectedToken].
   */
  fun tokenMatches(candidate: String?, expectedToken: String): Boolean {
    return !candidate.isNullOrEmpty() && MessageDigest.isEqual(
      candidate.toByteArray(Charsets.UTF_8),
      expectedToken.toByteArray(Charsets.UTF_8)
    )
  }

  /**
   * Whether a `Host` header value may answer this request. Loopback names
   * pass everywhere — they are what the local plugin and the build's health
   * probe dial. When the bind address is not loopback (which only happens
   * with `server.allowRemote` on), the machine's own addresses and names pass
   * too, so a LAN client can dial by IP or hostname; any other name stays a
   * rebinding attempt and is refused. [machineNames] is a test seam for
   * [localMachineHostNames].
   */
  fun isAllowedHostHeader(
    hostHeader: String?,
    machineNames: Set<String>? = null,
    bindHost: String = ServerConfiguration.DEFAULT_HOST_ADDRESS
  ): Boolean {
    if (hostHeader.isNullOrBlank()) return false
    val hostName = extractHostName(hostHeader).lowercase()

    return when {
      hostName in ALLOWED_HOST_NAMES -> true
      isLoopbackBindHost(bindHost) -> false
      else -> hostName in (machineNames ?: localMachineHostNames())
    }
  }

  /** Whether a configured bind host stays on the loopback interface. */
  fun isLoopbackBindHost(host: String): Boolean =
    host.trim().lowercase() in ALLOWED_HOST_NAMES

  /**
   * Addresses this machine answers on — the ones a LAN client puts in its
   * `Host` header once the server binds past loopback. Loopback is skipped
   * (it is already in [ALLOWED_HOST_NAMES]) and so is the link-local range,
   * whose zone-qualified IPv6 forms never appear in a browser's Host line.
   */
  fun localAddresses(): List<String> {
    val addresses: MutableList<String> = mutableListOf()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses
      for (networkInterface in interfaces) {
        if (!networkInterface.isUp) continue
        for (address in networkInterface.inetAddresses) {
          if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
          val literal: String = address.hostAddress?.substringBefore('%')?.lowercase() ?: continue
          if (literal !in addresses) addresses += literal
        }
      }
    } catch (interfaceFailure: SocketException) {
      logger.warn("Failed to enumerate network interfaces: {}", interfaceFailure.message)
    }
    return addresses
  }

  /**
   * Every name a LAN client may legally use for `Host` when the server binds
   * past loopback: the addresses above plus the local DNS name in its full,
   * first-label, and `.local` mDNS forms. Enumerated per check — the set is
   * tiny and interfaces can appear after boot (Wi-Fi joining later). A
   * rebound attacker domain matches none of these, so the guard holds.
   */
  fun localMachineHostNames(): Set<String> {
    val names: MutableSet<String> = localAddresses().toMutableSet()
    try {
      val hostName: String = InetAddress.getLocalHost().hostName.lowercase()
      if (hostName.isNotEmpty()) {
        names += hostName
        val firstLabel: String = hostName.substringBefore('.')
        if (firstLabel.isNotEmpty()) {
          names += firstLabel
          names += "$firstLabel.local"
        }
      }
    } catch (lookupFailure: UnknownHostException) {
      logger.debug("Local hostname lookup failed: {}", lookupFailure.message)
    }
    return names
  }

  /**
   * The auth cookie every credential path sets: host-only, invisible to
   * scripts, and closed to cross-site requests.
   */
  fun authCookieHeader(authToken: String): String =
    "$TOKEN_COOKIE_NAME=$authToken; Path=/; HttpOnly; SameSite=Strict"

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

/**
 * The five-character code a device on another machine types to leave reader
 * mode. It exists because a 43-character token is what the host machine
 * pastes, while a phone or tablet can only reasonably type five characters;
 * the code is printed to the host's console at startup and regenerated on
 * every restart, so it never lives in a file or a URL. Letters and digits,
 * uppercase, without the confusable pairs (0/O, 1/I/L) a person would
 * retype wrong.
 *
 * Three wrong tries in a row pause every try for ten seconds, which keeps
 * the code's space out of reach of a script. Comparison is constant-time
 * (through [ServerAuth.tokenMatches]) so response timing leaks nothing, and
 * candidates are matched case-insensitively so lowercase typing still lands.
 */
class PairingState(val code: String = generatePairingCode()) {

  private var failures: Int = 0

  private var cooldownUntilMillis: Long = 0L

  /** True when [candidate] is the code (either case) and no cooldown runs. */
  @Synchronized
  fun tryUnlock(candidate: String?): Boolean {
    val nowMillis: Long = System.currentTimeMillis()
    if (nowMillis < cooldownUntilMillis) return false
    val normalized: String? = candidate?.trim()?.uppercase(Locale.ROOT)
    if (ServerAuth.tokenMatches(normalized, code)) {
      failures = 0
      return true
    }
    if (!normalized.isNullOrBlank()) {
      failures += 1
      if (failures >= MAX_FAILURES) {
        cooldownUntilMillis = nowMillis + COOLDOWN_MILLIS
        failures = 0
      }
    }
    return false
  }

  companion object {
    /** Characters a code draws from: no 0/O, 1/I/L to mistype. */
    const val CODE_ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** How many characters a code holds. */
    const val CODE_LENGTH: Int = 5

    /** Wrong tries before every try pauses. */
    private const val MAX_FAILURES: Int = 3

    /** How long the pause lasts, in milliseconds. */
    private const val COOLDOWN_MILLIS: Long = 10_000L

    /** A fresh random five-character code, uppercase. */
    fun generatePairingCode(): String {
      val secureRandom = SecureRandom()
      return buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) {
          append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)])
        }
      }
    }
  }
}
