import {codeMetrics, escapeHtml, FALLBACK_METRICS} from "../core/dom.js";
import {activeDoc, withDoc} from "../core/store.js";
import {buildSkill, deploySkill, listSkills} from "../data/skillsApi.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createPanel} from "../ui/components/panel.js";
import {createTabStrip} from "../ui/components/tab.js";
import {copy} from "../ui/copy.js";
import {dragPointer} from "./splitter.js";

const KOTLIN_KEYWORDS = [
  "package", "import", "class", "object", "interface", "fun", "val", "var",
  "override", "private", "public", "protected", "internal", "return", "if", "else", "when",
  "for", "while", "do", "try", "catch", "finally", "throw", "is", "in", "as", "null", "true",
  "false", "this", "super", "open", "data", "sealed", "enum", "companion", "abstract", "const",
  "lateinit", "by", "where", "typealias", "init", "constructor", "break", "continue", "out",
  "reified", "inline", "suspend", "crossinline", "noinline", "vararg", "operator", "infix",
  "external", "annotation", "actual", "expect", "tailrec",
];

const TOKEN_CLASS = {
  1: "com",
  2: "str",
  3: "ann",
  4: "num",
  5: "kw",
  6: "type",
  7: "fn",
};

// Compiler output caps the diagnostics it prints inline; anything past this
// count lives in the Problems panel, which lists them all.
const MAX_LOG_PROBLEMS = 6;

// The share of the body the first pane keeps when the divider is dragged. Held
// between a fifth and four fifths, so neither pane can be dragged shut — closing
// one outright is what the split toggle is for.
const MIN_SHARE = 0.2;
const MAX_SHARE = 0.8;

const TOKEN_PATTERN = new RegExp(
  [
    String.raw`(\/\/[^\n]*|\/\*[\s\S]*?\*\/)`,
    String.raw`("""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')`,
    String.raw`(@[A-Za-z_][A-Za-z0-9_]*)`,
    String.raw`(\b\d[\w.]*[LlFfDd]?\b)`,
    `\\b(${KOTLIN_KEYWORDS.join("|")})\\b`,
    String.raw`(\b[A-Z][A-Za-z0-9_]*\b)`,
    String.raw`(\b[a-z_][A-Za-z0-9_]*)(?=\s*\()`,
  ].join("|"),
  "g",
);

function classOf(match) {
  for (let group = 1; group < match.length; group++) {
    if (match[group] !== undefined) {
      return TOKEN_CLASS[group];
    }
  }
  return null;
}

function tokenize(text) {
  const tokens = [];
  let cursor = 0;
  let match;

  TOKEN_PATTERN.lastIndex = 0;
  while ((match = TOKEN_PATTERN.exec(text)) !== null) {
    if (match.index > cursor) {
      tokens.push({text: text.slice(cursor, match.index), cls: null});
    }
    tokens.push({text: match[0], cls: classOf(match)});
    cursor = match.index + match[0].length;
  }
  if (cursor < text.length) {
    tokens.push({text: text.slice(cursor), cls: null});
  }
  return tokens;
}

// East Asian wide and fullwidth ranges. These are the glyphs a monospace grid
// draws two cells wide, which is how a column is counted for the status bar.
// The class is assembled from fragments so no single line runs past the column
// limit.
const WIDE_GLYPH = new RegExp(
  [
    String.raw`[\u1100-\u115F\u2E80-\u303E\u3041-\u33FF\u3400-\u4DBF`,
    String.raw`\u4E00-\u9FFF\uA000-\uA4CF\uAC00-\uD7A3`,
    String.raw`\uF900-\uFAFF\uFE30-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]`,
  ].join(""),
);

// Column the caret sits in, counted the way a monospace grid counts rather
// than by character: a wide glyph holds two columns, and a tab runs to the
// next stop four columns along. This is what the status bar reports, so it is
// deliberately not the same number as the pixel offset `advance` returns.
function displayColumns(text) {
  let column = 0;

  for (const char of text) {
    if (char === "\t") {
      column += 4 - (column % 4);
    } else if (WIDE_GLYPH.test(char)) {
      column += 2;
    } else {
      column += 1;
    }
  }
  return column;
}

// Anything outside ASCII is boxed in the overlay, so a fullwidth （ is not read
// as an ASCII ( at a glance: the two are near-identical at code size and the
// glyph shape alone does not separate them. Escaping runs first, so the tags
// added here are never themselves boxed and an entity's ASCII characters are
// left alone — only the glyphs the file actually holds are wrapped.
function markGlyphs(text) {
  return escapeHtml(text).replace(
    /[^\x00-\x7F]+/g,
    (run) => `<span class="glyph-box">${run}</span>`,
  );
}

// One code surface: the gutter, the syntax overlay, the marker layer, the drawn
// caret, and the textarea that owns the real caret and selection. Two of these
// are mounted side by side to show the same buffer twice — each keeps its own
// tab strip, scroll, selection and caret, and redraws only its own overlay.
function createCodePane(store, {onInput, onFocus, onRun}) {
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

  let selectedLines = [];

  function clearSelection() {
    for (const lineNode of selectedLines) {
      lineNode.classList.remove("has-selection");
      lineNode.style.removeProperty("--selection-left");
      lineNode.style.removeProperty("--selection-width");
    }
    selectedLines = [];
  }

  // The band is drawn per line from the same measured offsets the caret uses, so
  // it lines up with the glyphs rather than the line box. A selection that runs
  // past a line's end simply stops there: the band is the selected characters,
  // and a line break holds no characters of its own.
  function updateSelection() {
    clearSelection();

    const start = source.selectionStart;
    const end = source.selectionEnd;
    if (end <= start) {
      return;
    }

    const text = source.value;
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
        lineNode.style.setProperty("--selection-left", `${left}px`);
        lineNode.style.setProperty("--selection-width", `${advance(lineNumber, to) - left}px`);
        lineNode.classList.add("has-selection");
        selectedLines.push(lineNode);
      }

      lineStart = lineEnd + 1;
      lineNumber += 1;
    }
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

  let caretOffset = -1;
  let publishedCursor = null;
  let publishedSelection = null;

  // The native caret can drift against the syntax overlay because the two
  // layers round their fractional line boxes apart, so it is hidden and this
  // caret is placed from the same metrics the overlay and squiggles use.
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
    // in characters and newlines while a range is selected.
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

      if (changed || selectionChanged) {
        const patch = {};
        if (changed) {
          patch.cursor = position;
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

    const visible = focused
      && source.selectionStart === source.selectionEnd
      && !source.readOnly
      && !code.hidden;
    if (!visible) {
      caret.classList.remove("is-visible");
      caretOffset = -1;
      return;
    }

    // The overlay is scrolled in lockstep with the text layer, so its left edge
    // already carries the horizontal scroll: the caret only has to subtract the
    // vertical offset, which it owns.
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineIndex + 1}"]`);
    const {top} = scrollOffset();

    let x = 0;
    if (lineNode) {
      const lineLeft = lineNode.getBoundingClientRect().left;
      const codeLeft = code.getBoundingClientRect().left;
      const caretRight = lineLeft + advance(lineIndex + 1, linePrefix.length);
      x = Math.round(caretRight - codeLeft);
    }
    const y = Math.round(metrics.padTop + lineIndex * metrics.lineHeight - top);

    caret.style.transform = `translate(${x}px, ${y}px)`;
    caret.classList.add("is-visible");

    // Restart the blink only when the caret changes position, never on a scroll
    // frame. A forced reflow here would make the gutter lag the native scroll.
    if (source.selectionStart !== caretOffset) {
      caretOffset = source.selectionStart;
      caret.getAnimations().forEach((animation) => {
        animation.currentTime = 0;
      });
    }
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

    const codeLineHtml = (content, index) =>
      `<span class="line" data-line="${index + 1}">${content}</span>`;
    const gutterLineHtml = (_content, index) =>
      `<span class="gutter-line" data-line="${index + 1}">${index + 1}</span>`;

    highlightCode.innerHTML = lines.map(codeLineHtml).join("");
    gutterInner.innerHTML = lines.map(gutterLineHtml).join("");
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

    renderHighlight(text);
    updateSelection();
    syncScroll();
  }

  const pane = {
    root,
    main,
    tabStrip,
    source,
    highlightCode,
    markers,
    metrics,
    measureMetrics,
    renderHighlight,
    loadDoc,
    adoptText,
    syncScroll,
    updateSelection,
    updateCaret,
    updateActiveLine,
    advance,
  };

  source.addEventListener("input", () => onInput(pane));
  source.addEventListener("scroll", syncScroll);
  source.addEventListener("focus", () => {
    updateCaret();
    onFocus(pane);
  });
  source.addEventListener("blur", updateCaret);

  // Holding an arrow key repeats keydown but fires neither keyup nor input, and
  // the text layer only scrolls once the caret reaches an edge: so a caret
  // redrawn on those events alone would sit still through the whole repeat and
  // then jump on release. The caret moves on every repeat, and selectionchange
  // is the one event that reports it; the editor layer listens for it once and
  // routes it to the focused pane.
  source.addEventListener("keydown", (event) => {
    if (event.key === "Tab") {
      event.preventDefault();
      const start = source.selectionStart;
      const end = source.selectionEnd;
      source.value = `${source.value.slice(0, start)}  ${source.value.slice(end)}`;
      source.selectionStart = source.selectionEnd = start + 2;
      onInput(pane);
    }
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      void onRun();
    }
  });

  return pane;
}

// The editor island: it owns the two code surfaces with their syntax overlays,
// the problem counts, and the run command. The surface is two panes over one
// buffer: the right pane stays mounted and is folded away by CSS until the split
// is switched on, so the pane list is fixed at two. Each pane carries its own
// tab strip, so nothing sits above the panes at the panel level.
export function mountEditor(store, {canvas, showPanel}) {
  const panel = createPanel();
  panel.classList.add("editor");

  const body = document.createElement("div");
  body.className = "editor-body";

  const emptyState = createEmptyState({
    title: copy.editor.emptyTitle,
    hint: copy.editor.emptyHint,
  });

  // The pane the user last put the caret in. `activePane()` prefers whatever
  // holds focus right now and falls back to this, so a focus lost to a click on
  // the gutter or a panel still leaves a sensible pane behind.
  let focusedPane;
  const panes = [];

  const leftPane = createCodePane(store, {
    onInput,
    onFocus: (pane) => {
      focusedPane = pane;
    },
    onRun: () => run(),
  });
  const rightPane = createCodePane(store, {
    onInput,
    onFocus: (pane) => {
      focusedPane = pane;
    },
    onRun: () => run(),
  });
  panes.push(leftPane, rightPane);
  focusedPane = leftPane;

  leftPane.root.dataset.pane = "left";
  rightPane.root.dataset.pane = "right";

  const divider = document.createElement("div");
  divider.className = "pane-divider";
  divider.setAttribute("role", "separator");

  // The divider is a handle, not just a hairline: pressing it re-shares the body
  // between the two panes along whichever axis the split is on. The share is a
  // fraction of the body's own length, so it stays right through a resize, and
  // it lives in the store so a re-render does not drop it. While the split is
  // closed the divider has no width and is hidden, so it takes no pointer.
  divider.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const bounds = body.getBoundingClientRect();
    const down = store.getState().splitDirection === "down";
    const span = down ? bounds.height : bounds.width;
    const start = down ? bounds.top : bounds.left;
    if (!span) {
      return;
    }

    dragPointer(down ? "row-resize" : "col-resize", (moveEvent) => {
      const offset = (down ? moveEvent.clientY : moveEvent.clientX) - start;
      const share = Math.min(Math.max(offset / span, MIN_SHARE), MAX_SHARE);
      store.setState({splitRatio: share});
    });
  });

  body.append(leftPane.root, divider, rightPane.root, emptyState);
  panel.append(body);
  canvas.appendChild(panel);

  function activePane() {
    return panes.find((pane) => pane.source === document.activeElement) ?? focusedPane;
  }

  function patchDoc(id, patch) {
    const state = store.getState();
    store.setState(withDoc(state, id, patch));
  }

  function logLine(message, tone) {
    const state = store.getState();
    store.setState({log: [...state.log, {message, tone}]});
  }

  async function refreshFiles() {
    try {
      store.setState({files: await listSkills()});
    } catch (_error) {
      // The rail keeps the names it already knows while the server is down.
    }
  }

  async function run() {
    const state = store.getState();
    const doc = activeDoc(state);
    if (!doc) {
      return;
    }

    // The buffer is read from the store, not from a textarea: the store is the
    // single source of truth, `onInput` has already written every keystroke
    // into it, and with two panes there is no longer one obvious textarea to
    // read.
    const name = doc.name;
    const text = doc.source;
    const lines = text.split("\n").length;
    const bytes = new TextEncoder().encode(text).length;

    store.setState({busy: true, busyTask: "run"});
    showPanel?.("output");
    logLine(copy.log.deploying(name, {lines, bytes}), "busy");

    const startedAt = performance.now();
    try {
      const result = await deploySkill(name, text);
      const ms = Math.round(performance.now() - startedAt);

      if (!result.ok || !result.body) {
        const detail = (result.body && result.body.error) || result.raw || `HTTP ${result.status}`;
        patchDoc(doc.id, {
          diagnostics: [{severity: "error", message: detail, line: null, column: null}],
        });
        logLine(
          result.ok ? copy.log.malformed(detail) : copy.log.rejected(result.status, detail),
          "error",
        );
        showPanel?.("problems");
        return;
      }

      const diagnostics = result.body.diagnostics || [];
      const errors = diagnostics.filter((item) => item.severity === "error").length;
      const warnings = diagnostics.filter((item) => item.severity === "warning").length;

      patchDoc(doc.id, {
        name: result.body.fileName,
        baseline: doc.source,
        dirty: false,
        diagnostics,
      });

      if (errors === 0) {
        logLine(copy.log.compiled(result.body.fileName, warnings, ms), "ok");
      } else {
        logLine(copy.log.failed(result.body.fileName, errors, warnings, ms), "error");
        for (const item of diagnostics.slice(0, MAX_LOG_PROBLEMS)) {
          logLine(copy.log.problem(item), "detail");
        }
        if (diagnostics.length > MAX_LOG_PROBLEMS) {
          logLine(copy.log.moreProblems(diagnostics.length - MAX_LOG_PROBLEMS), "detail");
        }
        logLine(copy.log.fixHint, null);
        showPanel?.("problems");
      }

      void refreshFiles();
    } catch (error) {
      patchDoc(doc.id, {
        diagnostics: [{severity: "error", message: error.message, line: null, column: null}],
      });
      logLine(copy.log.unreachable(), "error");
      showPanel?.("problems");
    } finally {
      store.setState({busy: false, busyTask: null});
    }
  }

  // Compile-only sibling of run(). It asks the server to compile the buffer and
  // nothing else: nothing is written to the skills directory and the registry is
  // not touched, so the buffer stays unsaved and the loaded skill unchanged. The
  // diagnostics that come back are the build's whole result, so the Problems
  // panel is raised to show them.
  async function build() {
    const state = store.getState();
    const doc = activeDoc(state);
    if (!doc) {
      return;
    }

    const name = doc.name;
    const text = doc.source;
    const lines = text.split("\n").length;
    const bytes = new TextEncoder().encode(text).length;

    store.setState({busy: true, busyTask: "build"});
    showPanel?.("problems");
    logLine(copy.log.building(name, {lines, bytes}), "busy");

    const startedAt = performance.now();
    try {
      const result = await buildSkill(name, text);
      const ms = Math.round(performance.now() - startedAt);

      if (!result.ok || !result.body) {
        const detail = (result.body && result.body.error) || result.raw || `HTTP ${result.status}`;
        patchDoc(doc.id, {
          diagnostics: [{severity: "error", message: detail, line: null, column: null}],
        });
        logLine(
          result.ok ? copy.log.malformed(detail) : copy.log.rejected(result.status, detail),
          "error",
        );
        return;
      }

      const diagnostics = result.body.diagnostics || [];
      const errors = diagnostics.filter((item) => item.severity === "error").length;
      const warnings = diagnostics.filter((item) => item.severity === "warning").length;

      // Nothing was deployed, so the baseline and the dirty flag stay as they
      // are: the buffer is still unsaved and the loaded skill is unchanged.
      patchDoc(doc.id, {diagnostics});

      if (errors === 0) {
        logLine(copy.log.built(result.body.fileName, warnings, ms), "ok");
      } else {
        logLine(copy.log.buildFailed(result.body.fileName, errors, warnings, ms), "error");
        for (const item of diagnostics.slice(0, MAX_LOG_PROBLEMS)) {
          logLine(copy.log.problem(item), "detail");
        }
        if (diagnostics.length > MAX_LOG_PROBLEMS) {
          logLine(copy.log.moreProblems(diagnostics.length - MAX_LOG_PROBLEMS), "detail");
        }
      }
    } catch (error) {
      patchDoc(doc.id, {
        diagnostics: [{severity: "error", message: error.message, line: null, column: null}],
      });
      logLine(copy.log.unreachable(), "error");
    } finally {
      store.setState({busy: false, busyTask: null});
    }
  }

  // The pane the user typed in redraws its own overlay here — a keystroke has
  // already mutated its textarea natively, so only the derived layers need to
  // catch up. The other pane is reconciled by `render`, which sees the store
  // change to write just produced.
  function onInput(pane) {
    const state = store.getState();
    const doc = activeDoc(state);

    if (doc) {
      const value = pane.source.value;
      const dirty = value !== doc.baseline;
      const patch = {source: value};

      if (dirty !== doc.dirty) {
        patch.dirty = dirty;
      }
      // Diagnostics are the last run's verdict on the text as it was then, so an
      // edit leaves them describing lines that moved or that the edit already
      // fixed. Drop them here; the next run repopulates them.
      if (doc.diagnostics.length > 0) {
        patch.diagnostics = [];
      }

      const next = {...withDoc(state, doc.id, patch), sourceRevision: state.sourceRevision + 1};
      store.setState(next);
    } else {
      store.setState({sourceRevision: state.sourceRevision + 1});
    }

    pane.renderHighlight(pane.source.value);
    pane.updateSelection();
    pane.syncScroll();
  }

  // Folding the split away must not strand the keyboard in a hidden pane, so
  // focus is handed back before the second pane disappears.
  function unsplit() {
    if (!store.getState().split) {
      return;
    }
    if (focusedPane === rightPane) {
      leftPane.source.focus();
    }
    store.setState({split: false});
  }

  // Both directions share one on/off flag and one orientation, so pressing the
  // other direction while a split is open swings the second pane over instead of
  // opening a third one.
  function toggleSplit(direction = "right") {
    const state = store.getState();
    if (Boolean(state.split) && state.splitDirection === direction) {
      unsplit();
      return;
    }
    store.setState({split: true, splitDirection: direction});
  }

  let renderedId;
  let renderedFocus = 0;

  const render = (state) => {
    const doc = activeDoc(state);
    const id = doc ? doc.id : null;
    const text = doc ? doc.source : "";
    const idChanged = id !== renderedId;
    if (idChanged) {
      renderedId = id;
    }

    for (const pane of panes) {
      if (idChanged) {
        pane.loadDoc(doc, {focus: pane === activePane()});
        continue;
      }
      // The focused pane already holds the buffer it just typed; only a pane
      // that is behind adopts the new text, so the writer's caret, selection and
      // IME composition are never disturbed.
      if (pane.source.value !== text && pane.source !== document.activeElement) {
        pane.adoptText(text);
      }
    }

    const splitOn = Boolean(state.split) && Boolean(doc);
    body.classList.toggle("is-split", splitOn);
    body.classList.toggle("is-split-down", state.splitDirection === "down");
    body.classList.toggle("is-empty", !doc);
    emptyState.hidden = Boolean(doc);

    const share = state.splitRatio ?? 0.5;
    body.style.setProperty("--split-ratio", String(share));

    const orientation = state.splitDirection === "down" ? "horizontal" : "vertical";
    divider.setAttribute("aria-orientation", orientation);

    if (state.focusToken !== renderedFocus) {
      renderedFocus = state.focusToken;
      if (doc) {
        activePane().source.focus();
      }
    }
  };

  store.subscribe(render);

  document.addEventListener("selectionchange", () => {
    const pane = panes.find((item) => item.source === document.activeElement);
    if (!pane) {
      return;
    }
    pane.updateActiveLine();
    pane.updateCaret();
    pane.updateSelection();
  });

  window.addEventListener("resize", () => {
    for (const pane of panes) {
      pane.measureMetrics();
      pane.syncScroll();
    }
  });

  for (const pane of panes) {
    pane.measureMetrics();
  }
  render(store.getState());

  return {
    body,
    panes,
    activePane,
    source: leftPane.source,
    highlightCode: leftPane.highlightCode,
    run,
    build,
    unsplit,
    toggleSplit,
  };
}
