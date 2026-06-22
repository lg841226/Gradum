/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProjectPaths.java  2026-06-22 20:11:15 Changed by gwy
 */

package gradum;

import java.nio.file.Path;

public final class ProjectPaths {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();

    public static final Path OUTPUT_DIRECTORY = PROJECT_ROOT.resolve("output");

    private ProjectPaths() {}
}
