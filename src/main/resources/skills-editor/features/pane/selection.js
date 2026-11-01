// The selection bands and bracket-pair boxes drawn over the highlight overlay.
//
// The band is drawn per line from the same measured offsets the caret uses, so
// it lines up with the glyphs rather than the line box. A selection that runs
// past a line's end simply stops there: the band is the selected characters,
// and a line break holds no characters of its own. Each range gets its own
// child span, not a pseudo-element, because several carets can share a line and
// a line owns only one ::before. The span stays empty, so the TreeWalker that
// places the caret never sees it. The bracket pair each collapsed caret touches
// is boxed the same way, in the same pass: the line markup it draws on is
// rebuilt by every re-highlight, so both live or die together here.

import {matchPairs} from "../analysis.js";

export function createSelectionLayer({source, highlightCode, advance}) {
  let bands = [];
  let matches = [];

  function clear() {
    for (const band of bands) {
      band.remove();
    }
    bands = [];
    for (const box of matches) {
      box.remove();
    }
    matches = [];
  }

  // One end of a matched pair: a child of the line it sits on, positioned from
  // the same advance the band uses, so it scrolls with the content and rests
  // under the glyphs.
  function drawMatch(text, offset) {
    const before = text.slice(0, offset);
    const row = before.split("\n");
    const lineNumber = row.length;
    const column = row[row.length - 1].length;
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
    if (!lineNode) {
      return;
    }

    const left = advance(lineNumber, column);
    const right = advance(lineNumber, column + 1);
    if (right <= left) {
      return;
    }

    const box = document.createElement("span");
    box.className = "match";
    box.style.left = `${left}px`;
    box.style.width = `${right - left}px`;
    lineNode.appendChild(box);
    matches.push(box);
  }

  function render(cursors) {
    clear();

    const text = source.value;
    for (const cursor of cursors) {
      const start = Math.min(cursor.anchor, cursor.head);
      const end = Math.max(cursor.anchor, cursor.head);
      if (end <= start) {
        continue;
      }

      const head = text.slice(0, start).split("\n");
      let lineNumber = head.length;
      let lineStart = start - head[head.length - 1].length;

      while (lineStart < end) {
        let lineEnd = text.indexOf("\n", lineStart);
        if (lineEnd === -1) {
          lineEnd = text.length;
        }

        const from = Math.max(start, lineStart) - lineStart;
        const to = Math.min(end, lineEnd) - lineStart;
        const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
        if (lineNode && to > from) {
          const left = advance(lineNumber, from);
          const band = document.createElement("span");
          band.className = "band";
          band.style.left = `${left}px`;
          band.style.width = `${advance(lineNumber, to) - left}px`;
          lineNode.appendChild(band);
          bands.push(band);
        }

        lineStart = lineEnd + 1;
        lineNumber += 1;
      }
    }

    for (const [open, close] of matchPairs(text, cursors)) {
      drawMatch(text, open);
      drawMatch(text, close);
    }

    // Where two boxes meet (an empty pair reads as one merged block), their
    // facing borders would double into a seam, so those two edges come off and
    // only the outer frame remains. Boxes are compared by measured position
    // rather than by drawing order, because the drawing follows the pairs and
    // a nested pair draws its outer end first.
    const byLine = new Map();
    for (const box of matches) {
      const siblings = byLine.get(box.parentElement);
      if (siblings) {
        siblings.push(box);
      } else {
        byLine.set(box.parentElement, [box]);
      }
    }
    for (const siblings of byLine.values()) {
      siblings.sort((a, b) => parseFloat(a.style.left) - parseFloat(b.style.left));
      for (let index = 1; index < siblings.length; index++) {
        const left = siblings[index - 1];
        const right = siblings[index];
        const leftEdge = parseFloat(left.style.width) + parseFloat(left.style.left);
        const gap = leftEdge - parseFloat(right.style.left);
        if (Math.abs(gap) < 0.5) {
          left.style.borderRightWidth = "0";
          right.style.borderLeftWidth = "0";
        }
      }
    }
  }

  return {render, clear};
}
