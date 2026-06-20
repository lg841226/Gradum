package gradum

sealed class SkillResult {

    data class Success(val data: Map<String, Any>) : SkillResult()

    data class Failure(val code: String, val message: String, val context: Map<String, Any> = emptyMap()) : SkillResult()
}

fun makeSuccess(data: Map<String, Any>): SkillResult {
    return SkillResult.Success(data)
}

fun makeFailure(code: String, message: String, context: Map<String, Any> = emptyMap()): SkillResult {
    return SkillResult.Failure(code, message, context)
}
