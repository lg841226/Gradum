/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PluginConfig.kt  2026-08-24 16:40:40 Changed by gwy
 */

package gradum.idea

import java.time.Duration

/**
 * Single source of truth for cross-cutting plugin constants.
 *
 * Component-local animation timings (e.g. bubble fade-in, jump-to-bottom
 * transitions) stay in their own files — they are not shared and centralizing
 * them would add indirection without value.
 *
 * This object covers values that are:
 *  - referenced from multiple files, OR
 *  - duplicated across files, OR
 *  - HTTP/network/protocol-level config shared by API and session code.
 */
object PluginConfig {

    /** Connect timeout for the main API client (GradumApiClient). */
    val API_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

    /** Request timeout for the /events SSE endpoint. */
    val API_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    /** Connect timeout for provider probe requests. */
    val PROBE_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)

    /** Request timeout for provider probe POST. */
    val PROBE_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

    /** Thumbnail image HTTP timeout. */
    const val THUMBNAIL_TIMEOUT_MS: Int = 3_000

    /** Max connection retry attempts before surfacing a server error. */
    const val MAX_CONNECT_ATTEMPTS: Int = 6

    /** Base backoff delay (ms), doubled after each failed attempt. */
    const val CONNECT_BACKOFF_MS: Long = 2_000

    /** Max file attachments per message. */
    const val MAX_ATTACHMENTS: Int = 10

    /** Max queued pending messages while one is sending. */
    const val MAX_PENDING_MESSAGES: Int = 2

    /** Min sessions combined in a merge operation. */
    const val MIN_MERGE_SESSIONS: Int = 2

    /** Min ms the "Sending" indicator is shown before the request fires. */
    const val MIN_SENDING_MS: Long = 400

    /** Default sub-agent timeout fallback when server doesn't provide one. */
    const val SUB_AGENT_TIMEOUT_FALLBACK: Int = 600

    /** Max chars for auto-generated transcript title. */
    const val MAX_TITLE_LENGTH: Int = 48

    /** Max chars of model display name in selector. */
    const val MAX_MODEL_NAME_CHARS: Int = 14

    /** Max chars of tool-call result shown in details view. */
    const val TOOL_DETAILS_RESULT_MAX_CHARS: Int = 1000

    /** Max log preview chars for inline Markdown parse diagnostics. */
    const val BAIL_REASON_LOG_PREVIEW_CHARS: Int = 80
    const val PARSE_FAILURE_LOG_PREVIEW_CHARS: Int = 120
    const val WALK_FAILURE_LOG_PREVIEW_CHARS: Int = 120

    /** In-memory LRU cache entries for decoded thumbnail bitmaps. */
    const val THUMBNAIL_LRU_MAX_ENTRIES: Int = 64

    /** Max bytes to read from a single thumbnail HTTP response. */
    const val THUMBNAIL_MAX_IMAGE_BYTES: Int = 512 * 1024

    /** Disk cache for thumbnail bytes. */
    const val THUMBNAIL_DISK_CACHE_MAX_BYTES: Long = 50L * 1024 * 1024

    /** Max size (5 MB) of an image file for upload attachment. */
    const val MAX_IMAGE_UPLOAD_BYTES: Long = 5L * 1024L * 1024L

    /** Max lines of Markdown rendered in debug mode. */
    const val MAX_MARKDOWN_LINES: Int = 4_000

    /** Lower bound of configurable provider poll interval. */
    const val MIN_POLL_INTERVAL_SECONDS: Int = 2

    /** Upper bound of configurable provider poll interval. */
    const val MAX_POLL_INTERVAL_SECONDS: Int = 60

    /** Input debounce delay before re-validating URL/key fields. */
    const val INPUT_DEBOUNCE_MS: Long = 500
}
