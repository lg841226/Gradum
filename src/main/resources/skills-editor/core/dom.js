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
// from a font — they are read back off the rendered overlay — so only the
// line box and the padding are needed here.
export function codeMetrics(surface) {
  const style = getComputedStyle(surface);
  return {
    lineHeight: parseFloat(style.lineHeight) || FALLBACK_METRICS.lineHeight,
    padTop: parseFloat(style.paddingTop) || FALLBACK_METRICS.padTop,
    padLeft: parseFloat(style.paddingLeft) || FALLBACK_METRICS.padLeft,
  };
}
