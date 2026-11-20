// A pack has exactly the shape the editor consumes today:
//   tokenize(text)  -> [{text, cls}]  cls: null | "com" | "str" | "ann" |
//                                      "num" | "kw" | "type" | "fn"
//   indentUnit      -> number of spaces one level uses
//   completion      -> {symbols, snippets, keywords} | null
//                      symbols:   {label, kind, detail, member?}
//                      snippets:  {label, kind, detail, insert}
//                      keywords:  string[]
//
// Unknown extensions fall back to plaintext: plain rendering, no completion,
// no diagnostics, no errors.

import {css} from "./css.js";
import {kotlin} from "./kotlin/index.js";
import {plaintext} from "./plaintext.js";

const LANGUAGES = [
  {extensions: ["kt", "kts"], language: kotlin},
  {extensions: ["css"], language: css},
];

export function languageFor(fileName) {
  const name = String(fileName ?? "").toLowerCase();
  const dot = name.lastIndexOf(".");
  const extension = dot >= 0 ? name.slice(dot + 1) : "";
  for (const entry of LANGUAGES) {
    if (entry.extensions.includes(extension)) {
      return entry.language;
    }
  }
  return plaintext;
}
