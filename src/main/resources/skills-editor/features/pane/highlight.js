import {escapeText} from "../../core/safety.js";
import {changedLines} from "../analysis.js";
import {languageFor} from "../languages/registry.js";

// Glyph framing that keeps a fullwidth character from reading as its ASCII
// twin at a glance: the tokenizer says what the run is, this says how it is
// drawn, whichever language the pack belongs to.
function markGlyphs(text) {
  return escapeText(text).replace(
    /[^\x00-\x7F]+/g,
    (run) => `<span class="glyph-box">${run}</span>`,
  );
}

// Consecutive lines are fused into one element, so a block of changes is one
// pill with a round tip at each end instead of a stack of chips.
function changedRuns(lines) {
  const runs = [];
  for (const line of lines) {
    const last = runs[runs.length - 1];
    if (last && line === last.end + 1) {
      last.end = line;
    } else {
      runs.push({start: line, end: line});
    }
  }
  return runs;
}

// Indentation guides: one dashed line per level, from the first line of a
// block to the last. Blank lines are transparent: they never open or close a
// run, so an empty line cannot leave a stub behind. A tab advances to the next
// tab stop the overlay is rendered with, so a tab-indented block lines up with
// its space-indented twin. One pass over the lines: every line opens the levels
// it newly reaches and closes the ones it falls below. A level's line is only
// drawn while the line is indented past that level: at indent === level * unit
// the column is the first glyph's own, so the level must stop one step short.
function indentGuides(text, indentUnit, tabWidth) {
  const guides = [];
  if (!(indentUnit > 0)) {
    return guides;
  }

  const rows = text.split("\n");
  const open = [];
  let last = -1;

  for (let index = 0; index < rows.length; index++) {
    const row = rows[index];
    let columns = 0;
    let cursor = 0;
    for (; cursor < row.length; cursor++) {
      if (row[cursor] === " ") {
        columns += 1;
      } else if (row[cursor] === "\t") {
        columns += tabWidth - (columns % tabWidth);
      } else {
        break;
      }
    }
    if (row.slice(cursor).trim() === "") {
      continue;
    }

    // Indented past, not level with: a line starting at level * unit has its
    // first glyph on that column, and a guide there would sit on the text.
    const depth = Math.max(0, Math.floor((columns - 1) / indentUnit));
    while (open.length > depth) {
      const run = open.pop();
      guides.push({level: run.level, start: run.start, end: last});
    }
    while (open.length < depth) {
      open.push({level: open.length + 1, start: index});
    }
    last = index;
  }

  while (open.length > 0) {
    const run = open.pop();
    guides.push({level: run.level, start: run.start, end: last});
  }
  return guides;
}

function guideMarkup(guides, indentUnit) {
  return guides.map(({level, start, end}) => {
    const span = end - start + 1;
    const top = `calc(${start} * var(--line-height-code))`;
    const height = `calc(${span} * var(--line-height-code))`;
    return `<span class="indent-guide" style="top: ${top}; height: ${height}; left: ${level * indentUnit}ch;"></span>`;
  }).join("");
}

function lineMarkup(text, tokenize) {
  const lines = [];
  let current = "";

  for (const token of tokenize(text)) {
    token.text.split("\n").forEach((part, index) => {
      if (index > 0) {
        lines.push(current);
        current = "";
      }
      if (!part) {
        return;
      }
      if (token.cls) {
        current += `<span class="${token.cls}">${markGlyphs(part)}</span>`;
      } else {
        current += markGlyphs(part);
      }
    });
  }
  lines.push(current);
  return lines;
}

export function createHighlightLayer({
                                       store,
                                       highlightCode,
                                       gutterInner,
                                       getText,
                                       getActiveOffset,
                                       getActiveDoc,
                                     }) {
  // What the last render used, so a settings change can repaint the two panes
  // exactly once instead of on every caret update the store broadcasts.
  let renderedText = null;
  let renderedGuides = null;
  let renderedTab = null;
  let renderedActive = null;

  function updateActiveLine() {
    const line = getText().slice(0, getActiveOffset()).split("\n").length;

    highlightCode.querySelector(".line.active")?.classList.remove("active");
    gutterInner.querySelector(".gutter-line.active")?.classList.remove("active");

    if (store.getState().highlightCurrentLine === false) {
      return;
    }

    highlightCode.querySelector(`.line[data-line="${line}"]`)?.classList.add("active");
    gutterInner.querySelector(`.gutter-line[data-line="${line}"]`)?.classList.add("active");
  }

  function render(text) {
    const state = store.getState();
    const doc = getActiveDoc(state);
    const language = languageFor(doc?.name);
    const guidesOn = state.indentGuides !== false;
    const tabWidth = state.tabWidth || 4;
    const lines = lineMarkup(text, language.tokenize);

    renderedText = text;
    renderedGuides = guidesOn;
    renderedTab = tabWidth;
    renderedActive = state.highlightCurrentLine !== false;

    const marked = doc && doc.baseline != null ? changedLines(text, doc.baseline) : [];
    const runHtml = changedRuns(marked).map(({start, end}) => {
      const span = end - start + 1;
      const top = `calc(var(--space-3) + ${start - 1} * var(--line-height-code) + 4px)`;
      const height = `calc(${span} * var(--line-height-code) - 8px)`;
      return `<span class="changed-run" style="top: ${top}; height: ${height};"></span>`;
    });

    highlightCode.innerHTML = lines
      .map((content, index) => `<span class="line" data-line="${index + 1}">${content}</span>`)
      .join("") + (guidesOn
      ? guideMarkup(indentGuides(text, language.indentUnit, tabWidth), language.indentUnit)
      : "");
    gutterInner.innerHTML = lines
      .map((_content, index) =>
        `<span class="gutter-line" data-line="${index + 1}">${index + 1}</span>`)
      .join("") + runHtml.join("");

    updateActiveLine();
  }

  store.subscribe((state) => {
    if (renderedText === null) {
      return;
    }
    const guidesOn = state.indentGuides !== false;
    const tabWidth = state.tabWidth || 4;
    if (guidesOn !== renderedGuides || tabWidth !== renderedTab) {
      render(renderedText);
      return;
    }
    const activeOn = state.highlightCurrentLine !== false;
    if (activeOn !== renderedActive) {
      renderedActive = activeOn;
      updateActiveLine();
    }
  });

  return {render, updateActiveLine};
}
