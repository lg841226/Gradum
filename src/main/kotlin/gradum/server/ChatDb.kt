package gradum.server

import gradum.server.ChatDb.chainHash
import gradum.utils.JsonUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

private val logger: Logger = LoggerFactory.getLogger("ChatDb")

private const val SCHEMA_VERSION: Int = 1

/**
 * SQLite-backed store for conversation contexts. The database lives inside
 * the project it belongs to, at `<projectRoot>.gradum/db/gradum.db`,
 * matching the per-project layout of the classic `context.json` files.
 *
 * Every saved context replaces the session's rows inside one transaction,
 * and each row carries a [chainHash] over the previous row, so tampering
 * with or corrupting any message invalidates that row and everything after
 * it. [loadContext] drops the broken tail instead of trusting it, which is
 * what keeps damaged records out of the model context.
 *
 * The store is deliberately dumb about message content: cleaning and
 * encryption stay in `ContextManager`, the single source of truth for the
 * message pipeline, so this class only moves serialized payloads in and
 * out of rows.
 */
object ChatDb {

  /** One open connection per project database; several sessions of the same project share theirs. */
  private val connections: ConcurrentHashMap<String, Connection> = ConcurrentHashMap()

  fun saveContext(
    projectRoot: Path,
    sessionId: String,
    modelName: String,
    messages: List<Map<String, Any>>,
  ): Boolean {
    val activeConnection: Connection = connectionFor(projectRoot)
    val nowMillis: Long = System.currentTimeMillis()

    activeConnection.autoCommit = false
    try {
      activeConnection.prepareStatement(
        """
        INSERT INTO sessions (id, model_name, created_at, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET model_name = excluded.model_name, updated_at = excluded.updated_at
        """.trimIndent()
      ).use { upsert ->
        upsert.setString(1, sessionId)
        upsert.setString(2, modelName)
        upsert.setLong(3, nowMillis)
        upsert.setLong(4, nowMillis)
        upsert.executeUpdate()
      }

      activeConnection.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { clear ->
        clear.setString(1, sessionId)
        clear.executeUpdate()
      }

      var previousHash = ""
      activeConnection.prepareStatement(
        "INSERT INTO messages (session_id, seq, message_id, role, payload, hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
      ).use { insert ->
        for ((index, message) in messages.withIndex()) {
          val payload: String = JsonUtil.encodeMap(input = message)
          val rowHash: String = chainHash(previousHash, payload)
          insert.setString(1, sessionId)
          insert.setInt(2, index)
          insert.setString(3, message["id"] as? String)
          insert.setString(4, message["role"] as? String ?: "")
          insert.setString(5, payload)
          insert.setString(6, rowHash)
          insert.setLong(7, nowMillis)
          insert.addBatch()
          previousHash = rowHash
        }
        insert.executeBatch()
      }

      activeConnection.commit()
      return true
    } catch (writeException: SQLException) {
      activeConnection.rollback()
      logger.error("Failed to save context for session {}: {}", sessionId, writeException.message)
      return false
    } finally {
      activeConnection.autoCommit = true
    }
  }

  /**
   * Loads the stored context of [sessionId] in sequence order, verifying the
   * hash chain along the way. The first row whose hash no longer matches
   * corrupted or edited is dropped together with every row after it, so a
   * broken prefix still resumes and only the damaged tail is lost.
   */
  fun loadContext(projectRoot: Path, sessionId: String): List<Map<String, Any>> {
    val activeConnection: Connection = connectionFor(projectRoot)

    activeConnection.prepareStatement(
      "SELECT seq, payload, hash FROM messages WHERE session_id = ? ORDER BY seq"
    ).use { query ->
      query.setString(1, sessionId)
      query.executeQuery().use { rows ->
        val messages: MutableList<Map<String, Any>> = mutableListOf()
        var previousHash = ""

        while (rows.next()) {
          val payload: String = rows.getString("payload")
          val storedHash: String = rows.getString("hash")
          if (chainHash(previousHash, payload) != storedHash) {
            logger.warn(
              "Hash chain broken at seq {} in session {}; dropping this message and all later ones",
              rows.getInt("seq"), sessionId
            )
            break
          }
          messages.add(decodeMessage(payload))
          previousHash = storedHash
        }
        return messages
      }
    }
  }

  /**
   * Drops the message carrying [messageId] and every later one, keeping the
   * rows before it: the prefix hash chain stays valid, so nothing else
   * needs rewriting. Returns `true` only when the id was found.
   */
  fun rewindTo(projectRoot: Path, sessionId: String, messageId: String): Boolean {
    if (messageId.isBlank()) return false
    val activeConnection: Connection = connectionFor(projectRoot)

    activeConnection.prepareStatement(
      "SELECT seq FROM messages WHERE session_id = ? AND message_id = ?"
    ).use { locate ->
      locate.setString(1, sessionId)
      locate.setString(2, messageId)
      locate.executeQuery().use { rows ->
        if (!rows.next()) return false

        activeConnection.prepareStatement(
          "DELETE FROM messages WHERE session_id = ? AND seq >= ?"
        ).use { delete ->
          delete.setString(1, sessionId)
          delete.setInt(2, rows.getInt("seq"))
          return delete.executeUpdate() > 0
        }
      }
    }
  }

  /**
   * Opens (or reuses) the project's own connection, creating the database
   * file and its schema on first touch. Sessions of one project share the
   * connection; another project gets its own file and connection.
   */
  private fun connectionFor(projectRoot: Path): Connection {
    val databaseFile: Path = projectRoot.resolve(".gradum").resolve("db").resolve("gradum.db")
    val cacheKey: String = databaseFile.toAbsolutePath().normalize().toString()
    return connections.computeIfAbsent(cacheKey) { openDatabase(databaseFile) }
  }

  private fun openDatabase(databaseFile: Path): Connection {
    Files.createDirectories(databaseFile.parent)

    val openedConnection: Connection =
      DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
    try {
      openedConnection.createStatement().use { statement ->
        // Rollback journal mode: at rest only gradum.db exists, no -wal/-shm
        // sidecar files next to the project. Explicitly set (not just left at
        // the default) so databases previously created in WAL mode convert back.
        statement.execute("PRAGMA journal_mode=DELETE")
        statement.execute("PRAGMA foreign_keys=ON")
        statement.execute("CREATE TABLE IF NOT EXISTS schema_info (version INTEGER NOT NULL)")
        statement.execute(
          """
          CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            model_name TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
          """.trimIndent()
        )
        statement.execute(
          """
          CREATE TABLE IF NOT EXISTS messages (
            session_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            message_id TEXT,
            role TEXT NOT NULL,
            payload TEXT NOT NULL,
            hash TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (session_id, seq),
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
          )
          """.trimIndent()
        )
        statement.execute(
          "CREATE INDEX IF NOT EXISTS idx_messages_message_id ON messages(session_id, message_id)"
        )
        statement.executeQuery("SELECT COUNT(*) FROM schema_info").use { rows ->
          if (!rows.next() || rows.getLong(1) == 0L) {
            statement.execute("INSERT INTO schema_info (version) VALUES ($SCHEMA_VERSION)")
          }
        }
      }
    } catch (schemaException: SQLException) {
      openedConnection.close()
      throw schemaException
    }

    logger.info("ChatDb initialized at {}", databaseFile)
    return openedConnection
  }

  private fun decodeMessage(payload: String): MutableMap<String, Any> {
    @Suppress("UNCHECKED_CAST")
    val decoded: Map<String, Any> = JsonUtil.decodeMap(input = payload) as Map<String, Any>
    return LinkedHashMap(decoded)
  }

  private fun chainHash(previousHash: String, payload: String): String {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    return digest.digest("$previousHash\n$payload".toByteArray(Charsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }
  }
}
