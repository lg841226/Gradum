package gradum.idea.server

import java.io.File

/**
 * Reads the bearer token the embedded Gradum server publishes at
 * `~/.gradum/server.token` (written by `gradum.server.ServerAuth`).
 *
 * The plugin sends the token on every request to the embedded server so
 * local processes and DNS-rebinding browser pages cannot call the
 * server's dangerous routes. The file is cached by last-modified time so
 * the common path is a single `lastModified()` stat; [invalidate] is
 * called after an HTTP 401 to pick up a freshly rotated token.
 */
object ServerTokenStore {

  /** Environment variable that mirrors the server-side override. */
  const val ENV_TOKEN: String = "GRADUM_SERVER_TOKEN"

  private const val TOKEN_FILE_NAME: String = "server.token"

  private val cacheLock: Any = Any()

  @Volatile
  private var cachedModifiedAt: Long = -1L

  @Volatile
  private var cachedToken: String? = null

  /**
   * Returns the current token, or `null` when none has been published yet
   * (server not started, or the file is unreadable). Never throws.
   */
  fun currentToken(): String? {
    System.getenv(ENV_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val tokenFile: File = tokenFile()
    val modifiedAt: Long = if (tokenFile.isFile) tokenFile.lastModified() else -1L
    synchronized(cacheLock) {
      if (modifiedAt != cachedModifiedAt) {
        cachedToken = readToken(tokenFile)
        cachedModifiedAt = modifiedAt
      }
      return cachedToken
    }
  }

  /** Drops the cached value so the next [currentToken] re-reads the file. */
  fun invalidate() {
    synchronized(cacheLock) {
      cachedModifiedAt = -1L
      cachedToken = null
    }
  }

  private fun readToken(tokenFile: File): String? =
    try {
      if (tokenFile.isFile) {
        tokenFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }

  private fun tokenFile(): File = File(configDir(), TOKEN_FILE_NAME)

  private fun configDir(): File =
    System.getProperty("gradum.server.configDir")?.let(::File)
      ?: File(System.getProperty("user.home"), ".gradum")
}
