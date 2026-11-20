// One place for the escapes and URL checks that keep untrusted text inert.
//
// `escapeText` is for text content only. An attribute value needs the same
// call, but the result must be quoted: the escapes cover quotes precisely so
// the two contexts share one implementation.

const TEXT_ESCAPES = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

export function escapeText(value) {
  return String(value).replace(/[&<>"']/g, (char) => TEXT_ESCAPES[char]);
}

const SAFE_SCHEMES = new Set(["http:", "https:", "mailto:"]);

// Returns the href to use, or null when the value could execute script. The
// model writes these links, so they are untrusted input in every respect.
export function safeUrl(raw) {
  const value = String(raw ?? "").trim();
  if (!value) {
    return null;
  }

  if (/[\u0000-\u001f\u007f]/.test(value)) {
    return null;
  }
  const scheme = value.match(/^([a-z][a-z0-9+.-]*):/i);
  if (!scheme) {
    return value;
  }
  return SAFE_SCHEMES.has(`${scheme[1].toLowerCase()}:`) ? value : null;
}

// Kind tokens reach class names and icon-map keys. Only the lowercase
// identifiers the completion tables define are allowed.
export function isSafeToken(value) {
  return typeof value === "string" && /^[a-z][a-z0-9-]*$/.test(value);
}
