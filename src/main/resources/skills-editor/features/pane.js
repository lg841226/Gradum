import {codeMetrics, displayColumns, FALLBACK_METRICS} from "../core/dom.js";
import {activeDoc} from "../core/store.js";
import {createTabStrip} from "../ui/components/tab.js";
import {bracketDepth, changedLines, indentWidth, matchPairs} from "./analysis.js";
import {createCompletion} from "./completion.js";
import {markGlyphs, tokenize} from "./syntax.js";
import {
  deleteRanges,
  insertText,
  lineEndAt,
  lineIndexOf,
  lineStartAt,
  moveCursor,
  normalize,
  offsetAtColumn,
  typedBracket,
} from "./multicursor.js";
import {recordEdit, redo as redoHistory, seedHistory, undo as undoHistory} from "./history.js";

// One blink clock for every caret in the document: the off-toggle lands on
// the root, so all carets flip in the same frame and the phases can never
// drift apart. A caret move restarts the clock, so a moved set comes back up
// solid the way a lone caret settles.
let blinkTimer = null;

function restartBlink() {
  document.documentElement.classList.remove("caret-blink-off");
  clearInterval(blinkTimer);
  blinkTimer = setInterval(() => {
    document.documentElement.classList.toggle("caret-blink-off");
  }, 500);
}

// One code surface: the gutter, the syntax overlay, the marker layer, the drawn
// caret, and the textarea that owns the real caret and selection. Two of these
// are mounted side by side to show the same buffer twice — each keeps its own
// tab strip, scroll, selection and caret, and redraws only its own overlay.
export function createCodePane(store, {onInput, onFocus, onRun}) {
  const root = document.createElement("div");
  root.className = "code-pane";

  const tabStrip = createTabStrip();
  const tabs = document.createElement("header");
  tabs.className = "code-pane-tabs";
  tabs.append(tabStrip);

  const gutter = document.createElement("div");
  gutter.className = "gutter";

  const gutterInner = document.createElement("div");
  gutterInner.className = "gutter-inner";
  gutter.appendChild(gutterInner);

  const code = document.createElement("div");
  code.className = "code";

  const highlight = document.createElement("pre");
  highlight.className = "highlight";
  highlight.setAttribute("aria-hidden", "true");

  const highlightCode = document.createElement("code");
  highlight.appendChild(highlightCode);

  const markers = document.createElement("div");
  markers.className = "markers";

  const source = document.createElement("textarea");
  source.className = "source";
  source.wrap = "off";
  source.spellcheck = false;
  source.setAttribute("autocapitalize", "off");
  source.setAttribute("autocomplete", "off");

  const caret = document.createElement("div");
  caret.className = "caret";

  code.append(highlight, markers, caret, source);

  const main = document.createElement("div");
  main.className = "code-pane-main";
  main.append(gutter, code);

  root.append(tabs, main);

  const metrics = {...FALLBACK_METRICS};

  function measureMetrics() {
    Object.assign(metrics, codeMetrics(highlight));
  }

  // The caret and the squiggles are drawn, not laid out by the text layer, so
  // their horizontal offsets are read back off the highlight overlay — the very
  // glyphs on screen — instead of being predicted from a font. Neither counting
  // columns nor measuring with a canvas survives a fallback: a CJK glyph is
  // drawn by whatever font the stack falls through to, and its advance is
  // whatever that font says, which the model has no way to know. A Range over
  // the line's own text nodes answers exactly, and because the range is read
  // after the overlay has been scrolled it already carries the horizontal
  // scroll. Reading a rect does not dirty layout the way a style write would,
  // so a scroll frame stays cheap.

  // The text node and offset that `index` (in UTF-16 units, matching the text
  // layer) falls on within a rendered line.
  function textPositionAt(lineNode, index) {
    let remaining = index;
    const walker = document.createTreeWalker(lineNode, NodeFilter.SHOW_TEXT);

    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (remaining <= node.data.length) {
        return {node, offset: remaining};
      }
      remaining -= node.data.length;
    }
    return null;
  }

  // Width, in pixels, of the first `charCount` characters of the rendered line,
  // measured from the line's own left edge. Tabs, wide glyphs and fallback runs
  // are all handled by the layout engine, so the number matches the glyphs.
  function advance(lineNumber, charCount) {
    if (charCount <= 0) {
      return 0;
    }
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
    if (!lineNode) {
      return 0;
    }
    const position = textPositionAt(lineNode, charCount);
    if (!position) {
      return 0;
    }

    const range = document.createRange();
    range.setStart(lineNode, 0);
    range.setEnd(position.node, position.offset);

    const rangeEnd = range.getBoundingClientRect().right;
    const lineStart = lineNode.getBoundingClientRect().left;
    return Math.max(0, rangeEnd - lineStart);
  }

  // The textarea can only ever hold one real range, so the caret list is the
  // truth and cursors[0] is the entry written back into it: drawing, editing
  // and the status bar all read from here, and the platform's range is just
  // the primary entry seen from outside.
  let cursors = [{anchor: 0, head: 0}];
  let composing = false;

  // Writing the list to the textarea is the only way the platform can learn
  // about the primary range. The reverse read must not mistake that echo for
  // a user action — the low and high edges compare equal — so the anchors
  // behind them, and every caret after them, survive the round trip.
  function writeCursors() {
    const first = cursors[0];
    const start = Math.min(first.anchor, first.head);
    const end = Math.max(first.anchor, first.head);
    const direction = first.anchor > first.head ? "backward" : "forward";
    source.setSelectionRange(start, end, direction);
  }

  function syncCursorsFromSource() {
    const start = source.selectionStart;
    const end = source.selectionEnd;
    const first = cursors[0];
    if (first) {
      const low = Math.min(first.anchor, first.head);
      const high = Math.max(first.anchor, first.head);
      if (low === start && high === end) {
        return;
      }
    }
    cursors = [{anchor: start, head: end}];
  }

  function setCursors(next) {
    cursors = normalize(next, source.value);
    writeCursors();
    updateSelection();
    updateActiveLine();
    updateCaret();
  }

  let bands = [];
  let matches = [];

  function clearSelection() {
    for (const band of bands) {
      band.remove();
    }
    bands = [];
    for (const box of matches) {
      box.remove();
    }
    matches = [];
  }

  // The band is drawn per line from the same measured offsets the caret uses,
  // so it lines up with the glyphs rather than the line box. A selection that
  // runs past a line's end simply stops there: the band is the selected
  // characters, and a line break holds no characters of its own. Each range
  // gets its own child span — not a pseudo-element — because several carets
  // can share a line and a line owns only one ::before. The span stays empty,
  // so the TreeWalker that places the caret never sees it. The bracket pair
  // each collapsed caret touches is boxed the same way, in the same pass: the
  // line HTML it draws on is rebuilt by every re-highlight, so both live or
  // die together here.
  function updateSelection() {
    clearSelection();

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
  }

  // One end of a matched pair: a child of the line it sits on, positioned
  // from the same advance the band uses, so it scrolls with the content and
  // rests under the glyphs.
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

  // macOS elastic overscroll can report a negative offset for a moment when a
  // fast scroll hits the top. The text layer cannot follow it, so the overlays
  // clamp and hold still instead of bouncing against the text.
  function scrollOffset() {
    return {
      left: Math.max(0, source.scrollLeft),
      top: Math.max(0, source.scrollTop),
    };
  }

  function syncScroll() {
    const {left, top} = scrollOffset();
    highlight.scrollTop = top;
    highlight.scrollLeft = left;
    gutterInner.style.transform = `translateY(${-top}px)`;
    markers.style.transform = `translate(${-left}px, ${-top}px)`;
    updateCaret();
  }

  let publishedCursor = null;
  let publishedCount = 0;
  let publishedSelection = null;
  let publishedWidth = 4;

  // Drawn carets, pooled: one per collapsed cursor, grown on demand and
  // hidden — never destroyed — when the list shrinks. The first entry is the
  // caret element mounted with the pane.
  const carets = [caret];
  const drawnOffsets = [-1];

  // Placement for one caret at one buffer offset, from the same measurements
  // the overlay and squiggles use: the line comes from the text before the
  // offset, the horizontal edge from a Range over the rendered line, the
  // vertical from the line box the pane already trusts.
  function caretPosition(offset) {
    const before = source.value.slice(0, offset);
    const lineIndex = before.split("\n").length - 1;
    const linePrefix = before.slice(before.lastIndexOf("\n") + 1);
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineIndex + 1}"]`);
    const {top} = scrollOffset();

    let x = 0;
    if (lineNode) {
      const lineLeft = lineNode.getBoundingClientRect().left;
      const codeLeft = code.getBoundingClientRect().left;
      x = Math.round(lineLeft + advance(lineIndex + 1, linePrefix.length) - codeLeft);
    }
    const y = Math.round(metrics.padTop + lineIndex * metrics.lineHeight - top);
    return {x, y};
  }

  // The native caret can drift against the syntax overlay because the two
  // layers round their fractional line boxes apart, so it is hidden and these
  // carets are drawn from the same metrics the overlay and squiggles use.
  function updateCaret() {
    const before = source.value.slice(0, source.selectionStart);
    const lineIndex = before.split("\n").length - 1;
    const linePrefix = before.slice(before.lastIndexOf("\n") + 1);
    const column = displayColumns(linePrefix);
    const focused = document.activeElement === source;

    // The status bar reads the caret position from the store, so every caret
    // move publishes it — but only for the pane the user is actually in, so the
    // readout follows the focused view rather than whichever pane drew last.
    // The selection rides along in the same patch: the strip appends its length
    // in characters and newlines while a range is selected. The position is the
    // primary range — cursors[0], the one the textarea mirrors — so a screen of
    // carets still reports the one the platform calls home.
    if (focused) {
      const position = {line: lineIndex + 1, column: column + 1};
      const changed = !publishedCursor
        || publishedCursor.line !== position.line
        || publishedCursor.column !== position.column;
      if (changed) {
        publishedCursor = position;
      }

      const start = source.selectionStart;
      const end = source.selectionEnd;
      const selectionChanged = !publishedSelection
        || publishedSelection.start !== start
        || publishedSelection.end !== end;
      if (selectionChanged) {
        publishedSelection = {start, end};
      }

      // The strip swaps the position for the caret count while the list holds
      // more than one, so the headcount rides the same patch.
      const countChanged = publishedCount !== cursors.length;
      if (countChanged) {
        publishedCount = cursors.length;
      }

      if (changed || selectionChanged || countChanged) {
        const patch = {};
        if (changed) {
          patch.cursor = position;
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

    // The file's indent unit belongs to the text, not the caret, so it is
    // published whenever the buffer reads a different width — and only then,
    // since the strip has no use for the same number twice. Focus does not
    // enter into it: the strip reports the open document even with the pane
    // clicked away.
    const width = indentWidth(source.value);
    if (width !== publishedWidth) {
      publishedWidth = width;
      store.setState({indent: width});
    }

    // Which carets are drawable right now: focused, editable, on screen. Only
    // collapsed cursors take a bar — a range shows as its band instead. Each
    // entry also says whether it is a shadow: everything past the primary, the
    // one the textarea mirrors, gets dressed in the accent blue.
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
    for (let index = 0; index < carets.length; index++) {
      const element = carets[index];
      const entry = wanted[index];
      if (!entry) {
        element.classList.remove("is-visible");
        drawnOffsets[index] = -1;
        continue;
      }

      const {x, y} = caretPosition(entry.offset);
      element.style.transform = `translate(${x}px, ${y}px)`;
      element.classList.toggle("is-shadow", entry.shadow);
      element.classList.add("is-visible");

      // Nudge the shared clock only when a caret changes position, never on a
      // scroll frame: a forced reflow here would make the gutter lag the
      // native scroll, and the whole set comes back up solid together.
      if (drawnOffsets[index] !== entry.offset) {
        drawnOffsets[index] = entry.offset;
        blinkChanged = true;
      }
    }
    if (blinkChanged) {
      restartBlink();
    }

    // The completion list rides the caret's coattails: every caret draw
    // revalidates it, so a click, an undo or the other pane's edit dismisses
    // or refreshes the popup in the same frame the caret moves — while the
    // keystroke that may *raise* it answers only from the input, never from
    // this echo.
    completion.onCaretMove();
  }

  function lineNumberAt(offset) {
    return source.value.slice(0, offset).split("\n").length;
  }

  function updateActiveLine() {
    const line = lineNumberAt(source.selectionStart);

    const previousCode = highlightCode.querySelector(".line.active");
    if (previousCode) {
      previousCode.classList.remove("active");
    }
    const previousGutter = gutterInner.querySelector(".gutter-line.active");
    if (previousGutter) {
      previousGutter.classList.remove("active");
    }

    const codeLine = highlightCode.querySelector(`.line[data-line="${line}"]`);
    if (codeLine) {
      codeLine.classList.add("active");
    }
    const gutterLine = gutterInner.querySelector(`.gutter-line[data-line="${line}"]`);
    if (gutterLine) {
      gutterLine.classList.add("active");
    }
  }

  function renderHighlight(text) {
    const lines = [];
    let line = "";

    for (const token of tokenize(text)) {
      token.text.split("\n").forEach((part, index) => {
        if (index > 0) {
          lines.push(line);
          line = "";
        }
        if (!part) {
          return;
        }
        if (token.cls) {
          line += `<span class="${token.cls}">${markGlyphs(part)}</span>`;
        } else {
          line += markGlyphs(part);
        }
      });
    }
    lines.push(line);

    // The gutter wears a bar over every run of lines the buffer has changed
    // since the baseline — computed here because this is the one place the
    // gutter's markup is rebuilt: opening, typing and deploying all land in
    // it, and a bar survives none of them by accident. Consecutive lines are
    // fused into one element, so a block of changes is one pill with a round
    // tip at each end instead of a stack of chips.
    const doc = activeDoc(store.getState());
    const marked = doc && doc.baseline != null ? changedLines(text, doc.baseline) : [];
    const runs = [];
    for (const line of marked) {
      const last = runs[runs.length - 1];
      if (last && line === last.end + 1) {
        last.end = line;
      } else {
        runs.push({start: line, end: line});
      }
    }

    const codeLineHtml = (content, index) =>
      `<span class="line" data-line="${index + 1}">${content}</span>`;
    const gutterLineHtml = (_content, index) =>
      `<span class="gutter-line" data-line="${index + 1}">${index + 1}</span>`;
    const runHtml = runs.map(({start, end}) => {
      const span = end - start + 1;
      const top = `calc(var(--space-3) + ${start - 1} * var(--line-height-code) + 4px)`;
      const height = `calc(${span} * var(--line-height-code) - 8px)`;
      return `<span class="changed-run" style="top: ${top}; height: ${height};"></span>`;
    });

    highlightCode.innerHTML = lines.map(codeLineHtml).join("");
    gutterInner.innerHTML = lines.map(gutterLineHtml).join("") + runHtml.join("");
    updateActiveLine();
  }

  function setSourceVisible(visible) {
    gutter.hidden = !visible;
    code.hidden = !visible;
  }

  function loadDoc(doc, {focus = false} = {}) {
    source.value = doc ? doc.source : "";
    source.readOnly = !doc;
    setSourceVisible(Boolean(doc));
    completion.close();

    // The platform leaves the selection wherever a fresh value puts it; the
    // caret list adopts that as its primary entry, and a document with no
    // history yet seeds it with this exact state so the first undo has a
    // before-picture to return to.
    cursors = [{anchor: source.selectionStart, head: source.selectionEnd}];
    if (doc) {
      seedHistory(doc.id, {text: source.value, cursors: [...cursors]});
    }

    renderHighlight(source.value);
    updateSelection();
    syncScroll();

    if (doc && focus) {
      source.focus();
    }
  }

  // Writes a buffer this pane did not produce itself — the other pane's typing —
  // while keeping this pane's own scroll and (clamped) selection, so a reader's
  // place is not thrown away by an edit on the far side.
  function adoptText(text) {
    const {selectionStart, selectionEnd, scrollTop, scrollLeft} = source;
    source.value = text;

    const limit = text.length;
    source.selectionStart = Math.min(selectionStart, limit);
    source.selectionEnd = Math.min(selectionEnd, limit);
    source.scrollTop = scrollTop;
    source.scrollLeft = scrollLeft;
    cursors = [{anchor: source.selectionStart, head: source.selectionEnd}];
    completion.close();

    renderHighlight(text);
    updateSelection();
    syncScroll();
  }

  const pane = {
    root,
    main,
    source,
    markers,
    metrics,
    tabStrip,
    highlightCode,
    loadDoc,
    advance,
    adoptText,
    syncScroll,
    updateCaret,
    measureMetrics,
    updateSelection,
    renderHighlight,
    updateActiveLine,
    syncCursors: syncCursorsFromSource,
  };

  function currentDocId() {
    const doc = activeDoc(store.getState());
    return doc ? doc.id : null;
  }

  // One edit, one path: buffer, caret list, the platform's range, the store
  // and the history all move together, so no layer can learn about a splice
  // another layer has not heard of.
  function commitEdit(edit) {
    source.value = edit.text;
    cursors = normalize(edit.cursors, edit.text);
    writeCursors();
    onInput(pane);
    const docId = currentDocId();
    if (docId) {
      recordEdit(docId, {text: edit.text, cursors: [...cursors]});
    }
    completion.onEdit();
  }

  // A restored snapshot travels the same road an edit does — minus the
  // history push, which the undo or redo already accounted for.
  function restoreHistory(snapshot) {
    if (!snapshot) {
      return;
    }
    source.value = snapshot.text;
    cursors = normalize(snapshot.cursors, snapshot.text);
    writeCursors();
    onInput(pane);
    completion.onEdit();
  }

  // A caret on the line below (or above) the outermost one, at the same
  // display column — the shortcut marches through the file one line per
  // press, and a line the file does not have leaves the list alone.
  function addCursorVertical(direction) {
    const text = source.value;
    const reference = direction > 0 ? cursors[cursors.length - 1] : cursors[0];
    const originStart = lineStartAt(text, reference.head);
    const column = displayColumns(text.slice(originStart, reference.head));
    const line = lineIndexOf(text, reference.head);
    const target = line + direction;
    const total = text.split("\n").length;
    if (target < 0 || target >= total) {
      return;
    }
    const head = offsetAtColumn(text, target, column);
    setCursors([...cursors, {anchor: head, head}]);
  }

  // Click point → buffer offset: the line comes from the vertical metrics the
  // caret already trusts, the column from a binary search over `advance` —
  // the same measurement the caret draws with — so a planted caret lands on
  // the glyph the pointer hit.
  function offsetFromPoint(clientX, clientY) {
    const text = source.value;
    const codeRect = code.getBoundingClientRect();
    const contentY = clientY - codeRect.top + Math.max(0, source.scrollTop);
    let lineIndex = Math.floor((contentY - metrics.padTop) / metrics.lineHeight);
    const total = text.split("\n").length;
    lineIndex = Math.min(Math.max(lineIndex, 0), total - 1);

    let start = 0;
    for (let index = 0; index < lineIndex; index++) {
      start = text.indexOf("\n", start) + 1;
      if (start === 0) {
        return null;
      }
    }

    const end = lineEndAt(text, start);
    const lineNumber = lineIndex + 1;
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
    if (!lineNode) {
      return null;
    }

    const target = clientX - lineNode.getBoundingClientRect().left;
    if (target < 0) {
      return start;
    }

    let low = 0;
    let high = end - start;
    while (low < high) {
      const middle = Math.floor((low + high + 1) / 2);
      if (advance(lineNumber, middle) <= target) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return start + low;
  }

  // The motion a key requests, resolved on the platform's own habits: Meta
  // walks to the line's edge (macOS), Option or Control walks a word, and the
  // plain arrows travel one character or one line while shift extends.
  function motionOf(event) {
    const wordy = event.altKey || (event.ctrlKey && !event.metaKey);
    if (event.key === "ArrowUp") {
      return "lineUp";
    }
    if (event.key === "ArrowDown") {
      return "lineDown";
    }
    if (event.key === "Home") {
      return "lineStart";
    }
    if (event.key === "End") {
      return "lineEnd";
    }
    if (event.key === "ArrowLeft") {
      if (event.metaKey) {
        return "lineStart";
      }
      return wordy ? "wordLeft" : "left";
    }
    if (event.key === "ArrowRight") {
      if (event.metaKey) {
        return "lineEnd";
      }
      return wordy ? "wordRight" : "right";
    }
    return null;
  }

  // The completion popup is built once per pane, closed over this pane's own
  // textarea, code box and edit path, and driven from the three events below:
  // the input (may raise it), the committed edit (may only refresh it) and
  // the caret draw (may refresh or dismiss it).
  const completion = createCompletion({
    source,
    code,
    store,
    caretPosition,
    metrics,
    getCursors: () => cursors,
    commit: commitEdit,
    isComposing: () => composing,
  });

  source.addEventListener("input", (event) => {
    // A native edit — typing, pasting, cutting with one caret — has already
    // moved the platform's range, so the list collapses to match and the
    // state joins the history as its own entry. Inputs inside a composition
    // skip the record; compositionend books the finished word once.
    if (!event.isComposing && !composing) {
      cursors = [{anchor: source.selectionStart, head: source.selectionEnd}];
      const docId = currentDocId();
      if (docId) {
        recordEdit(docId, {text: source.value, cursors: [...cursors]});
      }
    }
    onInput(pane);
    completion.onTextInput(event);
  });
  source.addEventListener("scroll", syncScroll);
  source.addEventListener("focus", () => {
    updateCaret();
    onFocus(pane);
  });
  source.addEventListener("blur", updateCaret);

  // The platform composes IME text into its one range; several carets would
  // scatter the word wrongly, so the extras step aside first and the composed
  // text lands in exactly one place. The finished composition is booked into
  // history once, after the input that carries it has settled.
  source.addEventListener("compositionstart", () => {
    composing = true;
    completion.close();

    if (cursors.length > 1) {
      cursors = [{anchor: source.selectionStart, head: source.selectionEnd}];
      updateSelection();
      updateCaret();
    }
  });

  source.addEventListener("compositionend", () => {
    composing = false;
    queueMicrotask(() => {
      cursors = [{anchor: source.selectionStart, head: source.selectionEnd}];
      const docId = currentDocId();
      if (docId) {
        recordEdit(docId, {text: source.value, cursors: [...cursors]});
      }
      updateSelection();
      updateCaret();
    });
  });

  // Cmd/Ctrl-click plants another caret without disturbing the one the
  // textarea holds: the default is suppressed — the native selection would
  // otherwise jump to the click and collapse the list — the point is mapped
  // to an offset, and the list grows. A plain press first drops the extras;
  // the platform's own selection change finishes the collapse a moment later.
  source.addEventListener("mousedown", (event) => {
    const modifier = event.metaKey || event.ctrlKey;
    if (cursors.length > 1 && !modifier) {
      cursors = [cursors[0]];
      updateSelection();
      updateCaret();
    }
    if (!modifier || event.altKey || source.readOnly) {
      return;
    }
    event.preventDefault();
    const offset = offsetFromPoint(event.clientX, event.clientY);
    if (offset === null) {
      return;
    }
    if (document.activeElement !== source) {
      source.focus();
    }
    setCursors([...cursors, {anchor: offset, head: offset}]);
  });

  // Holding an arrow key repeats keydown but fires neither keyup nor input, and
  // the text layer only scrolls once the caret reaches an edge: so a caret
  // redrawn on those events alone would sit still through the whole repeat and
  // then jump on release. The caret moves on every repeat, and selectionchange
  // is the one event that reports it; the editor layer listens for it once and
  // routes it to the focused pane.
  source.addEventListener("keydown", (event) => {
    // IME keys belong to the composition; compositionstart has already
    // collapsed the extras, so nothing here may race the platform's own text.
    if (event.isComposing || composing) {
      return;
    }

    const mod = event.metaKey || event.ctrlKey;

    if (mod && !event.shiftKey && (event.key === "z" || event.key === "Z")) {
      event.preventDefault();
      restoreHistory(undoHistory(currentDocId()));
      return;
    }
    if ((mod && event.shiftKey && (event.key === "z" || event.key === "Z"))
      || (event.ctrlKey && (event.key === "y" || event.key === "Y"))) {
      event.preventDefault();
      restoreHistory(redoHistory(currentDocId()));
      return;
    }

    if (mod && event.key === "Enter") {
      event.preventDefault();
      void onRun();
      return;
    }

    if (completion.keydown(event)) {
      return;
    }

    if (mod && event.altKey && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
      event.preventDefault();
      addCursorVertical(event.key === "ArrowDown" ? 1 : -1);
      return;
    }

    if (event.key === "Escape" && cursors.length > 1) {
      event.preventDefault();
      setCursors([cursors[0]]);
      return;
    }

    if (event.key === "Tab") {
      event.preventDefault();
      commitEdit(insertText(source.value, cursors, "  "));
      return;
    }

    if (event.key === "Enter") {
      event.preventDefault();
      const newline = (text, at) => {
        const depth = bracketDepth(text, at);
        return `\n${"  ".repeat(depth)}`;
      };
      commitEdit(insertText(source.value, cursors, newline));
      return;
    }

    // An opener pairs itself and parks the caret in the middle of the two; a
    // closer already waiting in front of an empty caret is stepped over rather
    // than doubled. The step moves no text, so it travels the caret road and
    // leaves the history alone.
    if (!mod && !event.altKey) {
      const bracket = typedBracket(source.value, cursors, event.key);
      if (bracket) {
        event.preventDefault();
        if (bracket.text === source.value) {
          setCursors(bracket.cursors);
        } else {
          commitEdit(bracket);
        }
        return;
      }
    }

    if (cursors.length <= 1) {
      return;
    }

    const motion = motionOf(event);
    if (motion) {
      event.preventDefault();
      setCursors(
        cursors.map((cursor) => moveCursor(source.value, cursor, motion, event.shiftKey)),
      );
      return;
    }

    if (event.key === "Backspace" || event.key === "Delete") {
      event.preventDefault();
      const grain = event.metaKey ? "line" : (event.altKey || event.ctrlKey ? "word" : "char");
      const direction = event.key === "Backspace" ? "back" : "forward";
      commitEdit(deleteRanges(source.value, cursors, direction, grain));
      return;
    }

    if (!mod && !event.altKey && event.key.length === 1) {
      event.preventDefault();
      commitEdit(insertText(source.value, cursors, event.key));
    }
  });

  return pane;
}
