/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProjectPaths.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum

import java.nio.file.Path

private val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
private val packageRoot: Path = projectRoot

val OUTPUT_DIRECTORY: Path = projectRoot.resolve("output")
val PROMPTS_DIRECTORY: Path = packageRoot.resolve("prompts")
