// Touch layout: the side panels float as movable windows. Each panel is
// dragged by its header, resized from a corner grip, and wears the accent
// border while the pointer holds it. Desktop never mounts this module.

const FLOAT_QUERY = "(hover: none), (pointer: coarse)";

/* True where panels float as windows instead of grid islands. Features that
   decide visibility read this so the size-driven auto-collapse never overrides
   a user toggle: afloat, the panels take no room from the editor at all. */
export function isFloatLayout() {
  return window.matchMedia(FLOAT_QUERY).matches;
}

const MIN_W = 220;
const MIN_H = 160;
const EDGE = 8;

function track(panel, handle, {onMove}) {
  handle.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) {
      return;
    }
    if (event.target.closest("button, input, select, textarea")) {
      return;
    }
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
  });
}

export function mountFloatPanels(store, {canvas}) {
  if (!window.matchMedia(FLOAT_QUERY).matches) {
    return;
  }
  const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

  for (const panel of canvas.querySelectorAll(".rail, .bottom")) {
    const header = panel.querySelector(".panel-header");
    if (header) {
      track(panel, header, {
        onMove: (dx, dy, rect) => {
          panel.style.setProperty(
            "--float-x",
            `${clamp(rect.left + dx, EDGE, window.innerWidth - 64)}px`);
          panel.style.setProperty(
            "--float-y",
            `${clamp(rect.top + dy, 40, window.innerHeight - 64)}px`);
        },
      });
    }

    const grip = document.createElement("div");
    grip.className = "float-resize";
    panel.append(grip);
    track(panel, grip, {
      onMove: (dx, dy, rect) => {
        panel.style.setProperty(
          "--float-w",
          `${clamp(rect.width + dx, MIN_W, window.innerWidth - rect.left - EDGE)}px`);
        panel.style.setProperty(
          "--float-h",
          `${clamp(rect.height + dy, MIN_H, window.innerHeight - rect.top - EDGE)}px`);
      },
    });
  }
}
