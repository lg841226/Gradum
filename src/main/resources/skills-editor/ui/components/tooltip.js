// One tooltip for the whole document, driven by a [data-tooltip] attribute
// rather than an instance bound to each control. The rail and the tab strip
// rebuild their buttons on every render, and a delegated reader means a fresh
// button answers the pointer the moment it is inserted, with nothing to bind
// and nothing to clean up when it goes away.
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

export function mountTooltip({root = document} = {}) {
  const bubble = document.createElement("div");
  bubble.className = "tooltip";
  bubble.setAttribute("role", "tooltip");
  // The controls carry their own accessible name through aria-label, so this
  // bubble is the sighted-user copy of it; announcing it as well would read
  // every label twice.
  bubble.setAttribute("aria-hidden", "true");
  root.body.appendChild(bubble);

  let anchor = null;
  let timer = 0;

  function hide() {
    clearTimeout(timer);
    timer = 0;
    anchor = null;
    bubble.classList.remove("is-visible");
  }

  // Centred over the control and lifted clear of it, dropping below only when
  // the window's top edge is in the way, and pulled back inside at either side
  // so a control near a corner still shows the whole bubble.
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
    // Position and animation share the transform channel, so the anchor offset
    // lives in left/top and the animation keeps transform to itself.
    bubble.style.left = `${Math.round(left)}px`;
    bubble.style.top = `${Math.round(top)}px`;
    // The bubble travels the last few pixels out of the control it belongs to
    // and scales up from that edge, so it reads as growing out of the control
    // rather than fading in place.
    bubble.style.setProperty("--tooltip-shift", above ? "3px" : "-3px");
    bubble.style.setProperty("--tooltip-origin", above ? "bottom" : "top");
  }

  function show(target) {
    timer = 0;
    // The rail and the tab strip rebuild on every state change, so the control
    // may have been replaced during the delay and left with no box to hang the
    // bubble off.
    if (!target.isConnected) {
      hide();
      return;
    }
    bubble.textContent = target.dataset.tooltip;
    // The bubble is laid out while still invisible, so its size is known before
    // the first painted frame: showing it first and moving it after would flash
    // it at the window's corner.
    place(target);
    bubble.classList.add("is-visible");
  }

  function targetOf(node) {
    return node instanceof Element ? node.closest("[data-tooltip]") : null;
  }

  root.addEventListener("pointerover", (event) => {
    const target = targetOf(event.target);
    const skip = !target || !target.dataset.tooltip || target === anchor;
    if (skip) {
      return;
    }
    hide();
    anchor = target;
    timer = setTimeout(() => show(target), SHOW_DELAY);
  });

  root.addEventListener("pointerout", (event) => {
    // Moving between a control and its own icon stays inside the control, so it
    // is not a reason to take the bubble away.
    if (!anchor || anchor.contains(event.relatedTarget)) {
      return;
    }
    hide();
  });

  // Anything that moves the page out from under the anchor, or takes the
  // pointer's attention elsewhere, dismisses the bubble. Scroll is caught in the
  // capture phase: it does not bubble, but a scroll of any pane has to count.
  root.addEventListener("pointerdown", hide, true);
  root.addEventListener("keydown", hide, true);
  root.addEventListener("scroll", hide, true);
  window.addEventListener("blur", hide);

  return {bubble};
}
