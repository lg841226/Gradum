package gradum.client

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probe/parse tests for [LmStudioModelCatalog] against a mocked HTTP engine.
 * Verifies the root-level `/api/v1/models` path is built from a base URL that
 * already carries the `/v1` OpenAI-compatible prefix (no `/v1/api/v1`
 * doubling), the `data[]` capability shape, and the conservative fallback.
 *
 * Each test uses a unique base URL: the catalog caches per server for the
 * process lifetime, so sharing one would leak state across tests.
 */
class LmStudioModelCatalogTest {

  private val samplePayload = """
    {
      "data": [
        {
          "id": "deepseek-r1-distill",
          "state": "loaded",
          "capabilities": {
            "vision": false,
            "trained_for_tool_use": true,
            "reasoning": {
              "allowed_options": ["off", "on", "low", "medium", "high"],
              "default": "on"
            }
          }
        },
        {
          "id": "qwen2.5-vl",
          "state": "not-loaded",
          "capabilities": {
            "vision": true,
            "trained_for_tool_use": false,
            "reasoning": {
              "allowed_options": [],
              "default": "off"
            }
          }
        }
      ]
    }
  """.trimIndent()

  private fun catalog(
    baseUrl: String,
    payload: String = samplePayload,
    status: HttpStatusCode = HttpStatusCode.OK
  ): Pair<LmStudioModelCatalog, () -> String> {
    var requestedUrl = ""
    val engine = MockEngine { request ->
      requestedUrl = request.url.toString()
      respond(
        content = ByteReadChannel(payload),
        status = status,
        headers = headersOf(name = HttpHeaders.ContentType, value = "application/json"),
      )
    }
    return LmStudioModelCatalog(baseUrl = baseUrl, httpClient = HttpClient(engine)) to { requestedUrl }
  }

  @Test
  fun `probes the root-level api path without doubling the v1 segment`() = runBlocking {
    val (catalog, requestedUrl) = catalog(baseUrl = "http://localhost:1234/v1")

    catalog.capabilitiesByModel()

    assertEquals(
      "http://localhost:1234/api/v1/models",
      requestedUrl(),
      "the /v1 OpenAI prefix must be stripped before the root-level REST path"
    )
  }

  @Test
  fun `probes the root-level api path when the base url has no v1 suffix`() = runBlocking {
    val (catalog, requestedUrl) = catalog(baseUrl = "http://localhost:1234")

    catalog.capabilitiesByModel()

    assertEquals("http://localhost:1234/api/v1/models", requestedUrl())
  }

  @Test
  fun `parses reasoning options vision tools and loaded state`() = runBlocking {
    val (catalog, _) = catalog(baseUrl = "http://mock-lmstudio-parse:1234/v1")

    val capabilities = catalog.capabilitiesFor(modelName = "deepseek-r1-distill")

    assertEquals(
      listOf("off", "on", "low", "medium", "high"),
      capabilities.reasoningOptions
    )
    assertTrue(capabilities.isLoaded)
    assertTrue(capabilities.supportsTools)
    assertFalse(capabilities.supportsVision)
  }

  @Test
  fun `a model without reasoning options reports no reasoning`() = runBlocking {
    val (catalog, _) = catalog(baseUrl = "http://mock-lmstudio-noreason:1234/v1")

    val capabilities = catalog.capabilitiesFor(modelName = "qwen2.5-vl")

    assertTrue(
      capabilities.reasoningOptions.isEmpty(),
      "missing/empty allowed_options means the model does not reason"
    )
    assertTrue(capabilities.supportsVision)
    assertFalse(capabilities.supportsTools)
    assertFalse(capabilities.isLoaded)
  }

  @Test
  fun `an unknown model degrades to the conservative fallback`() = runBlocking {
    val (catalog, _) = catalog(baseUrl = "http://mock-lmstudio-unknown:1234/v1")

    val capabilities = catalog.capabilitiesFor(modelName = "not-in-the-list")

    assertEquals("not-in-the-list", capabilities.modelName)
    assertTrue(capabilities.reasoningOptions.isEmpty())
    assertFalse(capabilities.supportsTools)
    assertFalse(capabilities.supportsVision)
    assertFalse(capabilities.isLoaded)
  }

  @Test
  fun `a probe failure degrades to the conservative fallback instead of throwing`() = runBlocking {
    val (catalog, _) = catalog(
      baseUrl = "http://mock-lmstudio-down:1234/v1",
      payload = "connection refused",
      status = HttpStatusCode.ServiceUnavailable,
    )

    assertTrue(catalog.capabilitiesByModel().isEmpty())

    val capabilities = catalog.capabilitiesFor(modelName = "gpt-oss-20b")
    assertEquals("gpt-oss-20b", capabilities.modelName)
    assertTrue(capabilities.reasoningOptions.isEmpty())
    assertFalse(capabilities.supportsTools)
  }

  @Test
  fun `an unparseable body degrades to the conservative fallback`() = runBlocking {
    val (catalog, _) = catalog(
      baseUrl = "http://mock-lmstudio-garbage:1234/v1",
      payload = "<html>not json</html>",
    )

    assertTrue(catalog.capabilitiesByModel().isEmpty())
  }

  private fun responsesProbeClient(
    status: HttpStatusCode,
    requestCount: AtomicInteger = AtomicInteger(),
  ): HttpClient {
    val engine = MockEngine { request ->
      requestCount.incrementAndGet()
      assertEquals("/v1/responses", request.url.encodedPath)
      respond(
        content = ByteReadChannel("{}"),
        status = status,
        headers = headersOf(name = HttpHeaders.ContentType, value = "application/json"),
      )
    }
    return HttpClient(engine)
  }

  @Test
  fun `a 2xx responses probe reports the endpoint as available`() = runBlocking {
    val available = probeResponsesAvailability(
      baseUrl = "http://mock-lmstudio-responses-ok:1234/v1",
      httpClient = responsesProbeClient(status = HttpStatusCode.OK),
    )

    assertTrue(available)
  }

  @Test
  fun `a 404 responses probe reports the endpoint as unavailable`() = runBlocking {
    val available = probeResponsesAvailability(
      baseUrl = "http://mock-lmstudio-responses-missing:1234/v1",
      httpClient = responsesProbeClient(status = HttpStatusCode.NotFound),
    )

    assertFalse(available)
  }

  @Test
  fun `concurrent probes for one server share a single request`() = runBlocking {
    val requestCount = AtomicInteger()
    val httpClient = responsesProbeClient(status = HttpStatusCode.OK, requestCount = requestCount)
    val baseUrl = "http://mock-lmstudio-responses-concurrent:1234/v1"

    val results = (1..8).map {
      async { probeResponsesAvailability(baseUrl = baseUrl, httpClient = httpClient) }
    }.awaitAll()

    assertTrue(results.all { isAvailable -> isAvailable })
    assertEquals(expected = 1, actual = requestCount.get())
  }
}
