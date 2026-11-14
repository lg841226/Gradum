import {withDoc} from "../core/store.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createPanel} from "../ui/components/panel.js";
import {copy} from "../ui/copy.js";
import {onDragSettle} from "./splitter.js";
import {createSplitLayout} from "./splitLayout.js";
import {createCodePane} from "./pane.js";

// Compiler output caps the diagnostics it prints inline; anything past this
// count lives in the Problems panel, which lists them all.
const MAX_LOG_PROBLEMS = 6;

// The editor island: it owns the two code surfaces with their syntax overlays,
// the problem counts and the run command. The surface is two panes over one
// buffer: the right pane stays mounted and is folded away by CSS until the split
// is switched on, so the pane list is fixed at two. Each pane carries its own
// tab strip, so nothing sits above the panes at the panel level.
//
// File I/O and the identity of the active document come from the host: this
// island never learns where a buffer came from or how a build reaches a server.
export function mountEditor(store, {canvas, showPanel, documents, getActiveDoc, runCommand}) {
  const panel = createPanel();
  panel.classList.add("editor");

  const body = document.createElement("div");
  body.className = "editor-body";

  const emptyState = createEmptyState({
    title: copy.editor.emptyTitle,
    hint: copy.editor.emptyHint,
  });

  let focusedPane;
  const panes = [];

  const leftPane = createCodePane(store, {
    onInput,
    onFocus: (pane) => {
      focusedPane = pane;
    },
    getActiveDoc,
  });
  const rightPane = createCodePane(store, {
    onInput,
    onFocus: (pane) => {
      focusedPane = pane;
    },
    getActiveDoc,
  });
  panes.push(leftPane, rightPane);
  focusedPane = leftPane;

  onDragSettle(() => {
    for (const pane of panes) {
      pane.syncScroll();
      pane.updateSelection();
    }
  });

  leftPane.root.dataset.pane = "left";
  rightPane.root.dataset.pane = "right";

  const split = createSplitLayout(store, {
    container: body,
    panes: [leftPane.root, rightPane.root],
    keys: {direction: "splitDirection", ratio: "splitRatio"},
    isShown: (state) => Boolean(state.split) && Boolean(getActiveDoc(state)),
  });
  const {divider} = split;

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

  async function run() {
    const state = store.getState();
    const doc = getActiveDoc(state);
    if (!doc) {
      return;
    }

    if (state.reader) {
      return;
    }

    const name = doc.name;
    const isSkill = name.startsWith("skills/");
    const shortName = isSkill ? name.slice("skills/".length) : name;
    const text = doc.source;
    const lines = text.split("\n").length;
    const bytes = new TextEncoder().encode(text).length;

    store.setState({busy: true, busyTask: "run"});
    showPanel?.("output");
    logLine(
      isSkill ? copy.log.deploying(shortName, {lines, bytes}) : copy.log.saving(name, {lines, bytes}),
      "busy",
    );

    const startedAt = performance.now();
    try {
      const result = await documents.save(name, text);
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

      if (!isSkill) {
        patchDoc(doc.id, {
          baseline: doc.source,
          dirty: false,
          diagnostics: [],
        });
        logLine(copy.log.saved(name, ms), "ok");
        runCommand("refresh-files");
        return;
      }

      const diagnostics = result.body.diagnostics || [];
      const errors = diagnostics.filter((item) => item.severity === "error").length;
      const warnings = diagnostics.filter((item) => item.severity === "warning").length;

      patchDoc(doc.id, {
        baseline: doc.source,
        dirty: false,
        diagnostics,
      });

      if (errors === 0) {
        logLine(copy.log.compiled(shortName, warnings, ms), "ok");
      } else {
        logLine(copy.log.failed(shortName, errors, warnings, ms), "error");
        for (const item of diagnostics.slice(0, MAX_LOG_PROBLEMS)) {
          logLine(copy.log.problem(item), "detail");
        }
        if (diagnostics.length > MAX_LOG_PROBLEMS) {
          logLine(copy.log.moreProblems(diagnostics.length - MAX_LOG_PROBLEMS), "detail");
        }
        logLine(copy.log.fixHint, null);
        showPanel?.("problems");
      }

      runCommand("refresh-files");
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

  async function build() {
    const state = store.getState();
    const doc = getActiveDoc(state);
    if (!doc) {
      return;
    }
    if (state.reader) {
      return;
    }

    const name = doc.name;
    if (!name.startsWith("skills/")) {
      logLine(copy.log.buildUnsupported(name), "detail");
      return;
    }

    const text = doc.source;
    const lines = text.split("\n").length;
    const bytes = new TextEncoder().encode(text).length;

    store.setState({busy: true, busyTask: "build"});
    showPanel?.("problems");
    logLine(copy.log.building(name, {lines, bytes}), "busy");

    const startedAt = performance.now();
    try {
      const result = await documents.build(name, text);
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

  function onInput(pane) {
    const state = store.getState();
    const doc = getActiveDoc(state);

    if (doc) {
      const value = pane.source.value;
      const dirty = value !== doc.baseline;
      const patch = {source: value};

      if (dirty !== doc.dirty) {
        patch.dirty = dirty;
      }

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
    const doc = getActiveDoc(state);
    const id = doc ? doc.id : null;
    const text = doc ? doc.source : "";
    const baseline = doc ? doc.baseline : null;
    const idChanged = id !== renderedId;
    if (idChanged) {
      renderedId = id;
    }

    const baselineChanged = !idChanged && baseline !== renderedBaseline;
    renderedBaseline = baseline;

    for (const pane of panes) {
      if (idChanged) {
        pane.loadDoc(doc, {focus: pane === activePane()});
        continue;
      }
      if (pane.source.value !== text && pane.source !== document.activeElement) {
        pane.adoptText(text);
      } else if (baselineChanged && pane.source.value === text) {
        pane.renderHighlight(text);
      }
    }

    const locked = Boolean(state.reader);
    for (const pane of panes) {
      pane.source.readOnly = locked || !doc;
    }

    split.apply(state);
    body.classList.toggle("is-empty", !doc);
    emptyState.hidden = Boolean(doc);

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

    pane.syncCursors();
    pane.updateActiveLine();
    pane.updateCaret();
    pane.updateSelection();
  });

  window.addEventListener("resize", () => {
    for (const pane of panes) {
      pane.measureMetrics();
      pane.syncScroll();
      pane.updateSelection();
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
