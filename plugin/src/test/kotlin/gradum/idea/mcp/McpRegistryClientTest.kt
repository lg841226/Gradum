package gradum.idea.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture

class McpRegistryClientTest {

  private val fixture: String = """
    {
      "servers": [
        {
          "server": {
            "name": "io.example/filesystem",
            "description": "Filesystem tools",
            "version": "1.2.3",
            "repository": { "url": "https://github.com/example/fs-mcp", "source": "github" },
            "_meta": {
              "io.modelcontextprotocol.registry/official": {
                "status": "active",
                "publishedAt": "2026-06-26T14:07:53.723164Z",
                "isLatest": true
              }
            },
            "packages": [
              {
                "registryType": "npm",
                "identifier": "@example/fs-mcp",
                "version": "1.0.0",
                "runtimeHint": "npx",
                "runtimeArguments": [ { "type": "positional", "value": "-y" } ],
                "packageArguments": [
                  { "type": "positional", "value": "mcp" },
                  { "type": "named", "name": "--transport", "value": "stdio" },
                  { "type": "named", "name": "--token" }
                ],
                "transport": { "type": "stdio" },
                "environmentVariables": [
                  { "name": "API_KEY", "isRequired": true, "isSecret": true },
                  { "name": "PORT", "default": "8080" },
                  { "name": "EXTRA" }
                ]
              }
            ]
          }
        },
        {
          "server": {
            "name": "com.example/remote-only",
            "description": "Remote transport only",
            "remotes": [ { "type": "streamable-http", "url": "https://mcp.example.com" } ]
          }
        },
        {
          "server": {
            "name": "org.tools/pysearch",
            "description": "Python search",
            "packages": [
              {
                "registryType": "pypi",
                "identifier": "pysearch-mcp",
                "version": "2.0.0",
                "transport": { "type": "stdio" }
              }
            ]
          }
        }
      ],
      "metadata": { "count": 3 }
    }
  """.trimIndent()

  @Test
  fun `parse builds stdio command and env hints from a package`() {
    val results: List<McpRegistryResult> = McpRegistryClient.parse(json = fixture)

    assertEquals(3, results.size)
    assertEquals(
      listOf("io.example/filesystem", "org.tools/pysearch", "com.example/remote-only"),
      results.map { it.name }
    )
    val first: McpRegistryResult = results[0]
    assertEquals("io.example/filesystem", first.name)
    assertEquals("filesystem", first.shortName)
    assertEquals("Filesystem tools", first.description)
    assertEquals(
      listOf("npx", "-y", "@example/fs-mcp", "mcp", "--transport", "stdio"),
      first.command
    )
    assertEquals(
      listOf(McpRegistryEnvHint("API_KEY", ""), McpRegistryEnvHint("PORT", "8080")),
      first.envHints
    )
    assertEquals("1.2.3", first.version)
    assertEquals("@example/fs-mcp@1.0.0", first.packageLine)
    assertEquals("https://github.com/example/fs-mcp", first.repositoryUrl)
    assertEquals("npm", first.registryType)
    assertEquals("2026-06", first.publishedAt)
  }

  @Test
  fun `parse keeps remote-only servers but marks them unimportable`() {
    val results: List<McpRegistryResult> = McpRegistryClient.parse(json = fixture)

    val remote: McpRegistryResult = results[2]
    assertEquals("remote-only", remote.shortName)
    assertNull(remote.command)
    assertTrue(remote.envHints.isEmpty())
    assertNull(remote.version)
    assertNull(remote.packageLine)
    assertNull(remote.registryType)
    assertNull(remote.repositoryUrl)
    assertNull(remote.publishedAt)
  }

  @Test
  fun `parse infers uvx for pypi packages without a runtime hint`() {
    val results: List<McpRegistryResult> = McpRegistryClient.parse(json = fixture)

    assertEquals(listOf("uvx", "pysearch-mcp"), results[1].command)
    assertEquals("pysearch-mcp@2.0.0", results[1].packageLine)
    assertEquals("pypi", results[1].registryType)
    assertNull(results[1].version)
  }

  @Test
  fun `parse tolerates a body without servers or with nameless entries`() {
    assertTrue(McpRegistryClient.parse(json = """{ "metadata": {} }""").isEmpty())
    assertTrue(
      McpRegistryClient.parse(json = """{ "servers": [ { "description": "x" }, 42 ] }""").isEmpty()
    )
  }

  @Test
  fun `search url encodes the query and pins latest versions`() {
    assertEquals(
      "https://registry.modelcontextprotocol.io/v0.1/servers" +
        "?search=foo+bar%26baz&limit=20&version=latest",
      McpRegistryClient.searchUrl(query = "  foo bar&baz  ")
    )
  }

  @Test
  fun `parse picks the stdio package when transports are mixed`() {
    val json: String = """
      { "servers": [ { "server": {
        "name": "org.tools/mixed",
        "version": "   ",
        "packages": [
          { "registryType": "npm", "identifier": "web-only", "version": "1.0.0",
            "transport": { "type": "streamable-http" } },
          { "registryType": "pypi", "identifier": "cli-mcp", "version": "2.1.0",
            "transport": { "type": "stdio" } }
        ]
      } } ] }
    """.trimIndent()

    val result: McpRegistryResult = McpRegistryClient.parse(json = json).single()

    assertEquals(listOf("uvx", "cli-mcp"), result.command)
    assertEquals("cli-mcp@2.1.0", result.packageLine)
    assertEquals("pypi", result.registryType)
    assertNull(result.version)
  }

  @Test
  fun `parse shows meta for a non-stdio package but keeps it unimportable`() {
    val json: String = """
      { "servers": [ { "server": {
        "name": "com.example/web",
        "description": "Web only",
        "packages": [
          { "registryType": "npm", "identifier": "web-mcp", "version": "3.0.0",
            "transport": { "type": "streamable-http" } }
        ]
      } } ] }
    """.trimIndent()

    val result: McpRegistryResult = McpRegistryClient.parse(json = json).single()

    assertNull(result.command)
    assertEquals("web-mcp@3.0.0", result.packageLine)
    assertEquals("npm", result.registryType)
  }

  @Test
  fun `parse infers runners per registry and drops unknown ones`() {
    val json: String = """
      { "servers": [
        { "server": { "name": "a/oci", "packages": [
          { "registryType": "oci", "identifier": "img", "version": "1.0.0",
            "transport": { "type": "stdio" } } ] } },
        { "server": { "name": "a/nuget", "packages": [
          { "registryType": "nuget", "identifier": "tool", "version": "1.0.0",
            "transport": { "type": "stdio" } } ] } },
        { "server": { "name": "a/maven", "packages": [
          { "registryType": "maven", "identifier": "mvn-thing", "version": "1.0.0",
            "transport": { "type": "stdio" } } ] } }
      ] }
    """.trimIndent()

    val results: List<McpRegistryResult> = McpRegistryClient.parse(json = json)

    assertEquals(listOf("docker", "img"), results[0].command)
    assertEquals(listOf("dnx", "tool"), results[1].command)
    assertNull(results[2].command)
    assertEquals("mvn-thing@1.0.0", results[2].packageLine)
  }

  @Test
  fun `parse honors an explicit runtime hint over registry inference`() {
    val json: String = """
      { "servers": [ { "server": { "name": "a/bun",
        "packages": [
          { "registryType": "npm", "identifier": "bun-mcp", "runtimeHint": "bunx",
            "runtimeArguments": [ { "type": "positional", "value": "--silent" } ],
            "transport": { "type": "stdio" } } ] } } ] }
    """.trimIndent()

    val result: McpRegistryResult = McpRegistryClient.parse(json = json).single()

    assertEquals(listOf("bunx", "--silent", "bun-mcp"), result.command)
  }

  @Test
  fun `parse resolves env hint defaults by requirement`() {
    val json: String = """
      { "servers": [ { "server": { "name": "a/env", "packages": [
        { "registryType": "npm", "identifier": "env-mcp", "transport": { "type": "stdio" },
          "environmentVariables": [
            { "name": "WITH_DEFAULT", "isRequired": true, "default": "fallback" },
            { "name": "REQUIRED", "isRequired": true },
            { "name": "OPTIONAL_DEFAULT", "default": "opt" },
            { "name": "OPTIONAL", "isRequired": false },
            { "name": "", "isRequired": true }
          ] } ] } } ] }
    """.trimIndent()

    val result: McpRegistryResult = McpRegistryClient.parse(json = json).single()

    assertEquals(
      listOf(
        McpRegistryEnvHint(name = "WITH_DEFAULT", value = "fallback"),
        McpRegistryEnvHint(name = "REQUIRED", value = ""),
        McpRegistryEnvHint(name = "OPTIONAL_DEFAULT", value = "opt")
      ),
      result.envHints
    )
  }

  @Test
  fun `parse deduplicates repeated names keeping the first occurrence`() {
    val json: String = """
      { "servers": [
        { "server": { "name": "dup/name", "description": "first" } },
        { "server": { "name": "dup/name", "description": "second" } }
      ] }
    """.trimIndent()

    val results: List<McpRegistryResult> = McpRegistryClient.parse(json = json)

    assertEquals(1, results.size)
    assertEquals("first", results.single().description)
  }

  @Test
  fun `awaitSuccess takes the first success even after a failed attempt`() {
    val failed: CompletableFuture<List<McpRegistryResult>> =
      CompletableFuture<List<McpRegistryResult>>().apply {
        completeExceptionally(IOException("boom"))
      }
    val ok: CompletableFuture<List<McpRegistryResult>> =
      CompletableFuture<List<McpRegistryResult>>().apply { complete(emptyList()) }

    val result: List<McpRegistryResult> =
      McpRegistryClient.awaitSuccess(attempts = listOf(failed, ok))

    assertTrue(result.isEmpty())
  }

  @Test
  fun `awaitSuccess rethrows the first failure when every attempt fails`() {
    val first: CompletableFuture<List<McpRegistryResult>> =
      CompletableFuture<List<McpRegistryResult>>().apply {
        completeExceptionally(IOException("boom"))
      }
    val second: CompletableFuture<List<McpRegistryResult>> =
      CompletableFuture<List<McpRegistryResult>>().apply {
        completeExceptionally(IOException("bust"))
      }

    try {
      McpRegistryClient.awaitSuccess(attempts = listOf(first, second))
      fail("expected the first failure to be rethrown")
    } catch (e: IOException) {
      assertEquals("boom", e.message)
    }
  }
}
