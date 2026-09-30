package gradum

/**
 * Single source of truth for all cross-cutting numeric constants.
 *
 * Every magic number that was previously scattered across skill files,
 * AgentConfiguration, LLMClient, and DelegateSkill now lives here.
 * Skills reference these constants instead of defining their own copies.
 *
 * Rule: if a value appears in more than one file, it belongs here.
 *
 * Documented constants:
 * - AGENT_TIMEOUT_SECONDS: global agent loop timeout (50 min for multistep tasks).
 * - DELEGATE_TIMEOUT_SECONDS: sub-agent (delegate) timeout.
 * - COMMAND_DEFAULT_TIMEOUT_SECONDS: default command execution timeout (2 min).
 * - COMMAND_MAX_TIMEOUT_SECONDS: maximum command execution timeout (10 min).
 * - COMMAND_FORCE_KILL_DELAY_MS: grace period after SIGTERM before SIGKILL (ms).
 * - LLM_REQUEST_TIMEOUT_MS: HTTP request timeout for LLM API calls (ms).
 * - COMMAND_MAX_OUTPUT_CHARS: max chars of command output kept in LLM context.
 * - READ_MAX_FILE_SIZE: max file size for read_file (1 MB).
 * - WRITE_MAX_FILE_SIZE: max size for a whole-file write via write_file (512 KB).
 * - READ_MAX_LINES: max lines for read_file.
 * - SEARCH_MAX_CONCURRENCY: max concurrent search workers.
 * - SEARCH_MAX_FILE_SIZE: max file size for search indexing (2 MB).
 * - SEARCH_MAX_MATCHES_DEFAULT: default max matches for grep.
 * - SEARCH_MAX_MATCHES_LIMIT: absolute max matches for grep.
 * - SEARCH_MIN_PATTERN_LENGTH: min pattern length for grep.
 * - GLOB_MAX_RESULTS: default max results for glob.
 * - GLOB_MAX_LIMIT: absolute max results for glob.
 * - MAX_HISTORY_MESSAGES: max messages kept in conversation history.
 * - MIN_TASK_LENGTH: min task description length for delegate_task.
 */
object GradumConfig {

  const val AGENT_TIMEOUT_SECONDS: Int = 3000

  const val DELEGATE_TIMEOUT_SECONDS: Int = 600

  const val COMMAND_DEFAULT_TIMEOUT_SECONDS: Long = 120

  const val COMMAND_MAX_TIMEOUT_SECONDS: Long = 600

  const val COMMAND_FORCE_KILL_DELAY_MS: Long = 3_000

  const val LLM_REQUEST_TIMEOUT_MS: Long = 600_000L

  const val COMMAND_MAX_OUTPUT_CHARS: Int = 16 * 1024

  const val READ_MAX_FILE_SIZE: Int = 1 * 1024 * 1024

  const val WRITE_MAX_FILE_SIZE: Int = 512 * 1024

  const val READ_MAX_LINES: Int = 10_000

  const val SEARCH_MAX_CONCURRENCY: Int = 4

  const val SEARCH_MAX_FILE_SIZE: Long = 2L * 1024 * 1024

  const val SEARCH_MAX_MATCHES_DEFAULT: Int = 100

  const val SEARCH_MAX_MATCHES_LIMIT: Int = 1000

  const val SEARCH_MIN_PATTERN_LENGTH: Int = 3

  const val GLOB_MAX_RESULTS: Int = 500

  const val GLOB_MAX_LIMIT: Int = 2000

  const val EXPLORE_MIN_DEPTH: Int = 5

  const val EXPLORE_MAX_DEPTH: Int = 14

  const val EXPLORE_DEFAULT_DEPTH: Int = 8

  const val EXPLORE_DEFAULT_LIMIT: Int = 500

  const val EXPLORE_MAX_CHILDREN: Int = 2048

  const val MAX_HISTORY_MESSAGES: Int = 30

  const val MIN_TASK_LENGTH: Int = 60

  const val DEFAULT_TOP_P: Double = 0.9

  const val DEFAULT_TEMPERATURE: Double = 0.7

  const val DEFAULT_MAX_TOKENS: Int = 2048 * 12

  const val DEFAULT_CONTEXT_WINDOW_SIZE: Int = 8192

  const val DEFAULT_OLLAMA_BASE_URL: String = "http://localhost:11434"

  const val DEFAULT_CHAT_COMPLETIONS_PATH: String = "/v1/chat/completions"
}
