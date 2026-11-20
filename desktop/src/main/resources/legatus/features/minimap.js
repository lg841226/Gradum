import {isDragging, onDragSettle} from "./splitter.js";

const MAX_OCCURRENCES = 500;

export function mountMinimap(store, {container, source, highlightCode, getActiveDoc, pane}) {
  const strip = document.createElement("div");
  strip.className = "minimap";

  const content = document.createElement("div");
  content.className = "highlight minimap-content";

  const viewport = document.createElement("div");
  viewport.className = "minimap-viewport";

  strip.append(content, viewport);
  container.appendChild(strip);

  let scale = 1;
  let grabOffset = 0;
  let windowOffset = 0;
  let dragging = false;

  function clone() {
    content.innerHTML = highlightCode.innerHTML;
    content.querySelectorAll(".band, .match").forEach((node) => node.remove());
    content.querySelectorAll(".active").forEach((lineNode) => {
      lineNode.classList.remove("active");
    });
    drawOccurrences();
    update();
  }

  let occurrenceBoxes = [];

  function drawOccurrences() {
    for (const box of occurrenceBoxes) {
      box.remove();
    }
    occurrenceBoxes = [];

    if (strip.hidden) {
      return;
    }
    const text = source.value;
    const start = source.selectionStart;
    const end = source.selectionEnd;
    if (end <= start) {
      return;
    }
    const needle = text.slice(start, end);
    if (!needle.trim() || needle.includes("\n")) {
      return;
    }

    let lineNumber = 1;
    let lineStart = 0;
    let at = text.indexOf(needle);
    let count = 0;

    while (at !== -1 && count < MAX_OCCURRENCES) {
      let next = text.indexOf("\n", lineStart);
      while (next !== -1 && next < at) {
        lineNumber += 1;
        lineStart = next + 1;
        next = text.indexOf("\n", lineStart);
      }

      const lineNode = content.querySelector(`.line[data-line="${lineNumber}"]`);
      if (lineNode) {
        const left = pane.advance(lineNumber, at - lineStart);
        const right = pane.advance(lineNumber, at - lineStart + needle.length);
        if (right > left) {
          const box = document.createElement("span");
          box.className = "minimap-match";
          box.style.left = `${left}px`;
          box.style.width = `${right - left}px`;
          lineNode.appendChild(box);
          occurrenceBoxes.push(box);
        }
      }

      at = text.indexOf(needle, at + needle.length);
      count += 1;
    }
  }

  function caretToDocumentY(documentY) {
    const {padTop, lineHeight} = pane.metrics;
    if (!lineHeight) {
      return;
    }
    const text = source.value;
    const total = text.split("\n").length;
    const lineIndex = Math.min(Math.max(Math.floor((documentY - padTop) / lineHeight), 0), total - 1);
    let start = 0;
    for (let line = 0; line < lineIndex; line++) {
      start = text.indexOf("\n", start) + 1;
    }

    source.focus({preventScroll: true});
    source.setSelectionRange(start, start);
    pane.syncCursors();
    pane.updateActiveLine();
    pane.updateCaret();
    pane.updateSelection();
  }

  function update() {
    const codeWidth = source.clientWidth;
    const stripWidth = strip.clientWidth;
    const stripHeight = strip.clientHeight;
    if (strip.hidden || !codeWidth || !stripWidth || !stripHeight) {
      return;
    }
    scale = stripWidth / codeWidth;

    const scaledContent = source.scrollHeight * scale;
    const viewTop = source.scrollTop * scale;
    const viewHeight = source.clientHeight * scale;
    const overflow = scaledContent - stripHeight;
    const maxScroll = source.scrollHeight - source.clientHeight;

    if (overflow > 0 && maxScroll > 0) {
      windowOffset = (source.scrollTop / maxScroll) * overflow;
    } else {
      windowOffset = 0;
    }

    content.style.transform = `translateY(${-windowOffset}px) scale(${scale})`;

    const rectTop = Math.min(Math.max(0, viewTop - windowOffset), stripHeight - 2);
    viewport.style.transform = `translateY(${rectTop}px)`;
    viewport.style.height = `${Math.max(2, Math.min(viewHeight, stripHeight - rectTop))}px`;
  }

  function scrollToPointer(clientY) {
    const stripTop = strip.getBoundingClientRect().top;
    const documentY = (clientY - stripTop + windowOffset) / scale;
    source.scrollTop = Math.max(
      0,
      Math.min(documentY - grabOffset, source.scrollHeight),
    );
    caretToDocumentY(documentY);
  }

  strip.addEventListener("mousedown", (event) => {
    if (strip.hidden) {
      return;
    }
    event.preventDefault();
    dragging = true;
    document.body.classList.add("is-dragging");
    const stripTop = strip.getBoundingClientRect().top;
    const documentY = (event.clientY - stripTop + windowOffset) / scale;

    source.scrollTop = Math.max(
      0,
      Math.min(documentY - source.clientHeight / 2, source.scrollHeight),
    );
    update();
    grabOffset = (event.clientY - stripTop + windowOffset) / scale - source.scrollTop;
    caretToDocumentY(documentY);
  });
  window.addEventListener("mousemove", (event) => {
    if (dragging) {
      scrollToPointer(event.clientY);
    }
  });
  window.addEventListener("mouseup", () => {
    dragging = false;
    document.body.classList.remove("is-dragging");
  });

  strip.addEventListener("wheel", (event) => {
    if (strip.hidden) {
      return;
    }
    event.preventDefault();
    source.scrollTop += event.deltaY;
  }, {passive: false});

  source.addEventListener("input", () => clone());
  source.addEventListener("scroll", () => update());
  pane.onSelection(drawOccurrences);

  let refreshPending = false;
  const refresh = () => {
    if (isDragging()) {
      refreshPending = true;
      return;
    }
    update();
  };

  new ResizeObserver(refresh).observe(source);
  window.addEventListener("resize", refresh);
  onDragSettle(() => {
    if (!refreshPending) {
      return;
    }
    refreshPending = false;
    update();
  });

  let renderedDocId;
  let renderedDiagnostics;
  let renderedRevision = -1;
  let renderedHidden = null;
  const render = (state) => {
    const doc = getActiveDoc(state);
    const hidden = !doc || Boolean(state.minimapHidden);

    if (hidden !== renderedHidden) {
      renderedHidden = hidden;
      strip.hidden = hidden;
      if (!hidden) {
        update();
      }
    }

    const id = doc ? doc.id : null;
    const diagnostics = doc ? doc.diagnostics : [];
    const revisionChanged = state.sourceRevision !== renderedRevision;

    if (id !== renderedDocId || diagnostics !== renderedDiagnostics || revisionChanged) {
      renderedDocId = id;
      renderedDiagnostics = diagnostics;
      renderedRevision = state.sourceRevision;
      clone();
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {strip};
}
