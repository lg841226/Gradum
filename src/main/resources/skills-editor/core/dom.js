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
export const FALLBACK_METRICS = {charWidth: 7.8, lineHeight: 20, padTop: 0, padLeft: 0};

export function codeMetrics(surface, probeHost) {
  const style = getComputedStyle(surface);
  const metrics = {
    charWidth: FALLBACK_METRICS.charWidth,
    lineHeight: parseFloat(style.lineHeight) || FALLBACK_METRICS.lineHeight,
    padTop: parseFloat(style.paddingTop) || FALLBACK_METRICS.padTop,
    padLeft: parseFloat(style.paddingLeft) || FALLBACK_METRICS.padLeft,
  };

  const probe = document.createElement("span");
  probe.style.cssText = "position:absolute;visibility:hidden;white-space:pre;";
  probe.textContent = "M".repeat(50);
  probeHost.appendChild(probe);
  const width = probe.getBoundingClientRect().width;
  if (width > 0) metrics.charWidth = width / 50;
  probe.remove();

  return metrics;
}
