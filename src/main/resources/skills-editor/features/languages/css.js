const TOKEN_PATTERN =
  /(\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(@[A-Za-z][A-Za-z0-9-]*)|(#[0-9A-Fa-f]{3,4}\b|#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{8}\b)|(\b\d+(?:\.\d+)?(?:%|[A-Za-z]+)?\b)/g;

export const css = {
  indentUnit: 2,

  tokenize(text) {
    if (!text) {
      return [];
    }

    const tokens = [];
    const push = (value, cls) => {
      if (value) {
        tokens.push({text: value, cls});
      }
    };

    let last = 0;
    for (const match of text.matchAll(TOKEN_PATTERN)) {
      const index = match.index ?? 0;
      if (index > last) {
        push(text.slice(last, index), null);
      }
      const cls = match[1] != null
        ? "com"
        : match[2] != null
          ? "str"
          : match[3] != null
            ? "ann"
            : "num";
      push(match[0], cls);
      last = index + match[0].length;
    }
    push(text.slice(last), null);
    return tokens;
  },

  completion: null,
};
