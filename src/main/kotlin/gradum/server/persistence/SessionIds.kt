package gradum.server.persistence

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Server-side session id allocation, in the `yyyyMMdd-HHmmss-xxxxxx` shape the
 * plugin used to generate: the time prefix keeps ids lexicographically
 * sortable and the 6-char hex suffix keeps same-second collisions negligible.
 */
object SessionIds {

  private const val SUFFIX_CHARS: Int = 6
  private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()
  private val SESSION_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  fun next(): String {
    val timeStamp: String = LocalDateTime.now().format(SESSION_ID_FORMAT)
    val suffixChars = CharArray(size = SUFFIX_CHARS) {
      HEX_CHARS[Random.nextInt(until = HEX_CHARS.size)]
    }
    return "$timeStamp-${String(suffixChars)}"
  }
}
