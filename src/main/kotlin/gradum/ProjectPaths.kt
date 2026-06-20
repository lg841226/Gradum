package gradum

import java.nio.file.Path

private val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
private val packageRoot: Path = projectRoot

val PROJECT_DIRECTORY: Path = projectRoot
val OUTPUT_DIRECTORY: Path = projectRoot.resolve("output")
val PROMPTS_DIRECTORY: Path = packageRoot.resolve("prompts")
