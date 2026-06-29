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
 * <p>The root path is mutable so that the server can be launched from a
 * developer workspace (Gradle Run Config, IDE, etc.) while still operating
 * on a different target project — the developer passes {@code --project-root}
 * at startup and we resolve every per-project path against that override.
 * If no override is set, the process CWD is used (legacy behaviour).
 *
 * <p>Call {@link #setProjectRoot(Path)} from {@code Main.main()} before
 * any other Gradum code touches these paths, otherwise the resolved
 * directory will reflect the CWD instead of the intended target.</p>
 *
 * @see gradum.server.MainKt
 * @see gradum.utils.ContextManager
 * @see gradum.skill.RunCommandSkill
 */
public final class ProjectPaths {

    private static volatile Path projectRoot = Path.of("").toAbsolutePath().normalize();

    public static Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * Override the project root. Pass an absolute path; relative input is
     * resolved against the current CWD. Pass {@code null} to reset to CWD
     * (mostly useful in tests).
     */
    public static void setProjectRoot(Path root) {
        projectRoot = (root == null ? Path.of("").toAbsolutePath().normalize() : root.toAbsolutePath().normalize());
    }

    /**
     * Per-project output directory ({@code <root>/.gradum}). Held as a
     * method instead of a static field so the override above takes effect
     * on every read.
     */
    public static Path outputDirectory() {
        return projectRoot.resolve(".gradum");
    }

    private ProjectPaths() {
    }
}
