/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProjectPaths.java  2026-06-29 23:27:17 Changed by gwy
 */

package gradum;

import java.nio.file.Path;

/**
 * Defines project-level path constants used across the application.
 *
 * <p>All paths are resolved relative to the working directory at JVM startup
 * time and normalized to absolute paths. This ensures consistent behavior
 * regardless of where the application is launched from.</p>
 *
 * <p><b>Usage:</b> Reference {@link #OUTPUT_DIRECTORY} for any file I/O
 * that persists data between sessions (conversation history, command logs,
 * etc.).</p>
 *
 * @see gradum.utils.ContextManager
 * @see gradum.skill.RunCommandSkill
 */
public final class ProjectPaths {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();

    public static final Path OUTPUT_DIRECTORY = PROJECT_ROOT.resolve(".gradum");

    private ProjectPaths() {
    }
}
