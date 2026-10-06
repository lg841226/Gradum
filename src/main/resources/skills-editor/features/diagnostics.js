import {clear} from "../core/dom.js";
import {activeDoc} from "../core/store.js";
import {createEmptyState} from "../ui/components/feedback.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";

// Renders the Problems panel and the squiggle markers over the editor. It reads
// the code surface through the API the editor feature exposes in main.js.
export function mountDiagnostics(store, {body, editor}) {
  const list = document.createElement("div");
  list.className = "problem-list";
  body.appendChild(list);

  let renderedDocId;
  let renderedDiagnostics;
  let renderedStatus;
  let renderedRevision = -1;

  function clearMarks() {
    editor.markers.replaceChildren();
    editor.highlightCode
      .querySelectorAll(".errline, .warnline")
      .forEach((node) => node.classList.remove("errline", "warnline"));
  }

  function renderMarks(diagnostics) {
    clearMarks();

    const {padTop, padLeft, lineHeight} = editor.metrics;
    const lines = editor.source.value.split("\n");

    for (const diagnostic of diagnostics) {
      if (!diagnostic.line) {
        continue;
      }

      const lineNode = editor.highlightCode.querySelector(`.line[data-line="${diagnostic.line}"]`);
      if (lineNode) {
        const severityClass = diagnostic.severity === "error" ? "errline" : "warnline";
        lineNode.classList.add(severityClass);
      }

      const text = lines[diagnostic.line - 1] || "";
      const column = Math.max(1, diagnostic.column || 1);
      const start = editor.advance(diagnostic.line, Math.min(column - 1, text.length));
      const end = editor.advance(diagnostic.line, text.length);

      const isError = diagnostic.severity === "error";
      const marker = document.createElement("div");
      marker.className = `squiggle ${isError ? "error" : "warning"}`;
      marker.style.top = `${padTop + (diagnostic.line - 1) * lineHeight}px`;
      marker.style.left = `${padLeft + start}px`;
      marker.style.width = `${Math.max(1, end - start)}px`;
      marker.style.height = `${lineHeight}px`;
      editor.markers.appendChild(marker);
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

  // The list has no row for a rejected action, so a failed action's message takes
  // the placeholder instead of vanishing. Build results are not failed actions:
  // Console owns those, and this stays the quiet "all clear" note it was.
  function renderList(diagnostics, doc, status) {
    clear(list);

    if (!doc) {
      list.appendChild(createEmptyState({title: copy.status.noSkill}));
      return;
    }

    if (diagnostics.length === 0) {
      // An edit throws the last run's verdict away, so the all-clear note would
      // be a claim nobody has checked. A rejected rename is a current, real
      // error, so it still outranks the out-of-date note.
      const current = status && status.tone === "error";
      if (current) {
        list.appendChild(createEmptyState({title: status.text, tone: "error"}));
      } else {
        const title = doc.dirty ? copy.problems.stale : copy.problems.empty(doc.name);
        list.appendChild(createEmptyState({title}));
      }
      return;
    }

    for (const diagnostic of diagnostics) {
      list.appendChild(createProblemRow(diagnostic));
    }
  }

  function jumpToLine(lineNumber, column) {
    if (!lineNumber) {
      return;
    }

    const source = editor.source;
    let offset = 0;

    for (let line = 1; line < lineNumber; line++) {
      const next = source.value.indexOf("\n", offset);
      if (next === -1) {
        offset = source.value.length;
        break;
      }
      offset = next + 1;
    }

    let end = source.value.indexOf("\n", offset);
    if (end === -1) {
      end = source.value.length;
    }

    const caret = Math.min(offset + Math.max(0, (column || 1) - 1), end);
    source.focus();
    source.setSelectionRange(caret, end);
    source.scrollTop = Math.max(0, (lineNumber - 3) * editor.metrics.lineHeight);
    editor.syncScroll();
    editor.updateActiveLine();
  }

  const render = (state) => {
    const doc = activeDoc(state);
    const id = doc ? doc.id : null;
    const diagnostics = doc ? doc.diagnostics : [];
    const status = state.status;
    const docChanged = id !== renderedDocId || diagnostics !== renderedDiagnostics;
    const statusChanged = status !== renderedStatus;

    if (docChanged) {
      renderedDocId = id;
      renderedDiagnostics = diagnostics;
      renderMarks(diagnostics);
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
