// Small DOM and measurement helpers shared across features and components.

export function clear(node) {
  node.replaceChildren();
  return node;
}

const HTML_ESCAPES = {"&": "&amp;", "<": "&lt;", ">": "&gt;"};

export function escapeHtml(value) {
  return String(value).replace(/[&<>]/g, (char) => HTML_ESCAPES[char]);
}

export function formatTime(date = new Date()) {
  return date.toLocaleTimeString();
}

// Metrics used before the code surface has been measured and whenever a value
// cannot be read from its computed style. Both the editor's initial state and
// codeMetrics read from here so the fallback lives in one place.
export const FALLBACK_METRICS = {lineHeight: 20, padTop: 0, padLeft: 0};

// Vertical metrics of the code surface. Horizontal offsets are never derived
// from a font: they are read back off the rendered overlay, so only the
// line box and the padding are needed here.
export function codeMetrics(surface) {
  const style = getComputedStyle(surface);
  return {
    lineHeight: parseFloat(style.lineHeight) || FALLBACK_METRICS.lineHeight,
    padTop: parseFloat(style.paddingTop) || FALLBACK_METRICS.padTop,
    padLeft: parseFloat(style.paddingLeft) || FALLBACK_METRICS.padLeft,
  };
}

// East Asian wide and fullwidth ranges. These are the glyphs a monospace grid
// draws two cells wide, which is how a column is counted for the status bar
// and for a caret moving vertically through lines of tabs. The class is
// assembled from fragments so no single line runs past the column limit.
const WIDE_GLYPH = new RegExp(
  [
    String.raw`[\u1100-\u115F\u2E80-\u303E\u3041-\u33FF\u3400-\u4DBF`,
    String.raw`\u4E00-\u9FFF\uA000-\uA4CF\uAC00-\uD7A3`,
    String.raw`\uF900-\uFAFF\uFE30-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]`,
  ].join(""),
);

// Column a caret sits in, counted the way a monospace grid counts rather than
// by character: a wide glyph holds two columns, and a tab runs to the next
// stop four columns along. The status bar reports this number, so it is
// deliberately not the same as the pixel offset `advance` returns.
export function displayColumns(text) {
  let column = 0;

  for (const char of text) {
    if (char === "\t") {
      column += 4 - (column % 4);
    } else if (WIDE_GLYPH.test(char)) {
      column += 2;
    } else {
      column += 1;
    }
  }
  return column;
}
