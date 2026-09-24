/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 */

package gradum.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the ask_interaction channel's server-side primitives:
 * [PendingQuestions] parking/unblocking, the [AskScope]/[AskBuilder] block
 * DSL, and [L10nText] / [Choice.Meaning] / [Lang] wire encoding.
 */
class AskInteractionTest {

  // -- PendingQuestions ------------------------------------------------

  @Test
  fun `await parks a thread that unblocks with the response and cleans up`() {
    val pending = PendingQuestions()
    val holder = arrayOfNulls<AskResult>(1)
    val blocker = Thread { holder[0] = pending.await("s", "r") }
    blocker.start()
    waitUntil { pending.isPending("s", "r") }

    val delivered = pending.completeChoice("s", "r", "once")

    blocker.join(2000)
    assertFalse(blocker.isAlive, "await should unblock after completeChoice")
    assertEquals("once", assertIs<AskResult.Case>(delivered).id)
    assertEquals("once", assertIs<AskResult.Case>(holder[0]).id)
    assertEquals(0, pending.size(), "entry should be removed after delivery")
  }

  @Test
  fun `completeText and completeCancelled resolve with their typed results`() {
    val pending = PendingQuestions()
    val textHolder = arrayOfNulls<AskResult>(1)
    val textThread = Thread { textHolder[0] = pending.await("s", "r1") }
    textThread.start()
    waitUntil { pending.isPending("s", "r1") }
    val delivered = pending.completeText("s", "r1", "my input")
    textThread.join(2000)
    assertEquals("my input", assertIs<AskResult.Text>(delivered).value)
    assertEquals("my input", assertIs<AskResult.Text>(textHolder[0]).value)

    val cancelHolder = arrayOfNulls<AskResult>(1)
    val cancelThread = Thread { cancelHolder[0] = pending.await("s", "r2") }
    cancelThread.start()
    waitUntil { pending.isPending("s", "r2") }
    pending.completeCancelled("s", "r2")
    cancelThread.join(2000)
    assertIs<AskResult.Cancelled>(cancelHolder[0])
  }

  @Test
  fun `responding to an unknown or already resolved key returns null`() {
    val pending = PendingQuestions()
    assertNull(pending.completeChoice("nobody", "asked", "x"), "unknown key")
    assertNull(pending.completeText("nobody", "asked", "x"), "unknown key (text)")

    val blocker = Thread { pending.await("s", "r") }
    blocker.start()
    waitUntil { pending.isPending("s", "r") }
    pending.completeChoice("s", "r", "once")

    // Second delivery for the same key is a duplicate no-op.
    assertNull(pending.completeChoice("s", "r", "once"))
    assertNull(pending.completeCancelled("s", "r"))
    blocker.join(2000)
  }

  @Test
  fun `pending questions are isolated by session key`() {
    val pending = PendingQuestions()
    val holderA = arrayOfNulls<AskResult>(1)
    val holderB = arrayOfNulls<AskResult>(1)
    val threadA = Thread { holderA[0] = pending.await("sessionA", "r") }
    val threadB = Thread { holderB[0] = pending.await("sessionB", "r") }
    threadA.start()
    threadB.start()
    waitUntil { pending.isPending("sessionA", "r") && pending.isPending("sessionB", "r") }
    assertEquals(2, pending.size())

    pending.completeChoice("sessionA", "r", "once")
    threadA.join(2000)

    // Only sessionA was resolved; sessionB stays parked.
    assertTrue(pending.isPending("sessionB", "r"))
    assertEquals("once", assertIs<AskResult.Case>(holderA[0]).id)
    pending.completeText("sessionB", "r", "hi")
    threadB.join(2000)
    assertEquals("hi", assertIs<AskResult.Text>(holderB[0]).value)
  }

  // -- AskScope / AskBuilder DSL ---------------------------------------

  @Test
  fun `ask_interaction choices block emits a card then returns the picked case`() {
    val h = AskHarness()
    val (blocker, holder) = h.startAsk {
      title = l10n.key("gradum.ask.run_cmd.confirm")
      details = l10n.raw("rm -rf build/", source = Lang.EN)
      choices {
        item("once", Choice.Meaning.ALLOW_ONCE)
        item("always", Choice.Meaning.ALLOW_ALWAYS)
        item("no", Choice.Meaning.REJECT)
      }
      default = "no"
    }

    assertEquals(1, h.emitted.size)
    val (eventType, wire) = h.emitted.single()
    assertEquals("ask_interaction", eventType)
    assertTrue(h.requestId.isNotBlank(), "card must carry a requestId")

    h.pending.completeChoice(h.sessionId, h.requestId, "always")

    blocker.join(2000)
    assertEquals("always", assertIs<AskResult.Case>(holder[0]).id)
  }

  @Test
  fun `toWire choices preserve order, emit stable semantics, and default falls back to the first choice`() {
    val wire = AskBuilder().apply {
      title = L10n.raw("Proceed?")
      choices {
        item("once", Choice.Meaning.ALLOW_ONCE)
        item("always", Choice.Meaning.ALLOW_ALWAYS)
      }
    }.toWire(requestId = "r", sessionId = "s")

    @Suppress("UNCHECKED_CAST")
    val choices = wire["choices"] as List<Map<String, Any>>
    assertEquals(listOf("once", "always"), choices.map { it["id"] })
    assertEquals(listOf("allow_once", "allow_always"), choices.map { it["semantics"] })
    assertEquals("once", wire["default"], "default falls back to the first choice")
    assertEquals("s", wire["sessionId"])
    assertEquals("r", wire["requestId"])
    assertEquals("raw", assertIs<Map<String, Any>>(wire["title"])["kind"])
  }

  @Test
  fun `duplicate or blank choice id is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      AskBuilder().apply {
        title = L10n.raw("t")
        choices {
          item("once", Choice.Meaning.ALLOW_ONCE)
          item("once", Choice.Meaning.REJECT)
        }
      }.validate()
    }
    assertFailsWith<IllegalArgumentException> {
      AskBuilder().apply {
        title = L10n.raw("t")
        choices { item("", Choice.Meaning.ALLOW_ONCE) }
      }.validate()
    }
  }

  @Test
  fun `validate rejects missing flavor, mixed flavors, and missing title`() {
    assertFailsWith<IllegalArgumentException> { AskBuilder().validate() }
    assertFailsWith<IllegalArgumentException> {
      AskBuilder().apply {
        title = L10n.raw("t")
        choices { item("a", Choice.Meaning.REJECT) }
        input { placeholder = L10n.raw("p") }
      }.validate()
    }
    assertFailsWith<IllegalArgumentException> {
      AskBuilder().apply {
        choices { item("a", Choice.Meaning.REJECT) }
      }.validate()
    }
  }

  @Test
  fun `input flavor emits a placeholder and resolves to a text answer`() {
    val h = AskHarness()
    val (blocker, holder) = h.startAsk {
      title = l10n.key("gradum.ask.commit.title")
      default = "initial text"
      input { placeholder = l10n.raw("commit message…") }
    }

    val wire = h.emitted.single().second
    @Suppress("UNCHECKED_CAST")
    val inputWire = wire["input"] as Map<String, Any>
    assertEquals("raw", assertIs<Map<String, Any>>(inputWire["placeholder"])["kind"])
    assertEquals("initial text", wire["default"])

    h.pending.completeText(h.sessionId, h.requestId, "fix: typo")

    blocker.join(2000)
    assertEquals("fix: typo", assertIs<AskResult.Text>(holder[0]).value)
  }

  // -- L10nText wire ----------------------------------------------------

  @Test
  fun `L10nText key wire carries kind, key and args`() {
    val wire = L10n.key("gradum.ask.commit.title").toWire()
    assertEquals("key", wire["kind"])
    assertEquals("gradum.ask.commit.title", wire["key"])
    assertEquals(listOf<String>(), wire["args"])
  }

  @Test
  fun `L10nText raw wire carries source language tag`() {
    val wire = L10n.raw("rm -rf build/", source = Lang.EN).toWire()
    assertEquals("raw", wire["kind"])
    assertEquals("rm -rf build/", wire["text"])
    assertEquals("en", wire["sourceLang"])
  }

  @Test
  fun `Lang fromWire is case-insensitive and falls back to EN`() {
    assertEquals(Lang.EN, Lang.fromWire("en"))
    assertEquals(Lang.EN, Lang.fromWire("EN"))
    assertEquals(Lang.EN, Lang.fromWire("unknown"))
  }

  // -- helpers ----------------------------------------------------------

  private inner class AskHarness(val sessionId: String = "s") {
    val pending = PendingQuestions()
    val emitted = mutableListOf<Pair<String, Map<String, Any>>>()
    private val scope = AskScope(sessionId, pending) { type, data -> emitted.add(type to data) }

    val requestId: String
      get() = emitted.last().second["requestId"] as String

    fun startAsk(build: AskBuilder.() -> Unit): Pair<Thread, Array<AskResult?>> {
      val holder = arrayOfNulls<AskResult>(1)
      val blocker = Thread { holder[0] = scope.ask_interaction(build) }
      blocker.start()
      waitUntil { emitted.isNotEmpty() && pending.size() == 1 }
      return blocker to holder
    }
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 2000
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    error("condition not met within timeout")
  }
}