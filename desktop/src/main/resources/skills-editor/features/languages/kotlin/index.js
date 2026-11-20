// The Kotlin language pack. The adapter is the whole contract the editor
// sees: how to tokenize a buffer, one indent level's width and where
// completion candidates come from. Nothing Kotlin-specific lives outside this
// directory.

import {API_SYMBOLS, SNIPPETS} from "./symbols.js";
import {KOTLIN_KEYWORDS, tokenize} from "./syntax.js";

export const kotlin = {
  indentUnit: 2,
  tokenize,
  completion: {
    symbols: API_SYMBOLS,
    snippets: SNIPPETS,
    keywords: KOTLIN_KEYWORDS,
  },
};
