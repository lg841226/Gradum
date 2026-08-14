/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingPromptInjectorTest.kt  2026-08-14 14:10:00 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.model.ThinkingLevel
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ThinkingPromptInjector.guideFor]. The injector is the
 * single point where the plugin's [ThinkingLevel] becomes text on the
 * wire; its output must be deterministic, locale-independent (the LLM
 * downstream is English-trained), and strictly ordered in strength.
 */
class ThinkingPromptInjectorTest {

  @Test
  fun `every level returns a non-empty guide`() {
    // The 3-level redesign (Low / Medium / High) removed the explicit
    // "off" level — every user-facing level now injects a hint, so the
    // call site never needs to null-check. The return type stays
    // non-nullable String and the call site can drop the `?.let { }`
    // branch.
    ThinkingLevel.entries.forEach { level ->
      val guide: String = ThinkingPromptInjector.guideFor(level)
      assertTrue(
        "guide for $level should be non-blank, got '$guide'",
        guide.isNotBlank()
      )
    }
  }

  @Test
  fun `LOW guide asks the model to briefly note constraints`() {
    val guide: String = ThinkingPromptInjector.guideFor(ThinkingLevel.LOW)
    assertTrue(
      "LOW guide should ask the model to do something (think/consider), not just label reasoning",
      guide.contains("briefly", ignoreCase = true) ||
        guide.contains("note", ignoreCase = true) ||
        guide.contains("constraints", ignoreCase = true)
    )
  }

  @Test
  fun `MEDIUM guide asks for a structured pre-answer pass without step-by-step`() {
    val guide: String = ThinkingPromptInjector.guideFor(ThinkingLevel.MEDIUM)
    // MEDIUM should ask the model to think about *approach* and *failure modes*,
    // but should not yet demand explicit step-by-step reasoning — that's
    // the HIGH differentiator. If a future refactor widens MEDIUM into HIGH
    // territory, this test fails and the regression is visible.
    assertTrue(
      "MEDIUM guide should mention approach or failure modes",
      guide.contains("approach", ignoreCase = true) ||
        guide.contains("failure", ignoreCase = true)
    )
    assertFalse(
      "MEDIUM guide should not yet demand step-by-step (that's HIGH)",
      guide.contains("step-by-step", ignoreCase = true) ||
        guide.contains("step by step", ignoreCase = true)
    )
  }

  @Test
  fun `HIGH guide explicitly asks for step-by-step reasoning`() {
    val guide: String = ThinkingPromptInjector.guideFor(ThinkingLevel.HIGH)
    // "step-by-step" is the canonical CoT trigger; if a future refactor
    // drops it the test fails, which is the desired signal.
    assertTrue(
      "HIGH guide should request explicit step-by-step reasoning",
      guide.contains("step", ignoreCase = true)
    )
  }

  @Test
  fun `guides are strictly ordered in length LOW then MEDIUM then HIGH`() {
    // Sanity check: the three levels are not arbitrary; each must
    // demonstrably contain more steering than the previous. If a future
    // refactor accidentally flattens the guides, this test catches it.
    val low: String = ThinkingPromptInjector.guideFor(ThinkingLevel.LOW)
    val medium: String = ThinkingPromptInjector.guideFor(ThinkingLevel.MEDIUM)
    val high: String = ThinkingPromptInjector.guideFor(ThinkingLevel.HIGH)
    assertTrue(
      "MEDIUM length=${medium.length} should exceed LOW length=${low.length}",
      medium.length > low.length
    )
    assertTrue(
      "HIGH length=${high.length} should exceed MEDIUM length=${medium.length}",
      high.length > medium.length
    )
  }
}
