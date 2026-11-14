// One blink clock is shared by every caret in the document: the off-toggle
// lands on the root, so all carets flip in the same frame and the phases can
// never drift apart. A caret move restarts the clock, so a moved set comes back
// up solid the way a lone caret settles.
//
// The native caret can drift against the syntax overlay because the two layers
// round their fractional line boxes apart, so it is hidden and these carets are
// drawn from the same metrics the overlay and squiggles use.

import {displayColumns} from "../../core/dom.js";
import {indentWidth} from "../analysis.js";

function restartBlink(elements) {
  for (const element of elements) {
    for (const animation of element.getAnimations()) {
      animation.currentTime = 0;
    }
  }
}

export function createCaretLayer({
                                   store,
                                   source,
                                   code,
                                   highlightCode,
                                   metrics,
                                   advance,
                                   getScroll,
                                   getCursors,
                                   initialCaret,
                                   onCaretMove,
                                 }) {
  let publishedCursor = null;
  let publishedCount = 0;
  let publishedSelection = null;
  let publishedWidth = 4;

  const carets = [initialCaret];
  const drawnOffsets = [-1];

  function position(offset) {
    const before = source.value.slice(0, offset);
    const lineIndex = before.split("\n").length - 1;
    const linePrefix = before.slice(before.lastIndexOf("\n") + 1);
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineIndex + 1}"]`);
    const {top} = getScroll();

    let x = 0;
    if (lineNode) {
      const lineLeft = lineNode.getBoundingClientRect().left;
      const codeLeft = code.getBoundingClientRect().left;
      x = Math.round(lineLeft + advance(lineIndex + 1, linePrefix.length) - codeLeft);
    }
    const y = Math.round(metrics.padTop + lineIndex * metrics.lineHeight - top);
    return {x, y};
  }

  function update() {
    const cursors = getCursors();
    const before = source.value.slice(0, source.selectionStart);
    const lineIndex = before.split("\n").length - 1;
    const linePrefix = before.slice(before.lastIndexOf("\n") + 1);
    const column = displayColumns(linePrefix, store.getState().tabWidth);
    const focused = document.activeElement === source;

    if (focused) {
      const nextPosition = {line: lineIndex + 1, column: column + 1};
      const changed = !publishedCursor
        || publishedCursor.line !== nextPosition.line
        || publishedCursor.column !== nextPosition.column;
      if (changed) {
        publishedCursor = nextPosition;
      }

      const start = source.selectionStart;
      const end = source.selectionEnd;
      const selectionChanged = !publishedSelection
        || publishedSelection.start !== start
        || publishedSelection.end !== end;
      if (selectionChanged) {
        publishedSelection = {start, end};
      }

      const countChanged = publishedCount !== cursors.length;
      if (countChanged) {
        publishedCount = cursors.length;
      }

      if (changed || selectionChanged || countChanged) {
        const patch = {};
        if (changed) {
          patch.cursor = nextPosition;
        }
        if (countChanged) {
          patch.cursorCount = publishedCount;
        }
        if (selectionChanged) {
          const selected = source.value.slice(start, end);
          patch.selection = start === end ? null : {
            characters: selected.length,
            newlines: selected.split("\n").length - 1,
          };
        }
        store.setState(patch);
      }
    }

    const width = indentWidth(source.value);
    if (width !== publishedWidth) {
      publishedWidth = width;
      store.setState({indent: width});
    }

    const wanted = [];
    const drawable = focused && !source.readOnly && !code.hidden;
    if (drawable) {
      for (let index = 0; index < cursors.length; index++) {
        const cursor = cursors[index];
        if (cursor.anchor === cursor.head) {
          wanted.push({offset: cursor.head, shadow: index > 0});
        }
      }
    }

    while (carets.length < wanted.length) {
      const extra = document.createElement("div");
      extra.className = "caret";
      code.appendChild(extra);
      carets.push(extra);
      drawnOffsets.push(-1);
    }

    let blinkChanged = false;
    const visible = [];
    for (let index = 0; index < carets.length; index++) {
      const element = carets[index];
      const entry = wanted[index];
      if (!entry) {
        element.classList.remove("is-visible");
        drawnOffsets[index] = -1;
        continue;
      }

      const {x, y} = position(entry.offset);
      element.style.transform = `translate(${x}px, ${y}px)`;
      element.classList.toggle("is-shadow", entry.shadow);
      element.classList.add("is-visible");
      visible.push(element);

      // Nudge the shared clock only when a caret changes position, never on a
      // scroll frame: a forced reflow here would make the gutter lag the
      // native scroll, and the whole set comes back up solid together.
      if (drawnOffsets[index] !== entry.offset) {
        drawnOffsets[index] = entry.offset;
        blinkChanged = true;
      }
    }
    if (blinkChanged) {
      restartBlink(visible);
    }

    onCaretMove?.();
  }

  return {position, update};
}
