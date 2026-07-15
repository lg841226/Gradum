/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Version.java  2026-07-14 21:27:12 Changed by gwy
 */

package gradum;


/**
 * Application version constant, following Semantic Versioning (MAJOR.MINOR.PATCH).
 *
 * <p><b>When to update:</b></p>
 * <ul>
 *   <li><b>MAJOR</b> — Breaking changes to HTTP API contract (NDJSON event format,
 *       request/response schema, skill parameter names). Clients (IDE plugins,
 *       web UIs) must update to stay compatible.</li>
 *   <li><b>MINOR</b> — New features (new skills, new endpoints, new event types)
 *       that are backward-compatible. Existing clients can continue working.</li>
 *   <li><b>PATCH</b> — Bug fixes, performance improvements, or internal refactors
 *       with no observable behavior change.</li>
 * </ul>
 *
 * <p><b>Where it appears:</b></p>
 * <ul>
 *   <li>NDJSON event stream — {@code session_start} and {@code session_end} events</li>
 *   <li>HTTP {@code /health} endpoint response</li>
 *   <li>Test assertions in {@code ServerRoutesTest}</li>
 * </ul>
 *
 * <p><b>Update process:</b> Change the string literal below, then run
 * {@code ./gradlew build} to verify all tests pass.</p>
 */
public final class Version {

    public static final String GRADUM_VERSION = "0.9.2";

    private Version() {
    }
}
