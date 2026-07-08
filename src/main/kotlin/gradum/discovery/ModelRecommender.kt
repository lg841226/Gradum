/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelRecommender.kt  2026-07-05 22:56:12 Changed by gwy
 */

package gradum.discovery

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory

/**
 * Inputs the [recommend] algorithm needs beyond the candidate [ModelEntry]
 * list. Kept as a typed data class so the scoring function is pure and
 * trivially testable with a fixed memory budget — no need to mock the
 * JVM's [OperatingSystemMXBean] in unit tests.
 *
 * @property availableRamGB Free physical memory the local models may
 *   consume, in gibibytes. Already discounted for the OS/IDE overhead so
 *   callers should pass a realistic "headroom" figure rather than the
 *   raw `getFreeMemorySize()` value.
 */
data class RecommendationContext(val availableRamGB: Double) {
    companion object {
        /**
         * Reserve 25% of free physical memory for the OS, IDE, and
         * whatever else the developer has open. The remaining 75% is
         * what a local model is realistically allowed to consume before
         * we should stop recommending it.
         */
        private const val FREE_RAM_HEADROOM_FACTOR: Double = 0.75

        /**
         * Snapshot the current machine's free memory and apply the
         * headroom discount. Called per `/models` request so that a
         * user who just opened IntelliJ on top of a previous Gradum
         * session sees the smaller budget reflected in the next
         * recommendation.
         */
        fun fromSystemMemory(): RecommendationContext {
            val operatingSystemBean: OperatingSystemMXBean =
                ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val freeMemoryBytes: Long = operatingSystemBean.freeMemorySize
            val usableMemoryGB: Double =
                freeMemoryBytes.toDouble() / 1024.0 / 1024.0 / 1024.0 * FREE_RAM_HEADROOM_FACTOR
            return RecommendationContext(usableMemoryGB)
        }
    }
}

/**
 * Local servers that host models on the developer's own hardware. Used
 * to distinguish "Ollama / LM Studio / vLLM / LocalAI" — which the user
 * paid electricity for — from cloud-hosted APIs that the score should
 * promote regardless of local memory budget.
 */
private val localServerNames: Set<String> = setOf("Ollama", "LM Studio", "vLLM", "LocalAI")

/**
 * Pick the single model that best matches the developer's current
 * machine and the project's needs. Returns null for an empty list so
 * callers don't have to special-case "no models discovered".
 *
 * The scoring formula is documented inline on [score]; the short
 * version is: cloud wins on raw capability, and among local models
 * bigger parameter counts win — but only if the machine can actually
 * hold them.
 */
fun recommend(
    models: List<ModelEntry>,
    context: RecommendationContext = RecommendationContext.fromSystemMemory()
): ModelEntry? {
    if (models.isEmpty()) return null
    return models.maxByOrNull { score(it, context) }
}

/**
 * Per-model score. Higher = more recommended.
 *
 * 1. Context window: baseline, capped at 200K to prevent outliers from dominating.
 * 2. Cloud bonus: +200 for cloud models (strictly stronger than local).
 * 3. Local size: +1.5 per billion params (70B beats 7B, but doesn't drown out cloud).
 * 4. Memory gate: -500 if estimated VRAM exceeds available memory.
 *    Drops it below all cloud and smaller local models, but keeps it selectable.
 *    Softer warning tier was rejected (caused ambiguous rankings without preventing OOM).
 * 5. Capability bonuses: +20 reasoning, +10 tool-call, +5 vision.
 */
internal fun score(model: ModelEntry, context: RecommendationContext): Double {
    var modelScore = 0.0

    modelScore += minOf(model.contextLimit, 200_000) / 2_000.0

    if (isCloudModel(model)) {
        modelScore += 200.0
    } else {
        val parameterCountInBillions: Double = parameterCountInBillions(model)
        modelScore += parameterCountInBillions * 1.5

        // Local models that exceed available memory get -500, dropping them below all cloud and smaller local alternatives.
        // A softer warning tier was tried but caused ambiguous rankings (e.g. 32B on 32GB) without preventing OOM.
        val estimatedMemoryGB: Double = parameterCountInBillions * 0.8
        if (estimatedMemoryGB > context.availableRamGB) modelScore -= 500.0
    }

    if (model.reasoning) modelScore += 20.0
    if (model.toolCall) modelScore += 10.0
    if (model.attachment) modelScore += 5.0

    return modelScore
}

/**
 * A model is treated as cloud if its name carries a `cloud` suffix
 * (the project convention — see `ModelSelectorBar.isCloudModel`) or if it
 * came from a server we don't recognize as a local runtime. The
 * second check is the safety net for upstream models.dev entries that
 * don't follow the naming convention.
 */
private fun isCloudModel(model: ModelEntry): Boolean {
    if (model.modelName.contains("cloud", ignoreCase = true)) return true
    return model.serverName !in localServerNames
}

/**
 * Pull the parameter count (in billions) out of the model name.
 * Handles integers (`70b`), decimals (`1.5b`), and ignores tokens
 * without a `b` suffix (`qwen2.5` → 0.0, which is fine because
 * non-cloud models without a size tag fall to the bottom of the
 * local ranking by design).
 */
fun parameterCountInBillions(model: ModelEntry): Double {
    val parameterMatch: MatchResult? = Regex(pattern = """(\d+(?:\.\d+)?)b""", option = RegexOption.IGNORE_CASE)
        .find(model.modelName)

    return parameterMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}
