// The Gradum skill API as the completion list knows it: a hand-kept mirror of
// Skill.kt, SkillContext, AgentConfiguration and the schema DSL, grown the
// way the docs are, by hand. Every row carries the kind its icon paints with
// and the type the right edge reports; `member` marks what belongs after a
// dot, where keywords and snippets do not.

export const API_SYMBOLS = [
  // Skill itself: what an override or an execute() body reaches for.
  {label: "skillName", kind: "val", detail: "String", member: true},
  {label: "alias", kind: "val", detail: "String", member: true},
  {label: "description", kind: "val", detail: "String", member: true},
  {label: "toolDisplay", kind: "val", detail: "ToolDisplay", member: true},
  {label: "allowedToolModes", kind: "val", detail: "Set<ToolMode>", member: true},
  {label: "manageOwnEventStream", kind: "val", detail: "Boolean", member: true},
  {label: "schemaProperties", kind: "val", detail: "SchemaBuilder.() -> Unit", member: true},
  {label: "simpleDescription", kind: "val", detail: "String?", member: true},
  {label: "historyKeepCount", kind: "val", detail: "Int", member: true},
  {label: "historyVolatileKeys", kind: "val", detail: "List<String>", member: true},
  {label: "callCount", kind: "val", detail: "Int", member: true},
  {label: "execute", kind: "fun", detail: "SkillResult", member: true},
  {label: "getSchema", kind: "fun", detail: "Map<String, Any>", member: true},
  {label: "prepareHistoryResult", kind: "fun", detail: "Map<String, Any>", member: true},
  {label: "compactHistory", kind: "fun", detail: "Unit", member: true},

  // The two enums a skill answers with. EDIT reads as ToolMode here, the one
  // allowedToolModes names more often than ToolDisplayKind does.
  {label: "ToolMode", kind: "type", detail: "enum", member: false},
  {label: "AGENT", kind: "val", detail: "ToolMode", member: true},
  {label: "READ_ONLY", kind: "val", detail: "ToolMode", member: true},
  {label: "EDIT", kind: "val", detail: "ToolMode", member: true},
  {label: "ToolDisplayKind", kind: "type", detail: "enum", member: false},
  {label: "READ", kind: "val", detail: "ToolDisplayKind", member: true},
  {label: "EXECUTE", kind: "val", detail: "ToolDisplayKind", member: true},
  {label: "SEARCH", kind: "val", detail: "ToolDisplayKind", member: true},
  {label: "FETCH", kind: "val", detail: "ToolDisplayKind", member: true},
  {label: "OTHER", kind: "val", detail: "ToolDisplayKind", member: true},

  // How a call is presented, and the shapes it is built from.
  {label: "ToolDisplay", kind: "type", detail: "data class", member: false},
  {label: "label", kind: "val", detail: "String", member: true},
  {label: "paramKey", kind: "val", detail: "String", member: true},
  {label: "kind", kind: "val", detail: "ToolDisplayKind", member: true},

  // The schema block: what SchemaBuilder and the SchemaAdapter extensions
  // resolve inside schemaProperties: string and friends called bare inside
  // the lambda, cloudOnly and simpleOnly after a dot on the builder.
  {label: "SchemaBuilder", kind: "type", detail: "class", member: false},
  {label: "SkillParameter", kind: "type", detail: "class", member: false},
  {label: "string", kind: "fun", detail: "SchemaAdapter", member: false},
  {label: "integer", kind: "fun", detail: "SchemaAdapter", member: false},
  {label: "boolean", kind: "fun", detail: "SchemaAdapter", member: false},
  {label: "stringArray", kind: "fun", detail: "SchemaAdapter", member: false},
  {label: "objectArray", kind: "fun", detail: "SchemaAdapter", member: false},
  {label: "cloudOnly", kind: "fun", detail: "SchemaBuilder", member: true},
  {label: "simpleOnly", kind: "fun", detail: "SchemaBuilder", member: true},
  {label: "add", kind: "fun", detail: "SkillParameter", member: true},

  // The context execute() carries, reached as `context.`.
  {label: "SkillContext", kind: "type", detail: "data class", member: false},
  {label: "toolMode", kind: "val", detail: "ToolMode", member: true},
  {label: "projectRoot", kind: "val", detail: "String", member: true},
  {label: "modelName", kind: "val", detail: "String", member: true},
  {label: "provider", kind: "val", detail: "Provider", member: true},
  {label: "agentConfiguration", kind: "val", detail: "AgentConfiguration?", member: true},
  {label: "conversationHistory", kind: "val", detail: "() -> List", member: true},
  {label: "emitEvent", kind: "val", detail: "", member: true},
  {label: "isSimpleModel", kind: "val", detail: "Boolean", member: true},

  // What an execute() body returns and builds it with.
  {label: "Skill", kind: "type", detail: "abstract class", member: false},
  {label: "SkillResult", kind: "type", detail: "sealed", member: false},
  {label: "makeSuccess", kind: "fun", detail: "SkillResult", member: false},
  {label: "makeFailure", kind: "fun", detail: "SkillResult", member: false},
  {label: "ErrorCode", kind: "type", detail: "enum", member: false},
];

// Three skeletons the list offers ahead of the words. `insert` is what lands
// in the buffer (newlines included), and the caret ends up after it.
export const SNIPPETS = [
  {
    label: "skillClass",
    kind: "snippet",
    detail: "",
    insert: [
      "class NewSkill : Skill() {",
      '  override val skillName: String = "new_skill"',
      '  override val alias: String = "New"',
      '  override val description: String = ""',
      "",
      "  override val schemaProperties: SchemaBuilder.() -> Unit = {",
      '    string(name = "input", description = "", required = true)',
      "  }",
      "",
      "  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {",
      '    return makeSuccess { string("result", "") }',
      "  }",
      "}",
    ].join("\n"),
  },
  {
    label: "overrideVal",
    kind: "snippet",
    detail: "",
    insert: "override val ",
  },
  {
    label: "schemaBlock",
    kind: "snippet",
    detail: "",
    insert: [
      "override val schemaProperties: SchemaBuilder.() -> Unit = {",
      '  string(name = "", description = "", required = true)',
      "}",
    ].join("\n"),
  },
];
