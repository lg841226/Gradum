import {codeMetrics, displayColumns, FALLBACK_METRICS} from "../core/dom.js";
import {createTabStrip} from "../ui/components/tab.js";
import {bracketDepth} from "./analysis.js";
import {createCompletion} from "./completion.js";
import {languageFor} from "./languages/registry.js";
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
// caret + the textarea that owns the real caret + selection. Two of these
// are mounted side by side to show the same buffer twice: each keeps its own
// tab strip, scroll, selection + caret, and redraws only its own overlay.
//
// The surface itself is assembled here; the three drawing layers live under
// features/pane/ and are handed the elements + accessors they need, so none
// of them reaches for a second source of truth.
export function createCodePane(store, {onInput, onFocus, getActiveDoc}) {
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
    getActiveDoc,
  });

  // The textarea can only ever hold one real range, so the caret list is the
  // truth and cursors[0] is the entry written back into it: drawing, editing +
  // the status bar all read from here, and the platform's range is just
  // the primary entry seen from outside.
  let cursors = [{anchor: 0, head: 0}];
  let composing = false;

  // Writing the list to the textarea is the only way the platform can learn
  // about the primary range. The reverse read must not mistake that echo for a
  // user action (the low + high edges compare equal), so the anchors
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
    getTokenize: () => currentLanguage().tokenize,
    getState: () => store.getState(),
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

  let renderedPairs = store.getState().highlightPairs !== false;
  store.subscribe((state) => {
    const pairsOn = state.highlightPairs !== false;
    if (pairsOn !== renderedPairs) {
      renderedPairs = pairsOn;
      updateSelection();
    }
  });

  function updateCaret() {
    caretLayer.update();
  }

  function updateActiveLine() {
    highlightLayer.updateActiveLine();
  }

  function renderHighlight(text) {
    highlightLayer.render(text);
  }

  function setDiagnostics(diagnostics, text) {
    markers.replaceChildren();
    highlightCode
      .querySelectorAll(".errline, .warnline")
      .forEach((node) => node.classList.remove("errline", "warnline"));
    if (diagnostics.length === 0) {
      return;
    }

    const lines = text.split("\n");
    for (const diagnostic of diagnostics) {
      if (!diagnostic.line) {
        continue;
      }

      const lineNode = highlightCode.querySelector(`.line[data-line="${diagnostic.line}"]`);
      if (lineNode) {
        lineNode.classList.add(diagnostic.severity === "error" ? "errline" : "warnline");
      }

      const line = lines[diagnostic.line - 1] || "";
      const column = Math.max(1, diagnostic.column || 1);
      const start = measure.advance(diagnostic.line, Math.min(column - 1, line.length));
      const end = measure.advance(diagnostic.line, line.length);

      const isError = diagnostic.severity === "error";
      const marker = document.createElement("div");
      marker.className = `squiggle ${isError ? "error" : "warning"}`;
      marker.style.top = `${metrics.padTop + (diagnostic.line - 1) * metrics.lineHeight}px`;
      marker.style.left = `${metrics.padLeft + start}px`;
      marker.style.width = `${Math.max(1, end - start)}px`;
      marker.style.height = `${metrics.lineHeight}px`;
      markers.appendChild(marker);
    }
  }

  function revealLine(lineNumber, column) {
    if (!lineNumber) {
      return;
    }

    const text = source.value;
    let offset = 0;
    for (let line = 1; line < lineNumber; line++) {
      const next = text.indexOf("\n", offset);
      if (next === -1) {
        offset = text.length;
        break;
      }
      offset = next + 1;
    }

    let end = text.indexOf("\n", offset);
    if (end === -1) {
      end = text.length;
    }

    const caret = Math.min(offset + Math.max(0, (column || 1) - 1), end);
    source.focus();
    source.setSelectionRange(caret, end);
    source.scrollTop = Math.max(0, (lineNumber - 3) * metrics.lineHeight);
    syncScroll();
    updateActiveLine();
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
    adoptText,
    syncScroll,
    updateCaret,
    measureMetrics,
    updateSelection,
    renderHighlight,
    updateActiveLine,
    setDiagnostics,
    revealLine,
    advance: measure.advance,
    syncCursors: syncCursorsFromSource,
  };

  function currentDocId() {
    const doc = getActiveDoc(store.getState());
    return doc ? doc.id : null;
  }

  function currentLanguage() {
    const doc = getActiveDoc(store.getState());
    return languageFor(doc ? doc.name : null);
  }

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

  function addCursorVertical(direction) {
    const text = source.value;
    const reference = direction > 0 ? cursors[cursors.length - 1] : cursors[0];
    const originStart = lineStartAt(text, reference.head);
    const tabWidth = store.getState().tabWidth;
    const column = displayColumns(text.slice(originStart, reference.head), tabWidth);
    const line = lineIndexOf(text, reference.head);
    const target = line + direction;
    const total = text.split("\n").length;
    if (target < 0 || target >= total) {
      return;
    }
    const head = offsetAtColumn(text, target, column, tabWidth);
    setCursors([...cursors, {anchor: head, head}]);
  }

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

  completion = createCompletion({
    code,
    store,
    source,
    metrics,
    commit: commitEdit,
    getCursors: () => cursors,
    isComposing: () => composing,
    caretPosition: caretLayer.position,
    getActiveDoc,
  });

  source.addEventListener("input", (event) => {
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

  source.addEventListener("keydown", (event) => {

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
      commitEdit(insertText(source.value, cursors, " ".repeat(currentLanguage().indentUnit)));
      return;
    }

    if (event.key === "Enter" && !mod) {
      event.preventDefault();
      const language = currentLanguage();
      const unit = " ".repeat(language.indentUnit);
      const newline = (text, at) => {
        const depth = bracketDepth(text, at, language.tokenize);
        return `\n${unit.repeat(depth)}`;
      };
      commitEdit(insertText(source.value, cursors, newline));
      return;
    }

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
      const tabWidth = store.getState().tabWidth;
      setCursors(
        cursors.map((cursor) => moveCursor(source.value, cursor, motion, event.shiftKey, tabWidth)),
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
