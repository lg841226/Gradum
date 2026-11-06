// A pill segmented control: one raised track, one popup-coloured thumb and text
// buttons, the same shape the menu bar uses for its mode switch. The thumb is
// measured against the group's own box instead of a hard-coded offset, so the
// same control fits the menu bar and a settings row at any interface scale.
//
// The first placement skips the transition (is-ready is added only after the
// thumb has real coordinates), so a freshly opened surface snaps into place
// instead of sliding in; later moves get the shared 120ms motion.
import {createButton} from "./button.js";

const controllers = new WeakMap();

export function createSegmented({label, options, value, onChange, className} = {}) {
  const group = document.createElement("div");
  group.className = className ? `segmented ${className}` : "segmented";
  group.setAttribute("role", "group");
  if (label) {
    group.setAttribute("aria-label", label);
  }

  const thumb = document.createElement("span");
  thumb.className = "segmented-thumb";
  thumb.setAttribute("aria-hidden", "true");
  group.appendChild(thumb);

  const items = new Map();
  for (const option of options) {
    const key = String(option.value);
    const button = createButton({
      label: option.label,
      variant: "menu",
      onClick: () => onChange?.(option.value),
    });
    button.dataset.value = key;
    items.set(key, button);
    group.appendChild(button);
  }

  let current = String(value);
  let ready = false;

  const place = () => {
    const target = items.get(current);
    if (!target) {
      return;
    }
    const outer = group.getBoundingClientRect();

    if (outer.width === 0) {
      return;
    }

    const rect = target.getBoundingClientRect();
    thumb.style.width = `${rect.width}px`;
    thumb.style.height = `${rect.height}px`;
    thumb.style.transform = `translate(${rect.left - outer.left}px, ${rect.top - outer.top}px)`;
    if (!ready) {
      ready = true;
      thumb.classList.add("is-ready");
    }
  };

  const apply = (next) => {
    current = String(next);
    for (const [key, button] of items) {
      button.setAttribute("aria-pressed", key === current ? "true" : "false");
    }
    place();
  };

  apply(value);
  controllers.set(group, apply);

  new ResizeObserver(place).observe(group);

  return group;
}

export function setSegmentedValue(group, value) {
  controllers.get(group)?.(value);
}
