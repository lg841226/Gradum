import {clampShare} from "../core/layout.js";
import {dragPointer, isDragging} from "./splitter.js";

export function createSplitLayout(store, {container, panes, keys, isShown}) {
  container.classList.add("split-host");
  for (const pane of panes) {
    pane.classList.add("split-pane");
  }

  const divider = document.createElement("div");
  divider.className = "pane-divider";
  divider.setAttribute("role", "separator");

  divider.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const bounds = container.getBoundingClientRect();
    const down = store.getState()[keys.direction] === "down";
    const span = down ? bounds.height : bounds.width;
    const start = down ? bounds.top : bounds.left;
    if (!span) {
      return;
    }

    let ratio = store.getState()[keys.ratio] ?? 0.5;
    let moved = false;

    dragPointer(down ? "row-resize" : "col-resize", (moveEvent) => {
      const offset = (down ? moveEvent.clientY : moveEvent.clientX) - start;
      ratio = clampShare(offset / span);
      container.style.setProperty("--split-ratio", String(ratio));
      moved = true;
    }, () => {
      if (moved) {
        store.setState({[keys.ratio]: ratio});
      }
    });
  });

  function apply(state) {
    const shown = isShown(state);
    const down = state[keys.direction] === "down";
    container.classList.toggle("is-split", shown);
    container.classList.toggle("is-split-down", down);
    if (!isDragging()) {
      container.style.setProperty("--split-ratio", String(state[keys.ratio] ?? 0.5));
    }
    divider.setAttribute("aria-orientation", down ? "horizontal" : "vertical");
    return shown;
  }

  return {divider, apply};
}
