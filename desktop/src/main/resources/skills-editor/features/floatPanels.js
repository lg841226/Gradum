// Matches the (max-width: 720px) block in layout.css: below it the docked
// slots become fixed popups, so they must also gain drag/resize handles.
const FLOAT_QUERY = "(hover: none), (pointer: coarse), (max-width: 720px)";

export function isFloatLayout() {
  return window.matchMedia(FLOAT_QUERY).matches;
}

export function onFloatChange(handler) {
  window.matchMedia(FLOAT_QUERY).addEventListener("change", handler);
}

const MIN_W = 220;
const MIN_H = 160;
const EDGE = 8;

function dragGesture(panel, handle, event, onMove) {
  event.preventDefault();
  handle.setPointerCapture(event.pointerId);
  panel.classList.add("is-dragging");

  const rect = panel.getBoundingClientRect();
  const originX = event.clientX;
  const originY = event.clientY;
  const move = (step) => {
    onMove(step.clientX - originX, step.clientY - originY, rect);
  };
  const up = () => {
    panel.classList.remove("is-dragging");
    handle.removeEventListener("pointermove", move);
    handle.removeEventListener("pointerup", up);
    handle.removeEventListener("pointercancel", up);
  };
  handle.addEventListener("pointermove", move);
  handle.addEventListener("pointerup", up);
  handle.addEventListener("pointercancel", up);
}

function begin(event, panel, handle, onMove) {
  if (event.pointerType === "mouse" && event.button !== 0) {
    return;
  }

  if (!panel.classList.contains("slot-float")) {
    return;
  }

  if (event.target.closest("button, input, select, textarea")) {
    return;
  }
  dragGesture(panel, handle, event, onMove);
}

export function mountFloatPanels(store, {panels}) {
  const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

  for (const descriptor of panels) {
    const panel = descriptor.element;
    const rightAnchored = descriptor.side === "right";

    panel.addEventListener("pointerdown", (event) => {
      const handle = event.target.closest(".panel-header, .slot-empty");
      if (!handle || !panel.contains(handle)) {
        return;
      }
      begin(event, panel, handle, (dx, dy, rect) => {
        const left = clamp(rect.left + dx, EDGE, window.innerWidth - 64);
        if (rightAnchored) {
          panel.style.setProperty(
            "--float-x",
            `${Math.max(EDGE, Math.round(window.innerWidth - left - rect.width))}px`);
        } else {
          panel.style.setProperty("--float-x", `${left}px`);
        }
        panel.style.setProperty(
          "--float-y",
          `${clamp(rect.top + dy, 40, window.innerHeight - 64)}px`);
      });
    });

    const grip = document.createElement("div");
    grip.className = "float-resize";
    panel.append(grip);
    grip.addEventListener("pointerdown", (event) => {
      begin(event, panel, grip, (dx, dy, rect) => {
        panel.style.setProperty(
          "--float-w",
          `${clamp(rect.width + dx, MIN_W, window.innerWidth - rect.left - EDGE)}px`);
        panel.style.setProperty(
          "--float-h",
          `${clamp(rect.height + dy, MIN_H, window.innerHeight - rect.top - EDGE)}px`);
      });
    });
  }
}
