import {escapeHtml} from "../core/dom.js";

// The syntax overlay's tokenizer: one pattern run over the buffer labels
// every run as a comment, string, number, keyword or name for the highlight
// layer, and the glyph framing keeps a fullwidth character from reading as
// its ASCII twin at a glance. Everything here is pure text to text.

const KOTLIN_KEYWORDS = [
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
  const tokens = [];
  let cursor = 0;
  let match;

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

// Anything outside ASCII is boxed in the overlay, so a fullwidth （ is not read
// as an ASCII ( at a glance: the two are near-identical at code size and the
// glyph shape alone does not separate them. Escaping runs first, so the tags
// added here are never themselves boxed and an entity's ASCII characters are
// left alone — only the glyphs the file actually holds are wrapped.
export function markGlyphs(text) {
  return escapeHtml(text).replace(
    /[^\x00-\x7F]+/g,
    (run) => `<span class="glyph-box">${run}</span>`,
  );
}
