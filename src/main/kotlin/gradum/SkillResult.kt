/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillResult.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum

sealed class SkillResult {

  data class Success(val data: Map<String, Any>) : SkillResult()

  data class Failure(val code: String, val message: String, val context: Map<String, Any> = emptyMap()) :
    SkillResult()
}

fun makeSuccess(data: Map<String, Any>): SkillResult {
  return SkillResult.Success(data)
}

fun makeFailure(code: String, message: String, context: Map<String, Any> = emptyMap()): SkillResult {
  return SkillResult.Failure(code, message, context)
}

fun makeFailure(code: ErrorCode, message: String, context: Map<String, Any> = emptyMap()): SkillResult {
  return SkillResult.Failure(code.code, message, context)
}
