export const KOTLIN_KEYWORDS = [
  "package", "import", "class", "object", "interface", "fun", "val", "var",
  "override", "private", "public", "protected", "internal", "return", "if", "else", "when",
  "for", "while", "do", "try", "catch", "finally", "throw", "is", "in", "as", "null", "true",
  "false", "this", "super", "open", "data", "sealed", "enum", "companion", "abstract", "const",
  "lateinit", "by", "where", "typealias", "init", "constructor", "break", "continue", "out",
  "reified", "inline", "suspend", "crossinline", "noinline", "vararg", "operator", "infix",
  "external", "annotation", "actual", "expect", "tailrec",
];

const TOKEN_CLASS = {
  1: "com",
  2: "str",
  3: "ann",
  4: "num",
  5: "kw",
  6: "type",
  7: "fn",
};

const TOKEN_PATTERN = new RegExp(
  [
    String.raw`(\/\/[^\n]*|\/\*[\s\S]*?\*\/)`,
    String.raw`("""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')`,
    String.raw`(@[A-Za-z_][A-Za-z0-9_]*)`,
    String.raw`(\b\d[\w.]*[LlFfDd]?\b)`,
    `\\b(${KOTLIN_KEYWORDS.join("|")})\\b`,
    String.raw`(\b[A-Z][A-Za-z0-9_]*\b)`,
    String.raw`(\b[a-z_][A-Za-z0-9_]*)(?=\s*\()`,
  ].join("|"),
  "g",
);

function classOf(match) {
  for (let group = 1; group < match.length; group++) {
    if (match[group] !== undefined) {
      return TOKEN_CLASS[group];
    }
  }
  return null;
}

export function tokenize(text) {
  let match;
  let cursor = 0;
  const tokens = [];

  TOKEN_PATTERN.lastIndex = 0;
  while ((match = TOKEN_PATTERN.exec(text)) !== null) {
    if (match.index > cursor) {
      tokens.push({text: text.slice(cursor, match.index), cls: null});
    }
    tokens.push({text: match[0], cls: classOf(match)});
    cursor = match.index + match[0].length;
  }
  if (cursor < text.length) {
    tokens.push({text: text.slice(cursor), cls: null});
  }
  return tokens;
}
