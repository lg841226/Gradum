// Multi-cursor arithmetic. Every function here is pure: it takes the buffer
// and a cursor list and returns the next buffer and cursor list, so the pane
// only has to decide *when* to edit, never *what* an edit does across several
// carets at once. A cursor is {anchor, head}: the anchor is planted and the
// head travels, which is what lets a backwards selection keep its direction
// through the textarea's one representable range.

import {displayColumns} from "../core/dom.js";

function clamp(offset, limit) {
  return Math.min(Math.max(offset, 0), limit);
}

function rangeOf(cursor) {
  const start = Math.min(cursor.anchor, cursor.head);
  const end = Math.max(cursor.anchor, cursor.head);
  return {start, end};
}

// Sorted by the low edge, clamped, overlap-free. The textarea mirrors the
// first entry, so the lowest range leads. Two ranges that touch at a point or
// overlap become one union, because two carets in the same place have nothing
// between them; adjacent non-empty ranges stay apart — [0,5) and [5,10) are
// two selections, not one. Direction survives everything but a merge, where
// the union is freshly forward.
function normalize(cursors, text) {
  const limit = text.length;
  const sorted = cursors
    .map((cursor) => ({
      anchor: clamp(cursor.anchor, limit),
      head: clamp(cursor.head, limit),
    }))
    .sort((left, right) => {
      const leftStart = Math.min(left.anchor, left.head);
      const rightStart = Math.min(right.anchor, right.head);
      return leftStart - rightStart;
    });

  const merged = [];
  for (const cursor of sorted) {
    const previous = merged[merged.length - 1];
    if (previous) {
      const previousStart = Math.min(previous.anchor, previous.head);
      const previousEnd = Math.max(previous.anchor, previous.head);
      const currentStart = Math.min(cursor.anchor, cursor.head);
      const currentEnd = Math.max(cursor.anchor, cursor.head);
      const previousEmpty = previousStart === previousEnd;
      const currentEmpty = currentStart === currentEnd;
      const inside = currentStart < previousEnd
        || (currentStart === previousEnd && (previousEmpty || currentEmpty));
      if (inside) {
        previous.anchor = previousStart;
        previous.head = Math.max(previousEnd, currentEnd);
        continue;
      }
    }
    merged.push(cursor);
  }
  return merged;
}

function lineIndexOf(text, offset) {
  return text.slice(0, offset).split("\n").length - 1;
}

function lineStartAt(text, offset) {
  return text.lastIndexOf("\n", Math.max(0, offset) - 1) + 1;
}

function lineEndAt(text, offset) {
  const found = text.indexOf("\n", offset);
  return found === -1 ? text.length : found;
}

// One code unit left, skipping a surrogate pair in a single step so an emoji
// is never split down the middle.
function stepLeft(text, offset) {
  if (offset <= 0) {
    return 0;
  }
  const at = offset - 1;
  const isLow = (text.charCodeAt(at) & 0xfc00) === 0xdc00;
  const isHigh = at > 0 && (text.charCodeAt(at - 1) & 0xfc00) === 0xd800;
  return isLow && isHigh ? at - 1 : at;
}

function stepRight(text, offset) {
  if (offset >= text.length) {
    return text.length;
  }
  const isHigh = (text.charCodeAt(offset) & 0xfc00) === 0xd800;
  const isLow = offset + 1 < text.length
    && (text.charCodeAt(offset + 1) & 0xfc00) === 0xdc00;
  return isHigh && isLow ? offset + 2 : offset + 1;
}

function charKind(char) {
  if (/\s/.test(char)) {
    return "space";
  }
  if (/[\p{L}\p{N}_]/u.test(char)) {
    return "word";
  }
  return "punct";
}

// Left end of the run — letters, punctuation or whitespace — that ends at
// `offset`, stepping over a whole run so one press crosses one word.
function wordLeftAt(text, offset) {
  if (offset <= 0) {
    return 0;
  }
  let at = offset - 1;
  const kind = charKind(text[at]);
  while (at > 0 && charKind(text[at - 1]) === kind) {
    at -= 1;
  }
  return at;
}

function wordRightAt(text, offset) {
  if (offset >= text.length) {
    return text.length;
  }
  let at = offset;
  const kind = charKind(text[at]);
  while (at < text.length && charKind(text[at]) === kind) {
    at += 1;
  }
  return at;
}

// Offset on `targetLine` nearest the display column of `column`, walking the
// line and counting tab stops the way the grid draws them. A column the line
// cannot hold lands at its end; a column inside a tab's expansion snaps to
// the stop the tab reaches, never to the middle of it.
function offsetAtColumn(text, targetLine, column) {
  const total = text.split("\n").length;
  const line = Math.min(Math.max(targetLine, 0), total - 1);
  let at = 0;
  for (let index = 0; index < line; index++) {
    at = text.indexOf("\n", at) + 1;
    if (at === 0) {
      return text.length;
    }
  }

  const end = lineEndAt(text, at);
  let width = 0;
  while (at < end && width < column) {
    const next = stepRight(text, at);
    const advance = displayColumns(text.slice(at, next));
    if (width + advance > column) {
      at = next;
      break;
    }
    width += advance;
    at = next;
  }
  return at;
}

// One motion applied to one cursor. A plain (unshifted) motion with a live
// selection collapses it to an empty caret at the side it travels toward —
// left and word-left to the start, right and word-right to the end — before
// any traveling happens. With shift the anchor stays planted and the head
// moves.
function moveCursor(text, cursor, motion, extend) {
  const {start, end} = rangeOf(cursor);
  const hasRange = end > start;

  if (motion === "left" || motion === "right") {
    if (hasRange && !extend) {
      const edge = motion === "left" ? start : end;
      return {anchor: edge, head: edge};
    }
    const head = motion === "left"
      ? stepLeft(text, cursor.head)
      : stepRight(text, cursor.head);
    return extend ? {anchor: cursor.anchor, head} : {anchor: head, head};
  }

  if (motion === "wordLeft" || motion === "wordRight") {
    if (hasRange && !extend) {
      const edge = motion === "wordLeft" ? start : end;
      return {anchor: edge, head: edge};
    }
    const head = motion === "wordLeft"
      ? wordLeftAt(text, cursor.head)
      : wordRightAt(text, cursor.head);
    return extend ? {anchor: cursor.anchor, head} : {anchor: head, head};
  }

  let head;
  if (motion === "lineStart" || motion === "lineEnd") {
    head = motion === "lineStart"
      ? lineStartAt(text, cursor.head)
      : lineEndAt(text, cursor.head);
  } else {
    // Up and down carry the display column across the line change, so a caret
    // threading through a run of tabs keeps its visual track.
    const originStart = lineStartAt(text, cursor.head);
    const column = displayColumns(text.slice(originStart, cursor.head));
    const line = lineIndexOf(text, cursor.head);
    const target = motion === "lineUp" ? line - 1 : line + 1;
    const total = text.split("\n").length;
    if (target < 0) {
      head = 0;
    } else if (target >= total) {
      head = text.length;
    } else {
      head = offsetAtColumn(text, target, column);
    }
  }
  return extend ? {anchor: cursor.anchor, head} : {anchor: head, head};
}

// The insertion at every cursor: each selection is replaced by `insert`, each
// empty cursor just receives it, and every caret lands after what it just
// gained. `insert` may be a function of the untouched buffer and this range's
// start, so each caret can lay down something of its own — Enter's indent
// differs per position — while the rebuild still runs left to right. Ranges
// are known disjoint, so the buffer is rebuilt with one running shift.
function insertText(text, cursors, insert) {
  let buffer = text;
  let shift = 0;
  const next = [];

  for (const cursor of cursors) {
    const {start, end} = rangeOf(cursor);
    const chunk = typeof insert === "function" ? insert(text, start) : insert;
    buffer = buffer.slice(0, start + shift) + chunk + buffer.slice(end + shift);
    const caret = start + shift + chunk.length;
    next.push({anchor: caret, head: caret});
    shift += chunk.length - (end - start);
  }
  return {text: buffer, cursors: next};
}

// Deletion shared by Backspace and Delete, forward and backward, at the
// character, word or line grain. A live selection is deleted first: that is
// what the key would do alone: and only an empty cursor reaches for its
// neighbors. A grain that finds nothing to delete leaves the caret put.
function deleteRanges(text, cursors, direction, grain) {
  let buffer = text;
  let shift = 0;
  const next = [];
  const backward = direction === "back";

  for (const cursor of cursors) {
    const {start, end} = rangeOf(cursor);
    let from;
    let to;

    if (start === end) {
      const at = start + shift;
      if (backward) {
        if (grain === "char") {
          from = stepLeft(buffer, at);
        } else if (grain === "word") {
          from = wordLeftAt(buffer, at);
        } else {
          from = lineStartAt(buffer, at);
        }
        to = at;
      } else {
        if (grain === "char") {
          to = stepRight(buffer, at);
        } else if (grain === "word") {
          to = wordRightAt(buffer, at);
        } else {
          to = lineEndAt(buffer, at);
        }
        from = at;
      }
    } else {
      from = start + shift;
      to = end + shift;
    }

    if (to > from) {
      buffer = buffer.slice(0, from) + buffer.slice(to);
      shift -= to - from;
      next.push({anchor: from, head: from});
    } else {
      next.push({anchor: start + shift, head: start + shift});
    }
  }
  return {text: buffer, cursors: next};
}

export {
  deleteRanges,
  insertText,
  lineEndAt,
  lineIndexOf,
  lineStartAt,
  moveCursor,
  normalize,
  offsetAtColumn,
  rangeOf,
  wordLeftAt,
  wordRightAt,
};
