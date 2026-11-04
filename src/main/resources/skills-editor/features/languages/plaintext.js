// The minimal language pack + the template a new language starts from.
//
// A pack is plain data plus a tokenizer: no DOM + no editor imports. The
// registry owns extension matching, so adding a language is this file's shape
// copied once + one registry row. Plain text paints no token classes, offers
// no completion, and keeps the two-space indent the editor defaults to.

export const plaintext = {
  indentUnit: 2,

  tokenize(text) {
    return text ? [{text, cls: null}] : [];
  },

  completion: null,
};
