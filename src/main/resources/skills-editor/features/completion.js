import {escapeHtml} from "../core/dom.js";
import {copy} from "../ui/copy.js";
import {icons} from "../ui/icons.js";
import {API_SYMBOLS, SNIPPETS} from "../data/apiSymbols.js";
import {insertText} from "./multicursor.js";
import {KOTLIN_KEYWORDS, tokenize} from "./syntax.js";

// Code completion: a local popup over the buffer's own words, the skill API,
// Kotlin keywords and a few skeletons — filtered as the caret types, accepted
// with Enter or Tab, dismissed with Escape. The list lives inside `.code`
// beside the text layers, so re-rendering the highlight never touches it, and
// it takes its position from the same measurement the drawn caret uses.
//
// Sources are ranked and the result is capped, so the list stays short and
// predictable, and an empty result leaves the popup closed rather than showing
// a shell. The pane drives the popup from three calls: `onTextInput` after a
// native insert (the only one that may raise it), `onEdit` after an edit the
// editor made itself, and `onCaretMove` from the caret draw — a refresh or a
// dismissal, never a new opening, so the caret echo of a keystroke cannot
// reopen a list the keystroke decided against.

const IDENTIFIER = /[A-Za-z0-9_]+/g;
const IDENTIFIER_CHAR = /[A-Za-z0-9_]/;
const MAX_ITEMS = 100;

let listSeq = 0;

export function createCompletion({
                                   source,
                                   code,
                                   store,
                                   caretPosition,
                                   metrics,
                                   getCursors,
                                   commit,
                                   isComposing,
                                 }) {
  const listId = `completion-${++listSeq}`;

  const popup = document.createElement("div");
  popup.className = "completion";
  popup.id = listId;
  popup.hidden = true;
  code.appendChild(popup);

  // The panel is a fixed frame — padding, border, shadow — and the list inside
  // it is what scrolls, so the air between the rows and the rounded border
  // holds still while the rows travel. The listbox role lives on the list: it
  // is the surface the options actually sit in, the way a native select
  // draws one, and aria-activedescendant still finds them from the textarea.
  const list = document.createElement("div");
  list.className = "completion-list";
  list.setAttribute("role", "listbox");
  list.setAttribute("aria-label", copy.completion.label);
  popup.appendChild(list);

  let open = false;
  let items = [];
  let selected = 0;
  let lastText = null;
  let lastCaret = -1;
  let box = {width: 0, height: 0};

  let scanText = null;
  let scanCache = {words: [], guards: []};
  const otherDocs = new Map();

  // One tokenizer run answers two questions about the buffer: which identifier
  // runs it holds (the word source) and where the strings and comments sit
  // (the zones the popup stays silent in). Memoized on the text, so a caret
  // move through an unchanged buffer never re-tokenizes.
  function scan(text) {
    if (text !== scanText) {
      const words = [];
      const guards = [];
      let cursor = 0;
      for (const token of tokenize(text)) {
        const start = cursor;
        cursor += token.text.length;
        if (token.cls === "str" || token.cls === "com") {
          guards.push({start, end: cursor});
        } else if (token.cls !== "kw") {
          addIdentifiers(token.text, start, words);
        }
      }
      scanCache = {words, guards};
      scanText = text;
    }
    return scanCache;
  }

  function addIdentifiers(chunk, offset, into) {
    IDENTIFIER.lastIndex = 0;
    let match;
    while ((match = IDENTIFIER.exec(chunk)) !== null) {
      // A run opening with a digit is a numeric literal — `900`, `0xFF`,
      // `123L` — not a name the caret could reach for, so it never enters the
      // word source. Letter- and underscore-led runs are names by the lexer's
      // own rule and stay.
      if (!/^\d/.test(match[0])) {
        into.push({word: match[0], start: offset + match.index});
      }
    }
  }

  // The other open documents' words, cached per document so a keystroke does
  // not retokenize files the caret is nowhere near.
  function wordsOf(doc) {
    const cached = otherDocs.get(doc.id);
    if (cached && cached.source === doc.source) {
      return cached.words;
    }
    const words = [];
    for (const token of tokenize(doc.source)) {
      if (token.cls === "str" || token.cls === "com" || token.cls === "kw") {
        continue;
      }
      const runs = [];
      addIdentifiers(token.text, 0, runs);
      for (const run of runs) {
        words.push(run.word);
      }
    }
    otherDocs.set(doc.id, {source: doc.source, words});
    return words;
  }

  // The identifier run ending at the offset — the range an accept replaces.
  function wordStartAt(text, offset) {
    let start = offset;
    while (start > 0 && IDENTIFIER_CHAR.test(text[start - 1])) {
      start -= 1;
    }
    return start;
  }

  // Where the caret stands, or null when it stands somewhere the popup must
  // not speak: inside a string or comment the typed run is content, not a
  // name. A dot just before the run switches the list to members — what can
  // follow a dot — with no keywords and no snippets.
  function contextAt(text, caret) {
    const {guards} = scan(text);
    for (const guard of guards) {
      if (caret > guard.start && caret < guard.end) {
        return null;
      }
    }
    const start = wordStartAt(text, caret);
    return {
      start,
      prefix: text.slice(start, caret),
      member: text[start - 1] === ".",
    };
  }

  // Every label the list could offer, picked in precedence so a name the API
  // knows beats the same name the buffer happens to hold: API, snippets,
  // keywords, this document's words, then the other documents'. First label
  // wins, so a duplicate row can never come back twice. The one word left out
  // is the run the caret is standing in: what is being typed should be the
  // question, not the first answer to it.
  function candidates(text, member, tokenStart) {
    const picked = new Map();
    const add = (label, kind, detail, source, insert) => {
      if (!picked.has(label)) {
        picked.set(label, {label, kind, detail, source, insert: insert || label});
      }
    };

    for (const symbol of API_SYMBOLS) {
      if (!member || symbol.member) {
        add(symbol.label, symbol.kind, symbol.detail, 1);
      }
    }
    if (!member) {
      for (const snippet of SNIPPETS) {
        add(snippet.label, snippet.kind, snippet.detail, 3, snippet.insert);
      }
      for (const keyword of KOTLIN_KEYWORDS) {
        add(keyword, "kw", "", 2);
      }
    }
    for (const entry of scan(text).words) {
      if (entry.start !== tokenStart) {
        add(entry.word, "word", "", 0);
      }
    }
    for (const doc of store.getState().docs) {
      if (doc.source !== text) {
        for (const word of wordsOf(doc)) {
          add(word, "word", "", 4);
        }
      }
    }
    return picked.values();
  }

  // The match tiers, the best first: the prefix as typed, the prefix ignoring
  // case, then a camel subsequence so `sbn` finds `skillBookName`. The
  // positions come back with the tier — they are the letters to paint.
  function matchOf(label, prefix, lowerPrefix) {
    if (label.startsWith(prefix)) {
      return {tier: 0, matched: prefixIndices(prefix.length)};
    }
    if (label.toLowerCase().startsWith(lowerPrefix)) {
      return {tier: 1, matched: prefixIndices(prefix.length)};
    }
    const matched = [];
    let cursor = 0;
    for (const char of lowerPrefix) {
      const at = label.toLowerCase().indexOf(char, cursor);
      if (at === -1) {
        return null;
      }
      matched.push(at);
      cursor = at + 1;
    }
    return {tier: 2, matched};
  }

  function prefixIndices(length) {
    const indices = [];
    for (let index = 0; index < length; index++) {
      indices.push(index);
    }
    return indices;
  }

  // Tier first, then where the label came from, then the shorter spelling and
  // finally the alphabet — short exact words in front of long lookalikes, and
  // a stable order between keystrokes. Capped so one letter cannot drag a wall
  // of rows into the pane.
  function collect(text, context) {
    const lowerPrefix = context.prefix.toLowerCase();
    const matches = [];
    for (const item of candidates(text, context.member, context.start)) {
      const match = matchOf(item.label, context.prefix, lowerPrefix);
      if (match) {
        matches.push({item, match});
      }
    }
    matches.sort(byRank);
    return matches.slice(0, MAX_ITEMS);
  }

  function byRank(left, right) {
    return (
      left.match.tier - right.match.tier
      || left.item.source - right.item.source
      || left.item.label.length - right.item.label.length
      || (left.item.label < right.item.label ? -1 : 1)
    );
  }

  function render() {
    list.innerHTML = items.map(rowHtml).join("");
    list.scrollTop = 0;
    box = {width: popup.offsetWidth, height: popup.offsetHeight};
    updateSelection();
  }

  function rowHtml(entry, index) {
    const {item, match} = entry;
    const selectedClass = index === selected ? " is-selected" : "";
    const detail = item.detail
      ? `<span class="completion-detail">${escapeHtml(item.detail)}</span>`
      : "";
    return (
      `<div class="completion-row${selectedClass}" role="option"`
      + ` id="${listId}-${index}" data-index="${index}"`
      + ` aria-selected="${index === selected}">`
      + kindMark(item.kind)
      + `<span class="completion-label">${labelHtml(item.label, match.matched)}</span>`
      + detail
      + "</div>"
    );
  }

  function kindMark(kind) {
    if (kind === "kw") {
      return `<span class="completion-kind completion-kind-kw"`
        + ` aria-hidden="true">K</span>`;
    }
    const path = icons[`completion-${kind}`] || icons["completion-word"];
    return (
      `<svg class="icon completion-icon completion-kind-${kind}" width="16" height="16"`
      + ' viewBox="0 0 16 16" fill="none" aria-hidden="true">'
      + path
      + "</svg>"
    );
  }

  // The label with its matched letters wrapped — the prefix as a run for the
  // first two tiers, scattered characters for a camel subsequence.
  function labelHtml(label, matched) {
    let html = "";
    let previous = 0;
    for (const index of matched) {
      if (index > previous) {
        html += escapeHtml(label.slice(previous, index));
      }
      html += `<span class="match">${escapeHtml(label[index])}</span>`;
      previous = index + 1;
    }
    return html + escapeHtml(label.slice(previous));
  }

  function updateSelection() {
    for (let index = 0; index < list.children.length; index++) {
      const row = list.children[index];
      const isSelected = index === selected;
      row.classList.toggle("is-selected", isSelected);
      row.setAttribute("aria-selected", String(isSelected));
    }
    source.setAttribute("aria-activedescendant", `${listId}-${selected}`);
    const row = list.children[selected];
    if (row) {
      if (row.offsetTop < list.scrollTop) {
        list.scrollTop = row.offsetTop;
      } else if (row.offsetTop + row.offsetHeight > list.scrollTop + list.clientHeight) {
        list.scrollTop = row.offsetTop + row.offsetHeight - list.clientHeight;
      }
    }
  }

  // The popup hangs off the caret the way the caret hangs off the text: below
  // the line while there is room, above it when there is not, clamped into the
  // code box on both axes. Width and height are measured once per render and
  // reused, so following a scroll costs no layout.
  function place() {
    const {x, y} = caretPosition(source.selectionStart);
    const maxLeft = Math.max(0, code.clientWidth - box.width);
    const maxTop = Math.max(0, code.clientHeight - box.height);
    let top = y + metrics.lineHeight + 2;
    if (top + box.height > code.clientHeight) {
      top = y - 2 - box.height;
    }
    top = Math.min(Math.max(top, 0), maxTop);
    const left = Math.min(Math.max(x, 0), maxLeft);
    popup.style.transform = `translate(${left}px, ${top}px)`;
  }

  function close() {
    if (!open) {
      return;
    }
    open = false;
    items = [];
    selected = 0;
    popup.hidden = true;
    source.setAttribute("aria-expanded", "false");
    source.removeAttribute("aria-activedescendant");
  }

  // One gate for every event. The caret's context decides the list, and
  // `allowOpen` separates the keystroke that may raise the popup from the
  // caret move that may only refresh or dismiss what is already up;
  // `allowEmpty` is Ctrl+Space asking with no prefix at all.
  function revalidate(allowOpen, allowEmpty = false) {
    if (!open && !allowOpen) {
      return;
    }
    if (isComposing() || source.readOnly || document.activeElement !== source) {
      close();
      return;
    }
    const text = source.value;
    const caret = source.selectionStart;
    if (open && text === lastText && caret === lastCaret) {
      place();
      return;
    }
    lastText = text;
    lastCaret = caret;

    const context = contextAt(text, caret);
    if (!context) {
      close();
      return;
    }
    if (!open && !(allowOpen && (context.prefix.length > 0 || allowEmpty))) {
      return;
    }
    const matches = collect(text, context);
    if (matches.length === 0) {
      close();
      return;
    }

    if (open) {
      // Keep the highlighted row on the same label when it survived the
      // refilter, so a keystroke refines the list instead of resetting it.
      const keep = items[selected] ? items[selected].item.label : null;
      items = matches;
      const survived = keep
        ? items.findIndex((entry) => entry.item.label === keep)
        : -1;
      selected = survived >= 0 ? survived : Math.min(selected, items.length - 1);
    } else {
      open = true;
      items = matches;
      selected = 0;
      popup.hidden = false;
      source.setAttribute("aria-expanded", "true");
    }
    render();
    place();
  }

  function move(delta) {
    selected = Math.min(Math.max(selected + delta, 0), items.length - 1);
    updateSelection();
  }

  // Accept runs through the pane's own edit path: the identifier run before
  // each caret — not only the primary's — is replaced by the item's insert
  // text, so several carets spell the same word in one stroke and one undo
  // takes the stroke back. Ranges that would overlap (two carets inside one
  // word) fall back to the selections themselves rather than corrupt the
  // splice the shared edit path performs.
  function accept() {
    const entry = items[selected];
    if (!entry) {
      close();
      return;
    }
    const text = source.value;
    const selections = getCursors().map((cursor) => ({
      anchor: Math.min(cursor.anchor, cursor.head),
      head: Math.max(cursor.anchor, cursor.head),
    }));
    let replacements = selections.map((range) => ({
      anchor: wordStartAt(text, range.anchor),
      head: range.head,
    }));

    let previous = 0;
    let overlap = false;
    for (const range of replacements) {
      if (range.anchor < previous) {
        overlap = true;
        break;
      }
      previous = range.head;
    }
    if (overlap) {
      replacements = selections;
    }

    close();
    commit(insertText(text, replacements, entry.item.insert));
  }

  // The popup answers for a small set of keys while it is up — navigation,
  // accept, dismiss — and asks for Ctrl+Space whether it is up or not. Every
  // other key falls through to the pane's own matrix: arrows still move the
  // caret (and the caret move revalidates the list), typed characters reach
  // the textarea and come back through the input event.
  function keydown(event) {
    if (event.isComposing) {
      return false;
    }
    if (event.code === "Space" && event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      revalidate(true, true);
      return true;
    }
    if (!open) {
      return false;
    }
    if (event.metaKey || event.ctrlKey || event.altKey) {
      return false;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return true;
    }
    if (event.key === "ArrowDown" || event.key === "PageDown") {
      event.preventDefault();
      move(event.key === "PageDown" ? 10 : 1);
      return true;
    }
    if (event.key === "ArrowUp" || event.key === "PageUp") {
      event.preventDefault();
      move(event.key === "PageUp" ? -10 : -1);
      return true;
    }
    if (event.key === "Enter" || event.key === "Tab") {
      event.preventDefault();
      accept();
      return true;
    }
    return false;
  }

  // A press on a row accepts it — and is suppressed first, so the textarea
  // never loses the focus to accept is about to use. A press on the popup's
  // own empty space is left alone: it blurs like any click, and the blur
  // closes the list.
  popup.addEventListener("mousedown", (event) => {
    const row = event.target.closest(".completion-row");
    if (!row) {
      return;
    }
    event.preventDefault();
    selected = Number(row.dataset.index);
    accept();
  });

  popup.addEventListener("mousemove", (event) => {
    const row = event.target.closest(".completion-row");
    if (!row) {
      return;
    }
    const index = Number(row.dataset.index);
    if (index !== selected && Number.isInteger(index)) {
      selected = index;
      updateSelection();
    }
  });

  return {
    close,
    keydown,
    onTextInput(event) {
      const inserted = !event.inputType || event.inputType.startsWith("insert");
      revalidate(inserted);
    },
    onEdit() {
      revalidate(false);
    },
    onCaretMove() {
      revalidate(false);
    },
  };
}
