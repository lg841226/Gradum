/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumConfig.kt  2026-09-25 19:58:46 Changed by gwy
 */

package gradum

/**
 * Single source of truth for all cross-cutting numeric constants.
 *
 * Every magic number that was previously scattered across skill files,
 * AgentConfiguration, LLMClient, and DelegateSkill now lives here.
 * Skills reference these constants instead of defining their own copies.
 *
 * Rule: if a value appears in more than one file, it belongs here.
 */
object GradumConfig {

  /** Global agent loop timeout (50 min — covers multistep tasks). */
  const val AGENT_TIMEOUT_SECONDS: Int = 3000

  /** Sub-agent (delegate) timeout. */
  const val DELEGATE_TIMEOUT_SECONDS: Int = 600

  /** Default command execution timeout (2 min). */
  const val COMMAND_DEFAULT_TIMEOUT_SECONDS: Long = 120

  /** Maximum command execution timeout (10 min). */
  const val COMMAND_MAX_TIMEOUT_SECONDS: Long = 600

  /** Grace period after SIGTERM before SIGKILL (ms). */
  const val COMMAND_FORCE_KILL_DELAY_MS: Long = 3_000

  /** HTTP request timeout for LLM API calls (ms). */
  const val LLM_REQUEST_TIMEOUT_MS: Long = 600_000L

  /** Max chars of command output kept in LLM context. */
  const val COMMAND_MAX_OUTPUT_CHARS: Int = 16 * 1024

  /** Max file size for read_file (1 MB). */
  const val READ_MAX_FILE_SIZE: Int = 1 * 1024 * 1024

  /** Max size for a whole-file write via write_file (512 KB). */
  const val WRITE_MAX_FILE_SIZE: Int = 512 * 1024

  /** Max lines for read_file. */
  const val READ_MAX_LINES: Int = 10_000

  /** Max concurrent search workers. */
  const val SEARCH_MAX_CONCURRENCY: Int = 4

  /** Max file size for search indexing (2 MB). */
  const val SEARCH_MAX_FILE_SIZE: Long = 2L * 1024 * 1024

  /** Default max matches for grep. */
  const val SEARCH_MAX_MATCHES_DEFAULT: Int = 100

  /** Absolute max matches for grep. */
  const val SEARCH_MAX_MATCHES_LIMIT: Int = 1000

  /** Min pattern length for grep. */
  const val SEARCH_MIN_PATTERN_LENGTH: Int = 3

  /** Default max results for glob. */
  const val GLOB_MAX_RESULTS: Int = 500

  /** Absolute max results for glob. */
  const val GLOB_MAX_LIMIT: Int = 2000

  const val EXPLORE_MIN_DEPTH: Int = 5
  const val EXPLORE_MAX_DEPTH: Int = 14
  const val EXPLORE_DEFAULT_DEPTH: Int = 8
  const val EXPLORE_DEFAULT_LIMIT: Int = 500
  const val EXPLORE_MAX_CHILDREN: Int = 2048

  /** Max messages kept in conversation history. */
  const val MAX_HISTORY_MESSAGES: Int = 30

  /** Min task description length for delegate_task. */
  const val MIN_TASK_LENGTH: Int = 60

  const val DEFAULT_TOP_P: Double = 0.9
  const val DEFAULT_TEMPERATURE: Double = 0.7
  const val DEFAULT_MAX_TOKENS: Int = 2048 * 12
  const val DEFAULT_CONTEXT_WINDOW_SIZE: Int = 8192
  const val DEFAULT_OLLAMA_BASE_URL: String = "http://localhost:11434"
  const val DEFAULT_CHAT_COMPLETIONS_PATH: String = "/v1/chat/completions"
}
