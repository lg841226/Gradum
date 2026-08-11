/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentityTest.kt  2026-08-11 18:00:00 Changed by gwy
 */

package gradum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelIdentityTest {

  // --- parameterCountInBillions ---

  @Test
  fun `parameterCountInBillions extracts integer size suffix`() {
    assertEquals(32.0, ModelIdentity.parameterCountInBillions("qwen-32b"), 0.001)
  }

  @Test
  fun `parameterCountInBillions extracts decimal size suffix`() {
    assertEquals(0.5, ModelIdentity.parameterCountInBillions("qwen-0.5b"), 0.001)
  }

  @Test
  fun `parameterCountInBillions returns zero when no size suffix is present`() {
    assertEquals(0.0, ModelIdentity.parameterCountInBillions("qwen2.5"), 0.001)
  }

  @Test
  fun `parameterCountInBillions is case-insensitive`() {
    assertEquals(70.0, ModelIdentity.parameterCountInBillions("LLAMA-70B"), 0.001)
  }

  @Test
  fun `parameterCountInBillions handles colon separator`() {
    assertEquals(14.0, ModelIdentity.parameterCountInBillions("qwen2.5:14b"), 0.001)
  }

  // --- isSmallModel ---

  @Test
  fun `isSmallModel returns true for sub-32B local models`() {
    assertTrue(ModelIdentity.isSmallModel("qwen-14b"))
    assertTrue(ModelIdentity.isSmallModel("qwen-7b"))
    assertTrue(ModelIdentity.isSmallModel("qwen-0.5b"))
  }

  @Test
  fun `isSmallModel returns false for models above threshold`() {
    assertFalse(ModelIdentity.isSmallModel("qwen-70b"))
    assertFalse(ModelIdentity.isSmallModel("qwen-110b"))
  }

  @Test
  fun `isSmallModel returns false for cloud-named models regardless of size`() {
    assertFalse(ModelIdentity.isSmallModel("gpt-4o"))
    assertFalse(ModelIdentity.isSmallModel("llama-7b-cloud"))
    assertFalse(ModelIdentity.isSmallModel("claude-sonnet"))
  }

  @Test
  fun `isSmallModel returns false for blank name`() {
    assertFalse(ModelIdentity.isSmallModel(""))
  }

  @Test
  fun `isSmallModel returns false when no size tag is present`() {
    assertFalse(ModelIdentity.isSmallModel("qwen2.5"))
  }

  // --- schemaVariant ---

  @Test
  fun `schemaVariant returns SIMPLE for small local models`() {
    assertEquals(SchemaVariant.SIMPLE, ModelIdentity.schemaVariant("qwen-14b"))
  }

  @Test
  fun `schemaVariant returns FULL for large models`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant("qwen-70b"))
  }

  @Test
  fun `schemaVariant returns FULL for blank name`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant(""))
  }

  @Test
  fun `schemaVariant returns FULL for cloud models`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant("gpt-4o"))
  }

  // --- isCloudTagged ---

  @Test
  fun `isCloudTagged matches cloud in name`() {
    assertTrue(ModelIdentity.isCloudTagged("minimax-m2.5:cloud"))
    assertTrue(ModelIdentity.isCloudTagged("qwen3-coder-480b-cloud"))
    assertTrue(ModelIdentity.isCloudTagged("FOO-CLOUD"))
  }

  @Test
  fun `isCloudTagged rejects names without cloud`() {
    assertFalse(ModelIdentity.isCloudTagged("qwen-14b"))
    assertFalse(ModelIdentity.isCloudTagged("gpt-4o"))
  }

  // --- normalizeCatalogKey ---

  @Test
  fun `normalizeCatalogKey strips size and instruct suffix`() {
    assertEquals("qwen2-5-coder", ModelIdentity.normalizeCatalogKey("Qwen2.5-Coder-14B-Instruct"))
  }

  @Test
  fun `normalizeCatalogKey collapses separators and trims dashes`() {
    assertEquals("llama3", ModelIdentity.normalizeCatalogKey("LLAMA3:70B"))
  }

  @Test
  fun `normalizeCatalogKey handles bare model name`() {
    assertEquals("gpt-4o", ModelIdentity.normalizeCatalogKey("gpt-4o"))
  }
}
