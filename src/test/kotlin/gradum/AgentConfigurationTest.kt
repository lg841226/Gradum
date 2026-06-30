/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfigurationTest.kt  2026-06-30 20:55:00 Changed by gwy
 */

package gradum

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the wire format that the plugin sends for [ToolMode]. The plugin
 * emits `"read_only"` / `"single_step"` / `"write"` (lowercase, snake_case)
 * — see [gradum.idea.chat.ui.input.PermissionMode] — and the server's
 * [ToolMode.fromStringOrDefault] must translate those to the actual
 * enum constants. If this translation ever regresses to be case-sensitive
 * or to reject lowercase values, the read-only mode is silently treated
 * as write and the security gate in `Agent.executeSingleTool` becomes a
 * no-op. The default fallback (unknown -> WRITE) is also pinned because
 * it is the silent-success path that previously masked the bug.
 */
class AgentConfigurationTest {

    @Test
    fun `read_only wire value resolves to READ_ONLY`() {
        assertEquals(ToolMode.READ_ONLY, ToolMode.fromStringOrDefault("read_only"))
    }

    @Test
    fun `single_step wire value resolves to SINGLE_STEP`() {
        assertEquals(ToolMode.SINGLE_STEP, ToolMode.fromStringOrDefault("single_step"))
    }

    @Test
    fun `write wire value resolves to WRITE`() {
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault("write"))
    }

    @Test
    fun `uppercase wire value also resolves (case insensitive)`() {
        // The previous version of fromStringOrDefault was case sensitive.
        // The plugin now always sends lowercase but tests pin this so a
        // future refactor that re-introduces case sensitivity (or worse,
        // throws on mismatch) gets caught before shipping.
        assertEquals(ToolMode.READ_ONLY, ToolMode.fromStringOrDefault("READ_ONLY"))
        assertEquals(ToolMode.SINGLE_STEP, ToolMode.fromStringOrDefault("SINGLE_STEP"))
    }

    @Test
    fun `unknown wire value falls back to WRITE`() {
        // The fallback default IS the silent-success path that previously
        // hid the bug (the plugin emitted an i18n label like "Read-only
        // Permissions" which fell through to WRITE). Pin it so we know
        // if anyone changes the default to something stricter — a stricter
        // default would break compatibility with old plugins, a laxer
        // default would re-open the security hole.
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault("garbage"))
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault("Read-only Permissions"))
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault("只读权限"))
    }

    @Test
    fun `null and blank wire value falls back to WRITE`() {
        // The plugin should never send null/blank but the server must not
        // crash if it does. A null toolMode historically defaulted to
        // WRITE; that is documented behavior for the /events endpoint.
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault(null))
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault(""))
        assertEquals(ToolMode.WRITE, ToolMode.fromStringOrDefault("   "))
    }
}
