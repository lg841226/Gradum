import {activeDoc} from "../core/store.js";

// The minimap: a scaled clone of the syntax overlay in a fixed-width strip on
// the editor's right edge, the way a desktop editor parks its overview ruler.
//
// The scale fits the visible code width to the strip, so a line that fills the
// code pane reaches the far edge of the strip and longer lines clip. The strip
// itself slides to keep the viewport rectangle in view, and pressing or
// dragging anywhere in it scrolls the editor to that point, centred under the
// pointer. The clone refreshes on every keystroke and on document switches;
// squiggle bands are carried over as overview markers while the current-line
// band is dropped, because it belongs to the editor pane alone.
export function mountMinimap(store, {body, source, highlightCode}) {
  const strip = document.createElement("div");
  strip.className = "minimap";

  const content = document.createElement("div");
  content.className = "highlight minimap-content";

  const viewport = document.createElement("div");
  viewport.className = "minimap-viewport";

  strip.append(content, viewport);
  body.appendChild(strip);

  let scale = 1;
  let windowOffset = 0;
  let dragging = false;
  let grabOffset = 0;

  function clone() {
    content.innerHTML = highlightCode.innerHTML;
    content.querySelectorAll(".active").forEach((lineNode) => lineNode.classList.remove("active"));
    update();
  }

  function update() {
    const codeWidth = source.clientWidth;
    const stripWidth = strip.clientWidth;
    const stripHeight = strip.clientHeight;
    if (strip.hidden || !codeWidth || !stripWidth || !stripHeight) return;
    scale = stripWidth / codeWidth;

    const scaledContent = source.scrollHeight * scale;
    const viewTop = source.scrollTop * scale;
    const viewHeight = source.clientHeight * scale;

    windowOffset = scaledContent <= stripHeight
      ? 0
      : Math.max(0, Math.min(
        viewTop - (stripHeight - viewHeight) / 2,
        scaledContent - stripHeight,
      ));
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
  }

  strip.addEventListener("mousedown", (event) => {
    if (strip.hidden) return;
    event.preventDefault();
    dragging = true;
    document.body.classList.add("is-dragging");
    const stripTop = strip.getBoundingClientRect().top;
    grabOffset = (event.clientY - stripTop + windowOffset) / scale - source.scrollTop;
    scrollToPointer(event.clientY);
  });
  window.addEventListener("mousemove", (event) => {
    if (dragging) scrollToPointer(event.clientY);
  });
  window.addEventListener("mouseup", () => {
    dragging = false;
    document.body.classList.remove("is-dragging");
  });

  // The wheel over the strip scrolls the editor, so the map stays put and the
  // text moves under the viewport rectangle.
  strip.addEventListener("wheel", (event) => {
    if (strip.hidden) return;
    event.preventDefault();
    source.scrollTop += event.deltaY;
  }, {passive: false});

  source.addEventListener("input", () => clone());
  source.addEventListener("scroll", () => update());

  // Splitter and rail drags resize the code pane without a window resize, so
  // the strip watches the text layer instead of the window.
  new ResizeObserver(() => update()).observe(source);

  let renderedDocId;
  let renderedDiagnostics;
  const render = (state) => {
    const doc = activeDoc(state);
    strip.hidden = !doc;
    const id = doc ? doc.id : null;
    const diagnostics = doc ? doc.diagnostics : [];
    if (id !== renderedDocId || diagnostics !== renderedDiagnostics) {
      renderedDocId = id;
      renderedDiagnostics = diagnostics;
      clone();
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {strip, update};
}
