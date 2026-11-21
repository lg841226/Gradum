package gradum.mode

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class ModeDirectoryScanner(
  val modesDirectory: File = ModeRegistry.userModesDir()
) {

  private val logger: Logger = LoggerFactory.getLogger("ModeDirectoryScanner")

  fun scan() {
    if (!modesDirectory.exists()) {
      modesDirectory.mkdirs()
      writeStarterMode(modesDirectory)
      logger.info("Created modes directory at {}", modesDirectory.absolutePath)
      return
    }
    reconcile()
  }

  fun reconcile() {
    ModeRegistry.reload()
  }

  fun currentFingerprint(): String =
    listModeFiles().joinToString(separator = "|") { file: File ->
      "${file.name}:${file.lastModified()}:${file.length()}"
    }

  private fun listModeFiles(): List<File> {
    val entries: Array<File> = modesDirectory.listFiles() ?: return emptyList()
    return entries.asSequence()
      .filter { entry -> entry.isFile && (entry.extension == "json" || entry.extension == "xml") }
      .sortedBy { entry: File -> entry.name }
      .toList()
  }

  private fun writeStarterMode(modesDirectory: File) {
    val starterJson = modesDirectory.resolve("review.json")
    if (!starterJson.exists()) starterJson.writeText(REVIEW_MODE_TEMPLATE, Charsets.UTF_8)
    val starterXml = modesDirectory.resolve("review.xml")
    if (!starterXml.exists()) starterXml.writeText(REVIEW_PROMPT_TEMPLATE, Charsets.UTF_8)
  }

  companion object {
    internal val REVIEW_MODE_TEMPLATE: String = """
{
  "id": "review",
  "label": "Code Review",
  "description": "Reads the diff, runs tests, posts findings; never edits",
  "aliases": ["cr"],
  "tools": {
    "allow": ["*"],
    "deny": ["write_file", "to_do", "finish_to_do_item", "${'$'}mcp"]
  },
  "gates": {
    "approvals": true,
    "commandFilter": false,
    "protectedPaths": true
  },
  "prompt": "review"
}
""".trimIndent() + "\n"

    internal val REVIEW_PROMPT_TEMPLATE: String = """
<System>
  <Identity>
      You are a senior reviewer. Your output is a verdict with evidence:
      you read the change, run the tests, and report findings. You never
      edit files yourself; fixing is the author's job.
  </Identity>
  <HowYouWork>
      1. Read.   Start from the diff or the files named by the user; read
                 the surrounding code before judging it.
      2. Verify. Run the project's tests or targeted checks with run_cmd;
                 cite the command and its outcome as evidence.
      3. Judge.  Separate blocking defects from nits; every finding names
                 the file, the line and the concrete reason it matters.
      4. Deliver. End with one verdict: approve, approve with nits, or
                 request changes with a short numbered list.
  </HowYouWork>
  <Constraints>
      Ask for clarification if the review target is unclear.
      Never edit files: you review; applying fixes belongs to an edit mode.
      If the user asks you to fix something yourself, report the finding and
      say which mode they should switch to.
  </Constraints>
  <Workflow>
      One review, one answer. No task lists.
      A result of success:false means the tool failed. Acknowledge it.
      Don't narrate tool calls. Speak only at milestones or final result.
      Reply in plain language. Never paste raw tool output.
  </Workflow>
</System>
""".trimIndent()
  }
}
