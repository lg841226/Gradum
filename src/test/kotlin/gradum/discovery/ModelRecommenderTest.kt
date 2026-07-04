/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelRecommenderTest.kt  2026-07-01 12:45:00 Changed by gwy
 */

package gradum.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRecommenderTest {

    private fun cloudModel(name: String, context: Int = 128_000, reasoning: Boolean = false, toolCall: Boolean = false): ModelEntry =
        ModelEntry(
            modelName = name,
            providerType = "openai",
            serverUrl = "https://api.example.com",
            serverName = "ExampleCloud",
            contextLimit = context,
            reasoning = reasoning,
            toolCall = toolCall,
        )

    private fun localModel(
        name: String,
        paramsB: Double? = null,
        context: Int = 32_000,
        reasoning: Boolean = false,
        toolCall: Boolean = false,
    ): ModelEntry {
        // Compose "<name>-<X>b" when paramsB is given; the regex under
        // test (parseParamsB) only matches a trailing "<digits>b"
        // token, so a helper that forgets the literal `b` is a
        // guaranteed failure.
        val resolvedName: String = if (paramsB == null) {
            name
        } else {
            val bLabel: String = if (paramsB % 1.0 == 0.0) "${paramsB.toInt()}b" else "${paramsB}b"
            if (name.endsWith(bLabel)) name else "$name-$bLabel"
        }
        return ModelEntry(
            modelName = resolvedName,
            providerType = "ollama",
            serverUrl = "http://localhost:11434",
            serverName = "Ollama",
            contextLimit = context,
            reasoning = reasoning,
            toolCall = toolCall,
        )
    }

    @Test
    fun `empty model list returns null`() {
        val result: ModelEntry? = recommend(emptyList(), RecommendationContext(availableRamGB = 16.0))
        assertNull(result)
    }

    @Test
    fun `cloud model wins over local when both are available`() {
        val models: List<ModelEntry> = listOf(
            localModel("qwen", paramsB = 7.0),
            cloudModel("gpt-4o"),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("gpt-4o", result?.modelName)
    }

    @Test
    fun `larger local model wins when memory is sufficient`() {
        // 32B * 0.8 = 25.6 GB; headroom 32 GB -> safe
        val models: List<ModelEntry> = listOf(
            localModel("qwen", paramsB = 7.0),
            localModel("qwen", paramsB = 32.0),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 32.0))
        assertEquals("qwen-32b", result?.modelName)
    }

    @Test
    fun `oversized local model is demoted below smaller alternatives`() {
        // 32B * 0.8 = 25.6 GB; headroom 16 GB -> -500 penalty
        val models: List<ModelEntry> = listOf(
            localModel("qwen", paramsB = 32.0),
            localModel("qwen", paramsB = 7.0),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("qwen-7b", result?.modelName)
    }

    @Test
    fun `cloud suffix in name overrides local server classification`() {
        // A model on Ollama but with `:cloud` in its name should still
        // be treated as cloud — that's the project convention.
        val models: List<ModelEntry> = listOf(
            ModelEntry(
                modelName = "minimax-m2.5:cloud",
                providerType = "ollama",
                serverUrl = "http://localhost:11434",
                serverName = "Ollama",
            ),
            localModel("qwen", paramsB = 7.0),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("minimax-m2.5:cloud", result?.modelName)
    }

    @Test
    fun `unknown server name is treated as cloud`() {
        // If a future plugin adds "TogetherAI" or similar, we want the
        // recommender to treat it as cloud rather than guessing a
        // memory budget the user can't audit.
        val models: List<ModelEntry> = listOf(
            ModelEntry("llama-3-70b", "openai", "https://api.together.xyz", "TogetherAI"),
            localModel("qwen", paramsB = 7.0),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("llama-3-70b", result?.modelName)
    }

    @Test
    fun `larger context beats smaller context among same-tier cloud models`() {
        val models: List<ModelEntry> = listOf(
            cloudModel("gpt-3.5-turbo", context = 16_000),
            cloudModel("gpt-4o", context = 128_000),
        )
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("gpt-4o", result?.modelName)
    }

    @Test
    fun `reasoning and tool-call capability flags break ties`() {
        val plain: ModelEntry = cloudModel("plain-model")
        val capable: ModelEntry = cloudModel("capable-model", reasoning = true, toolCall = true)
        val models: List<ModelEntry> = listOf(plain, capable)
        val result: ModelEntry? = recommend(models, RecommendationContext(availableRamGB = 16.0))
        assertEquals("capable-model", result?.modelName)
    }

    @Test
    fun `parseParamsB parses integer size suffix`() {
        val model: ModelEntry = localModel("qwen", paramsB = 32.0)
        assertEquals(32.0, parseParamsB(model), 0.001)
    }

    @Test
    fun `parseParamsB parses decimal size suffix`() {
        val model: ModelEntry = localModel("qwen", paramsB = 0.5)
        assertEquals(0.5, parseParamsB(model), 0.001)
    }

    @Test
    fun `parseParamsB returns zero when no size suffix is present`() {
        val model: ModelEntry = localModel("qwen2.5", paramsB = null)
        assertEquals(0.0, parseParamsB(model), 0.001)
    }

    @Test
    fun `recommendation context default-from-system is positive on a real machine`() {
        // Smoke test: the JVM is alive and reports some memory. We
        // only assert positivity so the test stays stable across CI
        // runners with different RAM budgets.
        val ctx: RecommendationContext = RecommendationContext.fromSystemMemory()
        assertTrue(ctx.availableRamGB > 0.0, "expected positive RAM headroom, got ${ctx.availableRamGB}")
    }
}
