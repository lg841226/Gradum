// One tooltip for the whole document, driven by a [data-tooltip] attribute
// rather than an instance bound to each control. The rail + the tab strip
// rebuild their buttons on every render, and a delegated reader means a fresh
// button answers the pointer the moment it is inserted, with nothing to bind +
// nothing to clean up when it goes away.
//
// This replaces the native title attribute, which waits about a second, is drawn
// by the platform, ignores the theme and cannot be positioned. Every control
// that used to set a title carries [data-tooltip] instead.

// Long enough that the bubble does not fire while the pointer is merely crossing
// the bar, short enough that it arrives while the pointer is still resting.
const SHOW_DELAY = 500;

// The bubble clears the control by this much, and stops this far short of the
// window's edges.
const ANCHOR_GAP = 6;
const WINDOW_EDGE = 8;

// The bubble is appended to the editor container rather than <body>, so it
// resolves the container's theme tokens while staying position: fixed. Pointer
// delegation stays on the document, since the controls it serves are rebuilt.
export function mountTooltip({container = document.body} = {}) {
  const bubble = document.createElement("div");
  bubble.className = "tooltip";
  bubble.setAttribute("role", "tooltip");
  bubble.setAttribute("aria-hidden", "true");
  container.appendChild(bubble);

  let anchor = null;
  let timer = 0;

  function hide() {
    clearTimeout(timer);
    timer = 0;
    anchor = null;
    bubble.classList.remove("is-visible");
  }

  function place(target) {
    const rect = target.getBoundingClientRect();
    const left = Math.max(
      WINDOW_EDGE,
      Math.min(
        rect.left + (rect.width - bubble.offsetWidth) / 2,
        window.innerWidth - bubble.offsetWidth - WINDOW_EDGE,
      ),
    );
    let top = rect.top - bubble.offsetHeight - ANCHOR_GAP;
    let above = true;
    if (top < WINDOW_EDGE) {
      top = rect.bottom + ANCHOR_GAP;
      above = false;
    }
    bubble.style.left = `${Math.round(left)}px`;
    bubble.style.top = `${Math.round(top)}px`;
    bubble.style.setProperty("--tooltip-shift", above ? "3px" : "-3px");
    bubble.style.setProperty("--tooltip-origin", above ? "bottom" : "top");
  }

  function show(target) {
    timer = 0;

    if (!target.isConnected) {
      hide();
      return;
    }

    bubble.textContent = target.dataset.tooltip;
    bubble.classList.add("is-visible");

    place(target);
  }

  function targetOf(node) {
    return node instanceof Element ? node.closest("[data-tooltip]") : null;
  }

  document.addEventListener("pointerover", (event) => {
    const target = targetOf(event.target);
    const skip = !target || !target.dataset.tooltip || target === anchor;

    if (skip) {
      return;
    }

    hide();
    anchor = target;
    timer = setTimeout(() => show(target), SHOW_DELAY);
  });

  document.addEventListener("pointerout", (event) => {
    if (!anchor || anchor.contains(event.relatedTarget)) {
      return;
    }

    hide();
  });

  document.addEventListener("pointerdown", hide, true);
  document.addEventListener("keydown", hide, true);
  document.addEventListener("scroll", hide, true);
  window.addEventListener("blur", hide);

  return {bubble};
}
