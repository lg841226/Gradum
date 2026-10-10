import {tokenize} from "./syntax.js";

// Buffer analysis, all of it pure: which brackets pair across the strings
// and comments the scan skips, how wide one indent level in this file is,
// and which lines an edit has changed since the baseline. The pane asks,
// this file answers, and nothing here touches the document.

const BRACKET_OPEN = "([{";
const BRACKET_CLOSE = ")]}";

// Every bracket outside a string or comment, with its buffer offset. The
// match scan reads this list instead of the raw characters, so a paren the
// overlay paints as part of a string is not a bracket at all. The list is
// memoised on the text, because the caret moves far more often than the
// buffer changes.
let bracketCacheText = null;
let bracketCache = [];

function bracketRuns(text) {
  if (text === bracketCacheText) {
    return bracketCache;
  }

  const found = [];
  let offset = 0;
  for (const token of tokenize(text)) {
    if (token.cls !== "str" && token.cls !== "com") {
      for (let index = 0; index < token.text.length; index++) {
        const character = token.text[index];
        if (BRACKET_OPEN.includes(character) || BRACKET_CLOSE.includes(character)) {
          found.push({character, at: offset + index});
        }
      }
    }
    offset += token.text.length;
  }

  bracketCacheText = text;
  bracketCache = found;
  return found;
}

// The other end of the pair this bracket belongs to, or null when the run
// around it does not balance: a closer met on the way that does not answer
// the one on top of the stack ends the search, so half a pair draws nothing.
function bracketPartner(brackets, origin) {
  const openIndex = BRACKET_OPEN.indexOf(brackets[origin].character);

  if (openIndex !== -1) {
    const stack = [BRACKET_CLOSE[openIndex]];
    for (let index = origin + 1; index < brackets.length; index++) {
      const next = brackets[index].character;
      if (next === stack[stack.length - 1]) {
        stack.pop();
        if (stack.length === 0) {
          return brackets[index].at;
        }
      } else if (BRACKET_OPEN.includes(next)) {
        stack.push(BRACKET_CLOSE[BRACKET_OPEN.indexOf(next)]);
      } else {
        return null;
      }
    }
    return null;
  }

  const stack = [BRACKET_OPEN[BRACKET_CLOSE.indexOf(brackets[origin].character)]];
  for (let index = origin - 1; index >= 0; index--) {
    const next = brackets[index].character;
    if (next === stack[stack.length - 1]) {
      stack.pop();
      if (stack.length === 0) {
        return brackets[index].at;
      }
    } else if (BRACKET_CLOSE.includes(next)) {
      stack.push(BRACKET_OPEN[BRACKET_CLOSE.indexOf(next)]);
    } else {
      return null;
    }
  }
  return null;
}

// The pairs to box: one per caret that touches a bracket (the character
// before it first, the one under it otherwise), deduplicated, because two
// cursors standing on one pair light the same two boxes.
export function matchPairs(text, cursors) {
  const brackets = bracketRuns(text);
  if (brackets.length < 2) {
    return [];
  }

  const indexByOffset = new Map();
  for (let index = 0; index < brackets.length; index++) {
    indexByOffset.set(brackets[index].at, index);
  }

  const pairs = new Map();
  for (const cursor of cursors) {
    if (cursor.anchor !== cursor.head) {
      continue;
    }

    let origin = indexByOffset.get(cursor.head - 1);
    if (origin === undefined) {
      origin = indexByOffset.get(cursor.head);
    }
    if (origin === undefined) {
      continue;
    }

    const partner = bracketPartner(brackets, origin);
    if (partner === null) {
      continue;
    }

    const open = Math.min(brackets[origin].at, partner);
    const close = Math.max(brackets[origin].at, partner);
    pairs.set(open, close);
  }

  return [...pairs.entries()];
}

// The nesting a caret sits in: opens minus closes over the brackets before
// it (strings and comments skipped by the same scan the match boxes use),
// so Enter lays the next line at that level, two spaces a level the way Tab
// indents. A run with more closes than opens reads as zero rather than a
// negative hang.
export function bracketDepth(text, offset) {
  let depth = 0;
  for (const bracket of bracketRuns(text)) {
    if (bracket.at >= offset) {
      break;
    }
    depth += BRACKET_OPEN.includes(bracket.character) ? 1 : -1;
  }
  return Math.max(0, depth);
}

// Greatest common divisor, for the indent probe below.
// The file's indent unit: the width one level is written in, a property of
// the whole buffer rather than of any caret. Candidates run from the widest
// leading run in the file down to two, and the first one at least eighty
// percent of the indented lines are a multiple of wins: a KDoc ` * ` line or
// a string continuation with an odd offset does not drag the answer to one,
// while a file laid out in fours reads four however the carets wander. A file
// that indents nothing (empty or flat) reads the default of four, so the
// strip never has to say zero.
export function indentWidth(text) {
  const leads = new Map();
  let widest = 0;
  for (const line of text.split("\n")) {
    const lead = /^ */.exec(line)[0].length;
    if (lead === 0) {
      continue;
    }
    leads.set(lead, (leads.get(lead) || 0) + 1);
    widest = Math.max(widest, lead);
  }

  let total = 0;
  for (const count of leads.values()) {
    total += count;
  }

  for (let unit = Math.min(widest, 16); unit >= 2; unit--) {
    let covered = 0;
    for (const [lead, count] of leads) {
      if (lead % unit === 0) {
        covered += count;
      }
    }
    if (covered >= total * 0.8) {
      return unit;
    }
  }
  return 4;
}

// The lines an edit has touched since the buffer's baseline, as one-based
// numbers in document order: a line counts as modified when the longest
// common subsequence of baseline and buffer lines cannot claim it: an
// insertion marks the new line, a rewrite marks the line that replaced the
// old one, and the walk runs backwards so the answer comes out in order.
// Skill files are small, so the table fits; a file big enough to make it a
// problem simply goes unmarked rather than stalling a keystroke.
export function changedLines(text, baseline) {
  if (text === baseline || baseline == null) {
    return [];
  }

  const now = text.split("\n");
  const then = baseline.split("\n");
  const rows = then.length + 1;
  const cols = now.length + 1;
  if (rows * cols > 1_000_000) {
    return [];
  }

  const table = new Uint32Array(rows * cols);
  for (let i = 1; i < rows; i++) {
    const row = i * cols;
    const above = (i - 1) * cols;
    for (let j = 1; j < cols; j++) {
      table[row + j] = then[i - 1] === now[j - 1]
        ? table[above + j - 1] + 1
        : Math.max(table[above + j], table[row + j - 1]);
    }
  }

  const marked = [];
  let i = then.length;
  let j = now.length;
  while (i > 0 && j > 0) {
    if (then[i - 1] === now[j - 1]) {
      i -= 1;
      j -= 1;
    } else if (table[(i - 1) * cols + j] >= table[i * cols + (j - 1)]) {
      i -= 1;
    } else {
      marked.push(j);
      j -= 1;
    }
  }
  while (j > 0) {
    marked.push(j);
    j -= 1;
  }
  return marked.reverse();
}
