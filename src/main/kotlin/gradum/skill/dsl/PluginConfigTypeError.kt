package gradum.skill.dsl

class PluginConfigTypeError(
  val keyName: String, val groupName: String,
  actualValue: Any?, expectedType: String
) : IllegalArgumentException(
  "Plugin config '$groupName.$keyName' expected $expectedType but got ${describe(actualValue)}"
) {
  companion object {
    private fun describe(configValue: Any?): String =
      configValue?.let { "${it::class.simpleName} ($it)" } ?: "null"
  }
}
