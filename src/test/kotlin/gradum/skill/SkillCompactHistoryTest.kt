/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillCompactHistoryTest.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.SkillResult
import gradum.ToolMode
import gradum.utils.JsonUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Coverage for the new "strip OLDER history, keep current call" contract
 * defined by [Skill.recordAndCompactHistory] and [Skill.compactHistory].
 *
 * The original design accidentally stripped the CURRENT call's result
 * (silent LLM blinding). This test exercises the corrected path: across
 * many calls on the same skill instance, the current call always
 * returns in full, while older history messages in
 * `conversationHistory` get [Skill.historyVolatileKeys] stripped
 * after [Skill.historyKeepCount] is exceeded.
 *
 * The fix is in [Skill] itself, so a tiny inline skill in the test
 * exercises the framework behavior end-to-end. The skill's
 * `execute` returns a success with a `content` key so the default
 * [Skill.compactHistory] can strip it from older messages.
 */
class SkillCompactHistoryTest {

    /** Minimal Skill that succeeds with a fixed payload — used to drive
     *  the framework's compactHistory / recordAndCompactHistory logic
     *  without depending on filesystem / network state. */
    private class ContentSkill(
        override val historyKeepCount: Int,
        override val historyVolatileKeys: List<String>,
    ) : Skill() {
        override val skillName: String = "content_skill"
        override val alias: String = "Contented"
        override val description: String = "returns a fixed payload with `content`"

        override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult =
            SkillResult.Success(
                linkedMapOf(
                    "content" to (arguments["body"] as? String ?: ""),
                    "metadata" to (arguments["meta"] as? String ?: ""),
                )
            )

        override fun getSchema(context: SkillContext?): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            )
        )
    }

    private fun testContext(): SkillContext = SkillContext(
        toolMode = ToolMode.READ_ONLY,
        projectRoot = "/tmp",
        provider = Provider.OLLAMA,
        modelName = "qwen2.5:7b"
    )

    private fun appendToolMessage(
        history: MutableList<Map<String, Any>>,
        alias: String,
        payload: Map<String, Any>,
    ) {
        val contentString: String = JsonUtil.encodeMap(payload)
        history.add(
            mapOf(
                "role" to "tool",
                "alias" to alias,
                "content" to contentString,
            )
        )
    }

    @Test
    fun `recordAndCompactHistory returns the current call's content in full on every call`() {
        val skill = ContentSkill(historyKeepCount = 2, historyVolatileKeys = listOf("content"))
        val history: MutableList<Map<String, Any>> = mutableListOf()
        val context = testContext()

        for (i in 1..5) {
            val raw: SkillResult = skill.execute(mapOf("body" to "body-$i", "meta" to "m-$i"), context)
            val data: Map<String, Any> = (raw as SkillResult.Success).data
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }

            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            assertEquals(
                "body-$i", historyResult["content"],
                "call #$i: current call's `content` was stripped — keys: ${historyResult.keys}"
            )
            assertEquals(
                "m-$i", historyResult["metadata"],
                "call #$i: current call's `metadata` was stripped"
            )
            appendToolMessage(history, skill.alias, historyResult)
        }
    }

    @Test
    fun `default compactHistory strips volatile keys from older messages after historyKeepCount exceeded`() {
        // Keep only the most recent 2 calls' content; older calls lose `content`.
        val skill = ContentSkill(historyKeepCount = 2, historyVolatileKeys = listOf("content"))
        val history: MutableList<Map<String, Any>> = mutableListOf()

        fun callBody(bodyValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to "m-$bodyValue")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        callBody("body-1")
        callBody("body-2")
        callBody("body-3")  // after this call, body-1 (call 1) is older than keepCount=2, must be stripped
        callBody("body-4")  // after this call, body-2 (call 2) is also stripped

        // history now has 4 tool messages in order: 1, 2, 3, 4
        val parsed: List<Map<String, Any?>> = history.map { msg ->
            val content: String = msg["content"] as String
            JsonUtil.decodeMap(content)
        }

        // Call 1 — older than keepCount=2 by call 3; should be stripped
        assertFalse(
            parsed[0].containsKey("content"),
            "call 1 should have `content` stripped — keys: ${parsed[0].keys}"
        )
        // Call 2 — older than keepCount=2 by call 4; should be stripped
        assertFalse(
            parsed[1].containsKey("content"),
            "call 2 should have `content` stripped — keys: ${parsed[1].keys}"
        )
        // Call 3 — still within the keepCount window of 2
        assertEquals(
            "body-3", parsed[2]["content"],
            "call 3 should keep its `content` (within historyKeepCount=2)"
        )
        // Call 4 — the most recent, must always keep its content
        assertEquals(
            "body-4", parsed[3]["content"],
            "call 4 should keep its `content` (most recent)"
        )

        // Sanity: `metadata` is NOT volatile — it should survive every call
        assertEquals("m-body-1", parsed[0]["metadata"])
        assertEquals("m-body-2", parsed[1]["metadata"])
        assertEquals("m-body-3", parsed[2]["metadata"])
        assertEquals("m-body-4", parsed[3]["metadata"])
    }

    @Test
    fun `default compactHistory strips multiple volatile keys at once`() {
        val skill = ContentSkill(
            historyKeepCount = 1,
            historyVolatileKeys = listOf("content", "metadata")
        )
        val history: MutableList<Map<String, Any>> = mutableListOf()

        fun callBody(bodyValue: String, metaValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to metaValue)
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        callBody("a", "x")
        callBody("b", "y")  // call 1 falls outside keepCount=1, both keys must be stripped
        callBody("c", "z")  // call 2 also falls outside keepCount=1

        val parsed: List<Map<String, Any?>> = history.map { msg ->
            val content: String = msg["content"] as String
            JsonUtil.decodeMap(content)
        }

        // Call 1 — both keys stripped
        assertFalse(parsed[0].containsKey("content"), "call 1: content should be stripped")
        assertFalse(parsed[0].containsKey("metadata"), "call 1: metadata should be stripped")

        // Call 2 — both keys stripped
        assertFalse(parsed[1].containsKey("content"), "call 2: content should be stripped")
        assertFalse(parsed[1].containsKey("metadata"), "call 2: metadata should be stripped")

        // Call 3 — most recent, must keep everything
        assertEquals("c", parsed[2]["content"])
        assertEquals("z", parsed[2]["metadata"])
    }

    @Test
    fun `Int_MAX_VALUE keep count means nothing is ever stripped`() {
        val skill = ContentSkill(
            historyKeepCount = Int.MAX_VALUE,
            historyVolatileKeys = listOf("content")
        )
        val history: MutableList<Map<String, Any>> = mutableListOf()

        fun callBody(bodyValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to "m-$bodyValue")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        for (i in 1..10) callBody("body-$i")

        val parsed: List<Map<String, Any?>> = history.map { msg ->
            val content: String = msg["content"] as String
            JsonUtil.decodeMap(content)
        }

        // Nothing should ever be stripped when historyKeepCount = MAX_VALUE
        for ((i, element) in parsed.withIndex()) {
            assertEquals(
                "body-${i + 1}", element["content"],
                "call ${i + 1}: content should NOT be stripped with keepCount=MAX_VALUE"
            )
        }
    }

    @Test
    fun `empty historyVolatileKeys means no keys are ever stripped`() {
        val skill = ContentSkill(
            historyKeepCount = 0,  // aggressive — would strip every older call
            historyVolatileKeys = emptyList()
        )
        val history: MutableList<Map<String, Any>> = mutableListOf()

        fun callBody(bodyValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to "m-$bodyValue")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        callBody("a")
        callBody("b")
        callBody("c")

        val parsed: List<Map<String, Any?>> = history.map { msg ->
            val content: String = msg["content"] as String
            JsonUtil.decodeMap(content)
        }

        for (i in parsed.indices) {
            assertEquals(
                "m-${listOf("a", "b", "c")[i]}", parsed[i]["metadata"],
                "call ${i + 1}: metadata should NOT be stripped with empty volatileKeys"
            )
        }
    }

    @Test
    fun `callCount increments by one per recordAndCompactHistory invocation`() {
        val skill = ContentSkill(historyKeepCount = 2, historyVolatileKeys = listOf("content"))
        val history: MutableList<Map<String, Any>> = mutableListOf()

        assertEquals(0, skill.callCount, "freshly created skill must start with callCount=0")

        for (i in 1..4) {
            val data: Map<String, Any> = mapOf("content" to "body-$i", "metadata" to "m-$i")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
            assertEquals(i, skill.callCount, "after $i calls, callCount should be $i")
        }

        skill.resetHistoryCount()
        assertEquals(0, skill.callCount, "resetHistoryCount must zero the counter")
    }

    @Test
    fun `compactHistory leaves untouched messages that are not the skill's own`() {
        val skill = ContentSkill(historyKeepCount = 1, historyVolatileKeys = listOf("content"))
        val history: MutableList<Map<String, Any>> = mutableListOf(
            // An unrelated tool message from a different skill — must NOT be touched.
            mapOf(
                "role" to "tool",
                "alias" to "OtherSkill",
                "content" to JsonUtil.encodeMap(mapOf("content" to "other-body", "metadata" to "other-m"))
            ),
            // A user/system message — must NOT be touched.
            mapOf("role" to "user", "content" to "hi"),
        )

        fun callBody(bodyValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to "m-$bodyValue")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        callBody("a")
        callBody("b")  // previous own call (a) is now older than keepCount=1 — should be stripped

        // The unrelated "OtherSkill" tool message must still have its content intact
        val otherContent: String = history[0]["content"] as String
        val otherParsed: Map<String, Any?> = JsonUtil.decodeMap(otherContent)
        assertEquals(
            "other-body", otherParsed["content"],
            "unrelated skill's tool message was modified — should be untouched"
        )

        // The user message is unchanged
        assertEquals("hi", history[1]["content"])

        // The skill's own call "a" had its content stripped
        val ownFirstParsed: Map<String, Any?> = JsonUtil.decodeMap(history[2]["content"] as String)
        assertFalse(
            ownFirstParsed.containsKey("content"),
            "skill's own first call should have `content` stripped after second call"
        )
    }

    @Test
    fun `compactHistory does not over-strip when truncation has removed older own messages`() {
        // Simulates the scenario where truncateHistory evicts old messages,
        // leaving fewer own messages than the session callCount would imply.
        val skill = ContentSkill(historyKeepCount = 2, historyVolatileKeys = listOf("content"))
        val history: MutableList<Map<String, Any>> = mutableListOf()

        fun callBody(bodyValue: String) {
            val data: Map<String, Any> = mapOf("content" to bodyValue, "metadata" to "m-$bodyValue")
            val ownIndices: List<Int> = history.withIndex()
                .filter { (_, entry) ->
                    (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
                }.map { it.index }
            val historyResult: Map<String, Any> = skill.recordAndCompactHistory(data, history, ownIndices)
            appendToolMessage(history, skill.alias, historyResult)
        }

        // Build 5 own messages: body-1 ... body-5
        for (i in 1..5) callBody("body-$i")

        // Simulate truncateHistory removing the first 2 own messages.
        // In production takeLastTurns may strip a mix of roles, but for
        // this regression test we only need to remove own messages so that
        // ownMessageIndices.size < callCount - 1.
        val ownIndices = history.withIndex()
            .filter { (_, entry) ->
                (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == skill.alias
            }
        // Remove the first two own messages (indices 0 and 1).
        history.removeAt(ownIndices[1].index)
        history.removeAt(ownIndices[0].index)

        // History now contains only body-3, body-4, body-5.  callCount was 5,
        // so old formula dropCount = 5 - 2 = 3, which would strip ALL three
        // remaining old messages.  New formula: ownMessageIndices.size + 1 - 2
        // = 3 + 1 - 2 = 2, which correctly strips only the two oldest
        // remaining messages and keeps body-5.

        // Now make a 6th call — this is where the bug would trigger.
        callBody("body-6")

        val parsed: List<Map<String, Any?>> = history.map { msg ->
            val content: String = msg["content"] as String
            JsonUtil.decodeMap(content)
        }

        // body-3 should be stripped (oldest remaining after truncation)
        assertFalse(parsed[0].containsKey("content"), "body-3 should be stripped")
        // body-4 should also be stripped (2nd oldest)
        assertFalse(parsed[1].containsKey("content"), "body-4 should be stripped")
        // body-5 must survive — it is within keepCount=2 of the final state
        assertEquals("body-5", parsed[2]["content"], "body-5 must survive (within keepCount window)")
        // body-6 (current call) must always survive
        assertEquals("body-6", parsed[3]["content"], "body-6 is the current call and must survive")
    }
}
