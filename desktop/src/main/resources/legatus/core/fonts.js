export const CODE_FONT_FAMILIES = [
  "Menlo",
  "SF Mono",
  "Consolas",
  "Fira Code",
  "Cascadia Code",
  "JetBrains Mono",
  "Source Code Pro",
];

const CODE_FALLBACK = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

export function applyCodeFontFamily(element, state) {
  const name = typeof state?.codeFontFamily === "string" ? state.codeFontFamily.trim() : "";
  if (name) {
    element.style.setProperty("--font-code", `"${name}", ${CODE_FALLBACK}`);
  } else {
    element.style.removeProperty("--font-code");
  }
}

const PROBE_TEXT = "mmmmmmmmmmlliWWWW0123456789@#%&";

function measureFamilies(families) {
  if (typeof document === "undefined") {
    return null;
  }
  let context = null;
  try {
    context = document.createElement("canvas").getContext("2d");
  } catch (error) {
    context = null;
  }
  if (!context) {
    return null;
  }

  const width = (stack) => {
    context.font = `72px ${stack}`;
    const measured = context.measureText(PROBE_TEXT).width;
    return Number.isFinite(measured) ? measured : 0;
  };

  const mono = width("monospace");
  if (!(mono > 0)) {
    return null;
  }

  const found = new Map();
  for (const family of families) {
    const quoted = `"${family.replace(/["\\]/g, "")}"`;
    found.set(family, width(`${quoted}, monospace`) !== mono);
  }
  return found;
}

function probeFontFamilies(families) {
  const measured = measureFamilies(families);
  if (!measured) {
    return null;
  }
  if (families.length > 1 && ![...measured.values()].some(Boolean)) {
    return null;
  }
  return measured;
}

let availability = null;
let probed = false;

export function fontAvailability() {
  if (!probed) {
    availability = probeFontFamilies(CODE_FONT_FAMILIES);
    probed = true;
  }
  return availability;
}

export function defaultFontFamily() {
  const found = fontAvailability();
  if (found) {
    for (const family of CODE_FONT_FAMILIES) {
      if (found.get(family) === true) {
        return family;
      }
    }
  }
  return CODE_FONT_FAMILIES[0];
}

export function remeasureAfterFonts() {
  const run = () => requestAnimationFrame(() => window.dispatchEvent(new Event("resize")));
  const fonts = typeof document !== "undefined" ? document.fonts : null;
  if (fonts?.ready?.then) {
    fonts.ready.then(run, run);
  } else {
    run();
  }
}
