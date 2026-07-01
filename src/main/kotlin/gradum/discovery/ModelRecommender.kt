/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelRecommender.kt  2026-07-01 12:45:00 Changed by gwy
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
            val osBean: OperatingSystemMXBean =
                ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val freeBytes: Long = osBean.freeMemorySize
            val availableGB: Double =
                freeBytes.toDouble() / 1024.0 / 1024.0 / 1024.0 * FREE_RAM_HEADROOM_FACTOR
            return RecommendationContext(availableGB)
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
    context: RecommendationContext = RecommendationContext.fromSystemMemory(),
): ModelEntry? {
    if (models.isEmpty()) return null
    return models.maxByOrNull { score(it, context) }
}

/**
 * Per-model score. Higher = more recommended.
 *
 *  1. Context window: the universal baseline, capped at 200K so a
 *     single 1M-token outlier can't dominate every comparison.
 *  2. Cloud preference: 200-point flat bonus because cloud models are
 *     strictly stronger than the local options the user has today, by
 *     design (see project memory: "专注于本地, 但本地模型性能距离
 *     云端模型还是有些差距").
 *  3. Local parameter count: `paramsB * 1.5` so a 70B beats a 7B
 *     decisively, but not so much that it drowns out a cloud candidate
 *     with better context or capability flags.
 *  4. Memory gate: a local model whose estimated VRAM exceeds the
 *     available headroom is hit with a -500 penalty. This is large
 *     enough to demote it below any plausible cloud candidate and any
 *     smaller local alternative, but it stays selectable (so a user
 *     who insists on the big model still can). A softer "warning"
 *     tier was tried and rejected — see the implementation.
 *  5. Capability flags: small bonuses so a reasoning / tool-calling
 *     / vision model outranks an otherwise-identical sibling.
 */
internal fun score(model: ModelEntry, context: RecommendationContext): Double {
    var s: Double = 0.0

    s += minOf(model.contextLimit, 200_000) / 2_000.0

    if (isCloud(model)) {
        s += 200.0
    } else {
        val paramsB: Double = extractedParamsB(model)
        s += paramsB * 1.5

        // Binary memory gate: a local model that cannot fit in the
        // current headroom is hard-demoted (-500) so it loses to
        // every cloud candidate and every smaller local alternative.
        // A softer "warning" tier was tried and rejected — it tipped
        // the ranking on borderline cases (e.g. 32B on a 32 GB
        // machine) and made the recommendation harder to reason
        // about without actually saving anyone from an OOM.
        val vramEstimate: Double = paramsB * 0.8
        if (vramEstimate > context.availableRamGB) s -= 500.0
    }

    if (model.reasoning) s += 20.0
    if (model.toolCall) s += 10.0
    if (model.attachment) s += 5.0

    return s
}

/**
 * A model is treated as cloud if its name carries a `cloud` suffix
 * (the project convention — see `ModelSelectorBar.isCloud`) or if it
 * came from a server we don't recognise as a local runtime. The
 * second check is the safety net for upstream models.dev entries that
 * don't follow the naming convention.
 */
private fun isCloud(model: ModelEntry): Boolean {
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
fun extractedParamsB(model: ModelEntry): Double {
    val match: MatchResult? = Regex("""(\d+(?:\.\d+)?)b""", RegexOption.IGNORE_CASE)
        .find(model.modelName)
    return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}
