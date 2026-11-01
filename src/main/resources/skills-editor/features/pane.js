import {codeMetrics, displayColumns, FALLBACK_METRICS} from "../core/dom.js";
import {activeDoc} from "../core/store.js";
import {createTabStrip} from "../ui/components/tab.js";
import {bracketDepth} from "./analysis.js";
import {createCompletion} from "./completion.js";
import {createCaretLayer} from "./pane/caret.js";
import {createHighlightLayer} from "./pane/highlight.js";
import {createMeasure} from "./pane/measure.js";
import {createSelectionLayer} from "./pane/selection.js";
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

// One code surface: the gutter, the syntax overlay, the marker layer, the drawn
// caret, and the textarea that owns the real caret and selection. Two of these
// are mounted side by side to show the same buffer twice: each keeps its own
// tab strip, scroll, selection and caret, and redraws only its own overlay.
//
// The surface itself is assembled here; the three drawing layers live under
// features/pane/ and are handed the elements and accessors they need, so none
// of them reaches for a second source of truth.
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

  const measure = createMeasure({highlightCode});

  const highlightLayer = createHighlightLayer({
    store,
    highlightCode,
    gutterInner,
    getText: () => source.value,
    getActiveOffset: () => source.selectionStart,
  });

  // The textarea can only ever hold one real range, so the caret list is the
  // truth and cursors[0] is the entry written back into it: drawing, editing
  // and the status bar all read from here, and the platform's range is just
  // the primary entry seen from outside.
  let cursors = [{anchor: 0, head: 0}];
  let composing = false;

  // Writing the list to the textarea is the only way the platform can learn
  // about the primary range. The reverse read must not mistake that echo for a
  // user action (the low and high edges compare equal), so the anchors
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

  // macOS elastic overscroll can report a negative offset for a moment when a
  // fast scroll hits the top. The text layer cannot follow it, so the overlays
  // clamp and hold still instead of bouncing against the text.
  function scrollOffset() {
    return {
      left: Math.max(0, source.scrollLeft),
      top: Math.max(0, source.scrollTop),
    };
  }

  let completion = null;

  const selectionLayer = createSelectionLayer({
    source,
    highlightCode,
    advance: measure.advance,
  });

  const caretLayer = createCaretLayer({
    store,
    source,
    code,
    highlightCode,
    metrics,
    advance: measure.advance,
    getScroll: scrollOffset,
    getCursors: () => cursors,
    initialCaret: caret,
    onCaretMove: () => completion?.onCaretMove(),
  });

  function updateSelection() {
    selectionLayer.render(cursors);
  }

  function updateCaret() {
    caretLayer.update();
  }

  function updateActiveLine() {
    highlightLayer.updateActiveLine();
  }

  function renderHighlight(text) {
    highlightLayer.render(text);
  }

  function setCursors(next) {
    cursors = normalize(next, source.value);
    writeCursors();
    updateSelection();
    updateActiveLine();
    updateCaret();
  }

  function syncScroll() {
    const {left, top} = scrollOffset();
    highlight.scrollTop = top;
    highlight.scrollLeft = left;
    gutterInner.style.transform = `translateY(${-top}px)`;
    markers.style.transform = `translate(${-left}px, ${-top}px)`;
    updateCaret();
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

  // Writes a buffer this pane did not produce itself (the other pane's typing)
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
    advance: measure.advance,
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

  // A restored snapshot travels the same road an edit does, minus the
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
  // display column: the shortcut marches through the file one line per
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

  // Click point to buffer offset: the line comes from the vertical metrics the
  // caret already trusts, the column from a binary search over `advance` (the
  // same measurement the caret draws with), so a planted caret lands on
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
      if (measure.advance(lineNumber, middle) <= target) {
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
  completion = createCompletion({
    source,
    code,
    store,
    caretPosition: caretLayer.position,
    metrics,
    getCursors: () => cursors,
    commit: commitEdit,
    isComposing: () => composing,
  });

  source.addEventListener("input", (event) => {
    // A native edit (typing, pasting, cutting with one caret) has already
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
  // textarea holds: the default is suppressed (the native selection would
  // otherwise jump to the click and collapse the list), the point is mapped
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
