package gradum.skill

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val logger: Logger = LoggerFactory.getLogger("BackgroundScope")
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

private val backgroundHttpClient: HttpClient = HttpClient {
  install(HttpTimeout) {
    connectTimeoutMillis = 5_000L
    socketTimeoutMillis = 10_000L
    requestTimeoutMillis = 15_000L
  }
}

/**
 * Handle to a background task submitted via [BackgroundScope]. The [taskId]
 * is available immediately after submission; [await] blocks until the task
 * reaches a terminal state.
 */
class BackgroundHandle(
  val taskId: String,
  val sessionId: String,
  private val baseUrl: String,
  private val apiKey: String?,
) {
  fun await(pollIntervalMs: Long = 1000L): JobOutcomeDto {
    while (true) {
      val outcome = fetchOutcome()
      if (outcome != null) return outcome
      runBlocking { delay(pollIntervalMs.milliseconds) }
    }
  }

  private fun fetchOutcome(): JobOutcomeDto? {
    return try {
      val response = runBlocking {
        backgroundHttpClient.get("$baseUrl/tasks/$taskId") {
          if (apiKey != null) header("Authorization", "Bearer $apiKey")
        }
      }

      if (response.status != HttpStatusCode.OK) {
        logger.warn("Failed to fetch task {} status: HTTP {}", taskId, response.status.value)
        return null
      }

      val bodyText = runBlocking { response.bodyAsText() }
      val json = jsonParser.parseToJsonElement(bodyText).jsonObject

      val status = json["status"]?.jsonPrimitive?.contentOrNull ?: return null
      if (status == "running") return null

      JobOutcomeDto(
        taskId = taskId,
        status = status,
        exitCode = json["exitCode"]?.jsonPrimitive?.intOrNull ?: -1,
        output = json["output"]?.jsonPrimitive?.contentOrNull ?: "",
        durationMs = json["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
      )
    } catch (pollException: Exception) {
      logger.warn("Failed to poll task {}: {}", taskId, pollException.message)
      null
    }
  }
}

/** Terminal outcome DTO returned by [BackgroundHandle.await]. */
data class JobOutcomeDto(
  val taskId: String,
  val status: String,
  val exitCode: Int,
  val output: String,
  val durationMs: Long,
)

/**
 * DSL scope for submitting background tasks. Configure via property assignment,
 * then the task is submitted when the lambda returns.
 */
class BackgroundScope internal constructor(
  private val sessionId: String,
  private val projectRoot: String,
  private val baseUrl: String,
  private val apiKey: String?,
) {
  var command: String = ""
  var timeout: Long = 600L

  internal fun submit(): BackgroundHandle {
    require(command.isNotBlank()) { "command must not be blank" }

    val response = runBlocking {
      backgroundHttpClient.post("$baseUrl/tasks") {
        contentType(ContentType.Application.Json)
        if (apiKey != null) header("Authorization", "Bearer $apiKey")
        setBody(
          buildJsonObject {
            put("sessionId", sessionId)
            put("command", command)
            put("projectRoot", projectRoot)
            put("timeoutSeconds", timeout)
          }.toString()
        )
      }
    }

    if (response.status != HttpStatusCode.Accepted) {
      val bodyText = runBlocking { response.bodyAsText() }
      throw IllegalStateException("Failed to submit background task: HTTP ${response.status.value} $bodyText")
    }

    val bodyText = runBlocking { response.bodyAsText() }
    val json = jsonParser.parseToJsonElement(bodyText).jsonObject
    val taskId = json["taskId"]?.jsonPrimitive?.contentOrNull
      ?: throw IllegalStateException("Missing taskId in response: $bodyText")

    return BackgroundHandle(
      taskId = taskId,
      sessionId = sessionId,
      baseUrl = baseUrl,
      apiKey = apiKey,
    )
  }
}

/**
 * Extension function on [SkillContext] to submit a background task via the
 * server's HTTP API. This keeps skill behavior consistent between built-in
 * and external skills: both go through the same HTTP endpoint.
 */
fun SkillContext.background(block: BackgroundScope.() -> Unit): BackgroundHandle {
  val serverUrl = this.serverUrl
    ?: throw IllegalStateException("Background tasks require a server URL; not available in this context")

  val scope = BackgroundScope(
    sessionId = this.agentConfiguration?.sessionId ?: "unknown",
    projectRoot = this.projectRoot,
    baseUrl = serverUrl,
    apiKey = this.serverApiKey,
  )
  scope.block()
  return scope.submit()
}
