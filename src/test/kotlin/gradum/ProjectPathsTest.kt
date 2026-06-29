package gradum

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProjectPathsTest {

    @AfterTest
    fun resetToCwd() {
        // Each test may override the project root; reset to CWD so the
        // next test (and the rest of the suite) starts from a known state.
        ProjectPaths.setProjectRoot(null)
    }

    @Test
    fun `default project root is the process CWD`() {
        val expected: Path = Paths.get("").toAbsolutePath().normalize()
        assertEquals(expected, ProjectPaths.getProjectRoot())
        assertEquals(expected.resolve(".gradum"), ProjectPaths.outputDirectory())
    }

    @Test
    fun `setProjectRoot overrides subsequent outputDirectory reads`() {
        val target: Path = Paths.get("/tmp/gradum-test-project-root").toAbsolutePath().normalize()
        ProjectPaths.setProjectRoot(target)
        assertEquals(target, ProjectPaths.getProjectRoot())
        assertEquals(target.resolve(".gradum"), ProjectPaths.outputDirectory())
    }

    @Test
    fun `relative input is resolved against the current CWD`() {
        val relative: Path = Paths.get(".").toAbsolutePath().normalize()
        ProjectPaths.setProjectRoot(Paths.get("."))
        assertEquals(relative, ProjectPaths.getProjectRoot())
    }

    @Test
    fun `passing null resets back to the process CWD`() {
        ProjectPaths.setProjectRoot(Paths.get("/some/where/else"))
        ProjectPaths.setProjectRoot(null)
        val cwd: Path = Paths.get("").toAbsolutePath().normalize()
        assertEquals(cwd, ProjectPaths.getProjectRoot())
    }
}
