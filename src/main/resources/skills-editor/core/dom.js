export function clear(node) {
  node.replaceChildren();
  return node;
}

export function formatTime(date = new Date()) {
  return date.toLocaleTimeString();
}

// Metrics used before the code surface has been measured and whenever a value
// cannot be read from its computed style. Both the editor's initial state and
// codeMetrics read from here so the fallback lives in one place.
export const FALLBACK_METRICS = {lineHeight: 20, padTop: 0, padLeft: 0};

export function codeMetrics(surface) {
  const style = getComputedStyle(surface);
  return {
    lineHeight: parseFloat(style.lineHeight) || FALLBACK_METRICS.lineHeight,
    padTop: parseFloat(style.paddingTop) || FALLBACK_METRICS.padTop,
    padLeft: parseFloat(style.paddingLeft) || FALLBACK_METRICS.padLeft,
  };
}

const WIDE_GLYPH = new RegExp(
  [
    String.raw`[\u1100-\u115F\u2E80-\u303E\u3041-\u33FF\u3400-\u4DBF`,
    String.raw`\u4E00-\u9FFF\uA000-\uA4CF\uAC00-\uD7A3`,
    String.raw`\uF900-\uFAFF\uFE30-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]`,
  ].join(""),
);

export function displayColumns(text, tabWidth = 4) {
  const stop = tabWidth > 0 ? tabWidth : 4;
  let column = 0;

  for (const char of text) {
    if (char === "\t") {
      column += stop - (column % stop);
    } else if (WIDE_GLYPH.test(char)) {
      column += 2;
    } else {
      column += 1;
    }
  }
  return column;
}
