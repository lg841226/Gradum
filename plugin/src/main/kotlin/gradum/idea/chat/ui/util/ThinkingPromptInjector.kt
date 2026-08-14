/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingPromptInjector.kt  2026-08-14 13:30:53 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.model.ThinkingLevel

/**
 * Builds the per-turn "thinking hint" that the plugin appends to the
 * outgoing user message based on the user's selected
 * [gradum.idea.chat.model.ThinkingLevel].
 *
 * **Why a plugin-side injector, not a server-side parameter.** The Gradum
 * server is dumb on purpose — it relays the request payload to whichever
 * LLM backend the model is hosted on. Adding a `reasoning_effort` /
 * `thinking` / `budget_tokens` parameter per provider would mean tracking
 * three incompatible wire formats that change on the upstream's schedule.
 * Prompt-injection lives entirely in user-typed text: it's stable across
 * providers, zero protocol cost, and degrades gracefully on a model that
 * ignores the hint. That is also why the dropdown is **always enabled**,
 * regardless of the model's catalog `reasoning` flag — the prompt works
 * on any model, just with different yields.
 *
 * **Why append, not prepend.** Models weight the most recently seen
 * instructions highest; prepending the hint buries it under the user's
 * actual question and the existing `<Rule>` block, where it gets treated
 * as background. Appending puts the hint right next to the question it's
 * steering.
 */
internal object ThinkingPromptInjector {

  /**
   * Suffix appended after the user message + `<Rule>` block. The wording
   * is deliberate: it asks the model to **think** (an action) not to
   * **say** "thinking" (a label). "Let me think step by step" is a
   * stronger trigger than "show your reasoning" — the latter often
   * produces decorative reasoning that just paraphrases the answer.
   *
   * Strength ordering is enforced by content length and the
   * strictness of the instructions; tests pin both.
   */
  fun guideFor(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.LOW ->
      "\n\nBefore answering, briefly note the key constraints and tradeoffs in 3~4 sentences."

    ThinkingLevel.MEDIUM ->
      "\n\nBefore answering, think through the main approach and its tradeoffs. " +
        "Identify the most likely failure modes and the simplest correct path, " +
        "then answer."

    ThinkingLevel.HIGH ->
      "\n\nThink carefully and step-by-step before responding. Consider edge cases " +
        "and alternative approaches, verify each step before committing to an answer, " +
        "and prefer the simplest correct solution over a clever one."
  }
}
