const RAIL_MIN = 150;
const RAIL_MAX = 480;
const BOTTOM_MIN = 80;
const BOTTOM_MAX_RATIO = 0.7;

const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

// A pointer drag that grabs the window rather than the handle, so the pointer
// may leave the thin strip without dropping the drag. Shared by the rail width,
// the bottom tool window, and the editor's own split, so every handle takes hold
// and let's go the same way.
export function dragPointer(cursor, onMove) {
  document.body.classList.add("is-dragging");
  document.body.style.cursor = cursor;

  const stop = () => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", stop);
    window.removeEventListener("pointercancel", stop);
    document.body.classList.remove("is-dragging");
    document.body.style.cursor = "";
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", stop);
  window.addEventListener("pointercancel", stop);
}

function createSplitter(variant, orientation) {
  const splitter = document.createElement("div");
  splitter.className = `splitter ${variant}`;
  splitter.setAttribute("role", "separator");
  splitter.setAttribute("aria-orientation", orientation);
  return splitter;
}

// Drag handles for the rail width and the bottom panel height. Only the rail
// width is applied here; the bottom height is applied by the panels feature so
// a single writer controls that grid track.
export function mountSplitters(store, {canvas, rail, bottom}) {
  const railSplitter = createSplitter("splitter-v", "vertical");
  const bottomSplitter = createSplitter("splitter-h", "horizontal");
  canvas.append(railSplitter, bottomSplitter);

  railSplitter.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const left = rail.getBoundingClientRect().left;

    dragPointer("col-resize", (moveEvent) => {
      const width = clamp(moveEvent.clientX - left, RAIL_MIN, RAIL_MAX);
      store.setState({railWidth: width});
    });
  });

  bottomSplitter.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const edge = bottom.getBoundingClientRect().bottom;

    dragPointer("row-resize", (moveEvent) => {
      const max = window.innerHeight * BOTTOM_MAX_RATIO;
      const height = clamp(edge - moveEvent.clientY, BOTTOM_MIN, max);
      store.setState({bottomHeight: height});
    });
  });

  const apply = (state) => {
    canvas.style.setProperty("--rail-width", `${state.railWidth}px`);
  };

  store.subscribe(apply);
  apply(store.getState());

  return {railSplitter, bottomSplitter};
}
