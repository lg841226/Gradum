import {clear} from "../core/dom.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";

// Renders the Problems panel and asks each pane to draw its squiggle markers.
// It never reaches into the pane's gutter, highlight or metrics: the pane owns
// those nodes and exposes the drawing and reveal calls it needs.
export function mountDiagnostics(store, {body, editor, getActiveDoc}) {
  const list = document.createElement("div");
  list.className = "problem-list";
  body.appendChild(list);

  let renderedDocId;
  let renderedDiagnostics;
  let renderedStatus;
  let renderedRevision = -1;

  function clearMarks() {
    for (const pane of editor.panes) {
      pane.setDiagnostics([], "");
    }
  }

  function renderMarks(diagnostics, text) {
    for (const pane of editor.panes) {
      pane.setDiagnostics(diagnostics, text);
    }
  }

  function createProblemRow(diagnostic) {
    const isError = diagnostic.severity === "error";

    const row = document.createElement("div");
    row.className = "problem";
    row.dataset.tone = isError ? "error" : "warning";
    row.dataset.line = diagnostic.line ?? "";
    row.dataset.column = diagnostic.column ?? "";

    const severity = document.createElement("span");
    severity.className = "problem-severity";
    severity.append(createIcon({name: isError ? "error" : "warning"}));

    const location = document.createElement("span");
    location.className = "problem-location";
    if (diagnostic.line) {
      const column = diagnostic.column ? `:${diagnostic.column}` : "";
      location.textContent = `Ln ${diagnostic.line}${column}`;
    } else {
      location.textContent = "-";
    }

    const message = document.createElement("span");
    message.className = "problem-message";
    message.textContent = diagnostic.message;

    row.append(severity, location, message);
    row.addEventListener("click", () => {
      if (row.dataset.line) {
        jumpToLine(Number(row.dataset.line), Number(row.dataset.column) || undefined);
      }
    });
    return row;
  }

  function renderList(diagnostics, doc, status) {
    clear(list);

    if (!doc) {
      list.appendChild(createEmptyState({
        title: copy.status.noSkill,
        hint: copy.problems.noSkillHint,
      }));
      return;
    }

    if (diagnostics.length === 0) {
      const current = status && status.tone === "error";
      if (current) {
        list.appendChild(createEmptyState({title: status.text, tone: "error"}));
      } else if (doc.dirty) {
        list.appendChild(createEmptyState({
          title: copy.problems.stale,
          hint: copy.problems.staleHint,
        }));
      } else {
        list.appendChild(createEmptyState({
          title: copy.problems.empty(doc.name),
          hint: copy.problems.emptyHint,
        }));
      }
      return;
    }

    for (const diagnostic of diagnostics) {
      list.appendChild(createProblemRow(diagnostic));
    }
  }

  function jumpToLine(lineNumber, column) {
    editor.activePane().revealLine(lineNumber, column);
  }

  const render = (state) => {
    const doc = getActiveDoc(state);
    const id = doc ? doc.id : null;
    const diagnostics = doc ? doc.diagnostics : [];
    const status = state.status;
    const docChanged = id !== renderedDocId || diagnostics !== renderedDiagnostics;
    const statusChanged = status !== renderedStatus;

    if (docChanged) {
      renderedDocId = id;
      renderedDiagnostics = diagnostics;
      renderMarks(diagnostics, doc ? doc.source : "");
      renderedRevision = state.sourceRevision;
    } else if (state.sourceRevision !== renderedRevision) {
      renderedRevision = state.sourceRevision;
      clearMarks();
    }

    if (docChanged || statusChanged) {
      renderedStatus = status;
      renderList(diagnostics, doc, status);
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {list};
}
