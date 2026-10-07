import {activeDoc, withDoc} from "../core/store.js";
import {buildSkill, deploySkill, listSkills} from "../data/skillsApi.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createPanel} from "../ui/components/panel.js";
import {copy} from "../ui/copy.js";
import {dragPointer} from "./splitter.js";
import {createCodePane} from "./pane.js";

// Compiler output caps the diagnostics it prints inline; anything past this
// count lives in the Problems panel, which lists them all.
const MAX_LOG_PROBLEMS = 6;

// The share of the body the first pane keeps when the divider is dragged. Held
// between a fifth and four fifths, so neither pane can be dragged shut — closing
// one outright is what the split toggle is for.
const MIN_SHARE = 0.2;
const MAX_SHARE = 0.8;

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
  let renderedBaseline;

  const render = (state) => {
    const doc = activeDoc(state);
    const id = doc ? doc.id : null;
    const text = doc ? doc.source : "";
    const baseline = doc ? doc.baseline : null;
    const idChanged = id !== renderedId;
    if (idChanged) {
      renderedId = id;
    }

    // A deployment swaps the baseline without moving a byte of the buffer, so the
    // modified-line bars have to be redrawn by hand — nothing below would
    // otherwise notice.
    const baselineChanged = !idChanged && baseline !== renderedBaseline;
    renderedBaseline = baseline;

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
      } else if (baselineChanged && pane.source.value === text) {
        pane.renderHighlight(text);
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
    // The platform only speaks for the primary range: a real user action
    // moves it somewhere the list does not match and collapses the extras,
    // while the echo of the editor's own write compares equal and leaves
    // every caret where it planted it.
    pane.syncCursors();
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
