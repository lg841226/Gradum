// Rebuilds the syntax overlay and the gutter together.
//
// Both layers are written from the same token run, so a line number and the
// glyphs beside it can never fall out of step. The changed-line bars are
// computed here because this is the one place the gutter's markup is rebuilt:
// opening, typing and deploying all land in it, and a bar survives none of
// them by accident.

import {activeDoc} from "../../core/store.js";
import {changedLines} from "../analysis.js";
import {markGlyphs, tokenize} from "../syntax.js";

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

function lineMarkup(text) {
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
}) {
  function updateActiveLine() {
    const line = getText().slice(0, getActiveOffset()).split("\n").length;

    highlightCode.querySelector(".line.active")?.classList.remove("active");
    gutterInner.querySelector(".gutter-line.active")?.classList.remove("active");

    highlightCode.querySelector(`.line[data-line="${line}"]`)?.classList.add("active");
    gutterInner.querySelector(`.gutter-line[data-line="${line}"]`)?.classList.add("active");
  }

  function render(text) {
    const lines = lineMarkup(text);

    const doc = activeDoc(store.getState());
    const marked = doc && doc.baseline != null ? changedLines(text, doc.baseline) : [];
    const runHtml = changedRuns(marked).map(({start, end}) => {
      const span = end - start + 1;
      const top = `calc(var(--space-3) + ${start - 1} * var(--line-height-code) + 4px)`;
      const height = `calc(${span} * var(--line-height-code) - 8px)`;
      return `<span class="changed-run" style="top: ${top}; height: ${height};"></span>`;
    });

    highlightCode.innerHTML = lines
      .map((content, index) => `<span class="line" data-line="${index + 1}">${content}</span>`)
      .join("");
    gutterInner.innerHTML = lines
      .map((_content, index) =>
        `<span class="gutter-line" data-line="${index + 1}">${index + 1}</span>`)
      .join("") + runHtml.join("");

    updateActiveLine();
  }

  return {render, updateActiveLine};
}
