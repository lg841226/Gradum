import {codeMetrics, escapeHtml, FALLBACK_METRICS} from "../core/dom.js";
import {activeDoc, withDoc} from "../core/store.js";
import {deploySkill, listSkills} from "../data/skillsApi.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createIcon} from "../ui/components/icon.js";
import {createPanel} from "../ui/components/panel.js";
import {createTabStrip} from "../ui/components/tab.js";
import {copy} from "../ui/copy.js";

const KOTLIN_KEYWORDS = [
  "package", "import", "class", "object", "interface", "fun", "val", "var",
  "override", "private", "public", "protected", "internal", "return", "if", "else", "when",
  "for", "while", "do", "try", "catch", "finally", "throw", "is", "in", "as", "null", "true",
  "false", "this", "super", "open", "data", "sealed", "enum", "companion", "abstract", "const",
  "lateinit", "by", "where", "typealias", "init", "constructor", "break", "continue", "out",
  "reified", "inline", "suspend", "crossinline", "noinline", "vararg", "operator", "infix",
  "external", "annotation", "actual", "expect", "tailrec",
];

const TOKEN_CLASS = {1: "com", 2: "str", 3: "ann", 4: "num", 5: "kw", 6: "type", 7: "fn"};

// Compiler output caps the diagnostics it prints inline; anything past this
// count lives in the Problems panel, which lists them all.
const MAX_LOG_PROBLEMS = 6;

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
    if (match[group] !== undefined) return TOKEN_CLASS[group];
  }
  return null;
}

function tokenize(text) {
  const tokens = [];
  let cursor = 0;
  let match;
  TOKEN_PATTERN.lastIndex = 0;
  while ((match = TOKEN_PATTERN.exec(text)) !== null) {
    if (match.index > cursor) tokens.push({text: text.slice(cursor, match.index), cls: null});
    tokens.push({text: match[0], cls: classOf(match)});
    cursor = match.index + match[0].length;
  }
  if (cursor < text.length) tokens.push({text: text.slice(cursor), cls: null});
  return tokens;
}

// A status-bar entry: a hollow severity icon beside its count, tinted by tone.
function createCount({tone, icon}) {
  const item = document.createElement("span");
  item.className = "status-count";
  item.dataset.tone = tone;
  const value = document.createElement("span");
  value.className = "status-count-value";
  item.append(createIcon({name: icon}), value);
  return {item, value};
}

// Counts only change on a compile, so the DOM is left alone unless the number
// actually differs.
function updateCount(count, value, label) {
  const text = String(value);
  if (count.value.textContent === text) return;
  count.value.textContent = text;
  count.item.setAttribute("aria-label", label);
}

// The editor island: it owns the tab strip container, the code surface with its
// syntax overlay, the problem counts, and the run command.
export function mountEditor(store, {canvas, showPanel}) {
  const panel = createPanel();
  panel.classList.add("editor");

  const header = document.createElement("header");
  header.className = "panel-header";

  const tabStrip = createTabStrip();

  header.append(tabStrip);

  const body = document.createElement("div");
  body.className = "editor-body";

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

  const emptyState = createEmptyState({
    title: copy.editor.emptyTitle,
    hint: copy.editor.emptyHint,
  });

  // The bar closes the island, counting the active skill's errors and warnings.
  const statusBar = document.createElement("footer");
  statusBar.className = "editor-status";
  const errorCount = createCount({tone: "error", icon: "error-outline"});
  const warningCount = createCount({tone: "warning", icon: "warning-outline"});
  statusBar.append(errorCount.item, warningCount.item);

  body.append(gutter, code, emptyState);
  panel.append(header, body, statusBar);
  canvas.appendChild(panel);

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
      if (remaining <= node.data.length) return {node, offset: remaining};
      remaining -= node.data.length;
    }
    return null;
  }

  // Width, in pixels, of the first `charCount` characters of the rendered line,
  // measured from the line's own left edge. Tabs, wide glyphs and fallback runs
  // are all handled by the layout engine, so the number matches the glyphs.
  function advance(lineNumber, charCount) {
    if (charCount <= 0) return 0;
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
    if (!lineNode) return 0;
    const position = textPositionAt(lineNode, charCount);
    if (!position) return 0;
    const range = document.createRange();
    range.setStart(lineNode, 0);
    range.setEnd(position.node, position.offset);
    return Math.max(0, range.getBoundingClientRect().right - lineNode.getBoundingClientRect().left);
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

  // East Asian wide and fullwidth ranges. These are the glyphs a monospace grid
  // draws two cells wide, which is how a column is counted for the status bar.
  const WIDE_GLYPH = /[\u1100-\u115F\u2E80-\u303E\u3041-\u33FF\u3400-\u4DBF\u4E00-\u9FFF\uA000-\uA4CF\uAC00-\uD7A3\uF900-\uFAFF\uFE30-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]/;

  // Column the caret sits in, counted the way a monospace grid counts rather
  // than by character: a wide glyph holds two columns, and a tab runs to the
  // next stop four columns along. This is what the status bar reports, so it is
  // deliberately not the same number as the pixel offset `advance` returns.
  function displayColumns(text) {
    let column = 0;
    for (const char of text) {
      column += char === "\t" ? 4 - (column % 4) : WIDE_GLYPH.test(char) ? 2 : 1;
    }
    return column;
  }

  let caretOffset = -1;
  let publishedCursor = null;

  // The native caret can drift against the syntax overlay because the two
  // layers round their fractional line boxes apart, so it is hidden and this
  // caret is placed from the same metrics the overlay and squiggles use.
  function updateCaret() {
    const before = source.value.slice(0, source.selectionStart);
    const lineIndex = before.split("\n").length - 1;
    const linePrefix = before.slice(before.lastIndexOf("\n") + 1);
    const column = displayColumns(linePrefix);

    // The status bar reads the caret position from the store, so every caret
    // move publishes it — only when it changed, or each blink would render.
    const position = {line: lineIndex + 1, column: column + 1};
    if (!publishedCursor
      || publishedCursor.line !== position.line
      || publishedCursor.column !== position.column) {
      publishedCursor = position;
      store.setState({cursor: position});
    }

    const visible = document.activeElement === source
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
    const x = lineNode
      ? Math.round(lineNode.getBoundingClientRect().left
        + advance(lineIndex + 1, linePrefix.length)
        - code.getBoundingClientRect().left)
      : 0;
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
    if (previousCode) previousCode.classList.remove("active");
    const previousGutter = gutterInner.querySelector(".gutter-line.active");
    if (previousGutter) previousGutter.classList.remove("active");
    const codeLine = highlightCode.querySelector(`.line[data-line="${line}"]`);
    if (codeLine) codeLine.classList.add("active");
    const gutterLine = gutterInner.querySelector(`.gutter-line[data-line="${line}"]`);
    if (gutterLine) gutterLine.classList.add("active");
  }

  // Anything outside ASCII is boxed in the overlay, so a fullwidth （ is not read
  // as an ASCII ( at a glance: the two are near-identical at code size and the
  // glyph shape alone does not separate them. Escaping runs first, so the tags
  // added here are never themselves boxed and an entity's ASCII characters are
  // left alone — only the glyphs the file actually holds are wrapped.
  function markGlyphs(text) {
    return escapeHtml(text).replace(/[^\x00-\x7F]+/g, (run) => `<span class="glyph-box">${run}</span>`);
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
        if (part) {
          line += token.cls
            ? `<span class="${token.cls}">${markGlyphs(part)}</span>`
            : markGlyphs(part);
        }
      });
    }
    lines.push(line);

    highlightCode.innerHTML = lines
      .map((content, index) => `<span class="line" data-line="${index + 1}">${content}</span>`)
      .join("");
    gutterInner.innerHTML = lines
      .map((_content, index) => `<span class="gutter-line" data-line="${index + 1}">${index + 1}</span>`)
      .join("");
    updateActiveLine();
  }

  function loadDoc(doc) {
    source.value = doc ? doc.source : "";
    source.readOnly = !doc;
    gutter.hidden = !doc;
    code.hidden = !doc;
    emptyState.hidden = Boolean(doc);
    renderHighlight(source.value);
    syncScroll();
    if (doc) source.focus();
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
    if (!doc) return;
    const name = doc.name;
    const text = source.value;
    const lines = text.split("\n").length;
    const bytes = new TextEncoder().encode(text).length;

    store.setState({busy: true});
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
        baseline: source.value,
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
      store.setState({busy: false});
    }
  }

  function onInput() {
    const state = store.getState();
    const doc = activeDoc(state);
    if (doc) {
      const value = source.value;
      const dirty = value !== doc.baseline;
      const patch = {source: value};
      if (dirty !== doc.dirty) patch.dirty = dirty;
      // Diagnostics are the last run's verdict on the text as it was then, so an
      // edit leaves them describing lines that moved or that the edit already
      // fixed. Drop them here; the next run repopulates them.
      if (doc.diagnostics.length > 0) patch.diagnostics = [];
      store.setState({...withDoc(state, doc.id, patch), sourceRevision: state.sourceRevision + 1});
    } else {
      store.setState({sourceRevision: state.sourceRevision + 1});
    }
    renderHighlight(source.value);
    syncScroll();
  }

  source.addEventListener("input", onInput);
  source.addEventListener("scroll", syncScroll);
  // Holding an arrow key repeats keydown but fires neither keyup nor input, and
  // the text layer only scrolls once the caret reaches an edge — so a caret
  // redrawn on those events alone would sit still through the whole repeat and
  // then jump on release. The caret moves on every repeat, and selectionchange
  // is the one event that reports it, so it replaces the click/keyup/select
  // trio rather than standing beside them.
  document.addEventListener("selectionchange", () => {
    if (document.activeElement !== source) return;
    updateActiveLine();
    updateCaret();
  });
  source.addEventListener("focus", updateCaret);
  source.addEventListener("blur", updateCaret);
  source.addEventListener("keydown", (event) => {
    if (event.key === "Tab") {
      event.preventDefault();
      const start = source.selectionStart;
      const end = source.selectionEnd;
      source.value = `${source.value.slice(0, start)}  ${source.value.slice(end)}`;
      source.selectionStart = source.selectionEnd = start + 2;
      onInput();
    }
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      void run();
    }
  });

  window.addEventListener("resize", () => {
    measureMetrics();
    syncScroll();
  });

  let renderedId;
  let renderedFocus = 0;

  const render = (state) => {
    const doc = activeDoc(state);
    const id = doc ? doc.id : null;
    if (id !== renderedId) {
      renderedId = id;
      loadDoc(doc);
    }
    const diagnostics = (doc && doc.diagnostics) || [];
    const errors = diagnostics.filter((item) => item.severity === "error").length;
    const warnings = diagnostics.filter((item) => item.severity === "warning").length;
    updateCount(errorCount, errors, copy.statusBar.errors(errors));
    updateCount(warningCount, warnings, copy.statusBar.warnings(warnings));
    if (state.focusToken !== renderedFocus) {
      renderedFocus = state.focusToken;
      if (doc) source.focus();
    }
  };

  store.subscribe(render);
  render(store.getState());
  measureMetrics();
  syncScroll();

  return {
    body,
    tabStrip,
    source,
    highlightCode,
    markers,
    metrics,
    advance,
    run,
    syncScroll,
    updateActiveLine,
  };
}
