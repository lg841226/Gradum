const clamp = (value, min, max) => Math.min(Math.max(value, min), max);
const resolve = (value, context) => (typeof value === "function" ? value(context) : value);

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

function readScale(canvas) {
  const value = parseFloat(getComputedStyle(canvas).getPropertyValue("--ui-scale"));
  return value > 0 ? value : 1;
}

function setLive(canvas, spec, value) {
  canvas.style.setProperty(spec.cssVar, `calc(${value}px * var(--ui-scale))`);
}

export function mountSplitters(store, {canvas, panels}) {
  const siblings = {};
  for (const descriptor of panels) {
    siblings[descriptor.id] = descriptor;
  }

  const splitters = [];
  for (const descriptor of panels) {
    const vertical = descriptor.side === "left" || descriptor.side === "right";
    const splitter = createSplitter(
      vertical ? "splitter-v" : "splitter-h",
      vertical ? "vertical" : "horizontal",
    );
    if (descriptor.size.splitterClass) {
      splitter.classList.add(descriptor.size.splitterClass);
    }
    canvas.appendChild(splitter);

    splitter.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      const element = descriptor.element;
      const scale = readScale(canvas);
      const context = {
        canvas,
        element,
        siblings,
        scale,
        gap: parseFloat(getComputedStyle(canvas).getPropertyValue("--island-gap")) || 0,
        state: store.getState(),
      };
      const min = resolve(descriptor.size.min, context);
      const max = resolve(descriptor.size.max, context);
      const horizontal = descriptor.side === "bottom";
      const direction = descriptor.side === "left" ? 1 : -1;
      const rect = element.getBoundingClientRect();
      const anchor = horizontal ? rect.bottom : descriptor.side === "left" ? rect.left : rect.right;
      const position = (moveEvent) => (horizontal ? moveEvent.clientY : moveEvent.clientX);
      let value = context.state[descriptor.size.key] ?? descriptor.size.default;
      let moved = false;

      dragPointer(horizontal ? "row-resize" : "col-resize", (moveEvent) => {
        value = clamp(direction * (position(moveEvent) - anchor) / scale, min, max);
        setLive(canvas, descriptor.size, value);
        moved = true;
      }, () => {
        if (moved) {
          store.setState({[descriptor.size.key]: value});
        }
      });
    });
    splitters.push(splitter);
  }

  return {splitters};
}
