/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfigStoreTest.kt  2026-08-15  Changed by gwy
 */
package gradum

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderConfigStoreTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("gradum-provider-env").toFile()
        System.setProperty("gradum.provider.configDir", tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("gradum.provider.configDir")
        tempDir.deleteRecursively()
    }

    @Test
    fun `load returns empty props when file is missing`() {
        assertTrue(ProviderConfigStore.load().isEmpty)
    }

    @Test
    fun `load parses hand-written env file`() {
        File(tempDir, "provider.env").writeText(
            """
            GRADUM_OLLAMA_BASE_URL=http://192.168.1.50:11434
            GRADUM_DEEPSEEK_API_KEY=sk-123
            # comment line is fine
            """.trimIndent()
        )

        val props = ProviderConfigStore.load()
        assertEquals("http://192.168.1.50:11434", props.getProperty("GRADUM_OLLAMA_BASE_URL"))
        assertEquals("sk-123", props.getProperty("GRADUM_DEEPSEEK_API_KEY"))
    }

    @Test
    fun `base url and api key keys are derived from configKey`() {
        assertEquals("GRADUM_OLLAMA_BASE_URL", ProviderConfigStore.baseUrlKey("ollama"))
        assertEquals("GRADUM_LMSTUDIO_API_KEY", ProviderConfigStore.apiKeyKey("lmstudio"))
        assertEquals(null, ProviderConfigStore.baseUrlKey(null))
    }

    @Test
    fun `empty config probes no providers (no localhost fallback)`() {
        // The env dir is empty, so no provider has a configured base URL.
        // doProbe must skip every provider (returning no model entries)
        // rather than falling back to probing the default localhost address.
        val entries = ModelIdentity.discoverModels()
        assertTrue(entries.isEmpty(), "expected no model entries when config is empty")
    }

    @Test
    fun `fingerprint is stable for missing file and changes on edit`() {
        val missingFingerprint: String = ProviderConfigStore.fingerprint()
        assertEquals("missing", missingFingerprint)

        val configFile: File = File(tempDir, "provider.env")
        configFile.writeText("GRADUM_OLLAMA_BASE_URL=http://localhost:11434")
        val first: String = ProviderConfigStore.fingerprint()
        assertTrue(first != "missing")

        configFile.writeText("GRADUM_OLLAMA_BASE_URL=http://192.168.1.50:11434")
        val second: String = ProviderConfigStore.fingerprint()
        assertTrue(second != first, "fingerprint must change when the file content changes")
    }
}
