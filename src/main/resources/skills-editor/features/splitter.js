const RAIL_MIN = 150;
const RAIL_MAX = 480;
const BOTTOM_MIN = 80;
const BOTTOM_MAX_RATIO = 0.7;

const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

let activeDrags = 0;
const settleHandlers = new Set();

export function isDragging() {
  return activeDrags > 0;
}

export function onDragSettle(handler) {
  settleHandlers.add(handler);
  return () => settleHandlers.delete(handler);
}

// A pointer drag that grabs the window rather than the handle, so the pointer
// may leave the thin strip without dropping the drag. Shared by the rail width,
// the bottom tool window + the editor's own split, so every handle takes hold
// and let's go the same way.
export function dragPointer(cursor, onMove, onEnd) {
  document.body.classList.add("is-dragging");
  document.body.style.cursor = cursor;
  activeDrags += 1;

  let frame = 0;
  let pending = null;

  const flush = () => {
    if (frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
    const step = pending;
    pending = null;
    if (step) {
      onMove(step);
    }
  };

  const move = (event) => {
    pending = event;
    if (frame) {
      return;
    }
    frame = requestAnimationFrame(() => {
      frame = 0;
      const step = pending;
      pending = null;
      if (step) {
        onMove(step);
      }
    });
  };

  const stop = (event) => {
    flush();
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", stop);
    window.removeEventListener("pointercancel", stop);
    activeDrags -= 1;
    if (activeDrags === 0) {
      document.body.classList.remove("is-dragging");
      document.body.style.cursor = "";
      for (const handler of settleHandlers) {
        handler();
      }
    }
    onEnd?.(event);
  };

  window.addEventListener("pointermove", move);
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

// Drag handles for the rail width + the bottom panel height. Only the rail
// width is applied here; the bottom height is applied by the panels feature so
// a single writer controls that grid track.
export function mountSplitters(store, {canvas, rail, bottom}) {
  const railSplitter = createSplitter("splitter-v", "vertical");
  const bottomSplitter = createSplitter("splitter-h", "horizontal");
  canvas.append(railSplitter, bottomSplitter);

  const scale = () => {
    const value = parseFloat(getComputedStyle(canvas).getPropertyValue("--ui-scale"));
    return value > 0 ? value : 1;
  };

  railSplitter.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const left = rail.getBoundingClientRect().left;
    const factor = scale();
    let width = store.getState().railWidth;
    let moved = false;

    dragPointer("col-resize", (moveEvent) => {
      width = clamp((moveEvent.clientX - left) / factor, RAIL_MIN, RAIL_MAX);
      canvas.style.setProperty("--rail-width", `calc(${width}px * var(--ui-scale))`);
      moved = true;
    }, () => {
      if (moved) {
        store.setState({railWidth: width});
      }
    });
  });

  bottomSplitter.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const edge = bottom.getBoundingClientRect().bottom;
    const factor = scale();
    const max = (window.innerHeight * BOTTOM_MAX_RATIO) / factor;
    let height = store.getState().bottomHeight;
    let moved = false;

    dragPointer("row-resize", (moveEvent) => {
      height = clamp((edge - moveEvent.clientY) / factor, BOTTOM_MIN, max);
      canvas.style.setProperty("--bottom-height", `calc(${height}px * var(--ui-scale))`);
      moved = true;
    }, () => {
      if (moved) {
        store.setState({bottomHeight: height});
      }
    });
  });

  const apply = (state) => {
    if (isDragging()) {
      return;
    }
    canvas.style.setProperty("--rail-width", `calc(${state.railWidth}px * var(--ui-scale))`);
  };

  store.subscribe(apply);
  apply(store.getState());

  return {railSplitter, bottomSplitter};
}
