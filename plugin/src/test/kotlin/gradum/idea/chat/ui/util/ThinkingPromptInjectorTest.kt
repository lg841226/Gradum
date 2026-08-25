/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingPromptInjectorTest.kt  2026-08-25 13:21:36 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.model.ThinkingLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    val guide: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.LOW)
    assertTrue(
      "LOW guide should ask the model to do something (think/consider), not just label reasoning",
      guide.contains(other = "briefly", ignoreCase = true) ||
        guide.contains(other = "note", ignoreCase = true) ||
        guide.contains(other = "constraints", ignoreCase = true)
    )
  }

  @Test
  fun `MEDIUM guide asks for a structured pre-answer pass without step-by-step`() {
    val guide: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.MEDIUM)

    assertTrue(
      "MEDIUM guide should mention approach or failure modes",
      guide.contains(other = "approach", ignoreCase = true) ||
        guide.contains(other = "failure", ignoreCase = true)
    )
    assertFalse(
      "MEDIUM guide should not yet demand step-by-step (that's HIGH)",
      guide.contains(other = "step-by-step", ignoreCase = true) ||
        guide.contains(other = "step by step", ignoreCase = true)
    )
  }

  @Test
  fun `HIGH guide explicitly asks for step-by-step reasoning`() {
    val guide: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.HIGH)

    assertTrue(
      "HIGH guide should request explicit step-by-step reasoning",
      guide.contains(other = "step", ignoreCase = true)
    )
  }

  @Test
  fun `guides are strictly ordered in length LOW then MEDIUM then HIGH`() {
    val low: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.LOW)
    val medium: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.MEDIUM)
    val high: String = ThinkingPromptInjector.guideFor(level = ThinkingLevel.HIGH)
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
