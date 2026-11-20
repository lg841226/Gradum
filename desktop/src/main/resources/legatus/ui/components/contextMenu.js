import {createIcon} from "./icon.js";

// A cursor-anchored popup menu, portalled to the document and reused as a
// single open instance. It borrows the dropdown's surface (.context-menu mirrors
// the .select-menu look) and the same edge-aware placement, but opens wherever
// the pointer asked instead of under a trigger.
//
// An item is either {label, icon, enabled, onSelect} or {separator: true}.
// Disabled items read as inert and are skipped by the keyboard walk.
let layer = null;
let detach = null;

function close() {
  if (!layer) {
    return;
  }
  const node = layer;
  layer = null;
  if (detach) {
    detach();
    detach = null;
  }
  node.remove();
}

function buildItem(item) {
  if (item.separator) {
    const rule = document.createElement("div");
    rule.className = "context-menu-sep";
    rule.setAttribute("role", "separator");
    return rule;
  }

  const row = document.createElement("button");
  row.type = "button";
  row.className = "context-menu-item";
  row.setAttribute("role", "menuitem");

  // A fixed leading gutter keeps every label aligned whether or not the item
  // carries a glyph, so the icon-less close rows line up with Rename/Copy.
  const glyph = document.createElement("span");
  glyph.className = "context-menu-glyph";
  if (item.icon) {
    glyph.appendChild(createIcon({name: item.icon}));
  }
  row.appendChild(glyph);

  const label = document.createElement("span");
  label.className = "context-menu-label";
  label.textContent = item.label;
  row.appendChild(label);

  if (item.enabled === false) {
    row.disabled = true;
    row.setAttribute("aria-disabled", "true");
    return row;
  }
  row.addEventListener("click", () => {
    close();
    item.onSelect?.();
  });
  return row;
}

export function openContextMenu({x, y, items, portal}) {
  if (layer) {
    close();
  }

  const menu = document.createElement("div");
  menu.className = "context-menu";
  menu.setAttribute("role", "menu");
  menu.tabIndex = -1;

  const enabled = [];
  for (const item of items) {
    if (!item) {
      continue;
    }
    if (item.separator) {
      menu.appendChild(buildItem(item));
      continue;
    }
    const row = buildItem(item);
    if (row && row.disabled !== true) {
      enabled.push(row);
    }
    menu.appendChild(row);
  }

  // The design tokens live on .canvas, not :root, so the menu must be portalled
  // inside it to inherit colours, radii and shadows; appending to <body> would
  // leave every var() unresolved and render the rows as unstyled black text.
  const host = portal ?? document.querySelector(".canvas") ?? document.body;
  host.appendChild(menu);
  layer = menu;

  // Place against the cursor, then flip and clamp so the menu stays inside the
  // host: it opens down-right of the pointer and pulls back on whichever edge
  // it would cross.
  const pad = 8;
  const bounds = host === document.body
    ? {left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight}
    : host.getBoundingClientRect();
  const width = menu.offsetWidth;
  const height = menu.offsetHeight;
  let left = x;
  let top = y;
  if (left + width > bounds.right - pad) {
    left = bounds.right - pad - width;
  }
  if (top + height > bounds.bottom - pad) {
    top = bounds.bottom - pad - height;
  }
  menu.style.left = `${Math.max(bounds.left + pad, left)}px`;
  menu.style.top = `${Math.max(bounds.top + pad, top)}px`;

  const focusStep = (delta) => {
    if (enabled.length === 0) {
      return;
    }
    const at = enabled.indexOf(document.activeElement);
    const next = (at + delta + enabled.length) % enabled.length;
    enabled[next].focus();
  };

  const onKey = (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      focusStep(1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusStep(-1);
    } else if (event.key === "Home") {
      event.preventDefault();
      enabled[0]?.focus();
    } else if (event.key === "End") {
      event.preventDefault();
      enabled[enabled.length - 1]?.focus();
    }
  };

  const onPointerDown = (event) => {
    if (!menu.contains(event.target)) {
      close();
    }
  };

  menu.addEventListener("keydown", onKey);
  document.addEventListener("mousedown", onPointerDown, true);
  window.addEventListener("resize", close);
  window.addEventListener("blur", close);
  detach = () => {
    document.removeEventListener("mousedown", onPointerDown, true);
    window.removeEventListener("resize", close);
    window.removeEventListener("blur", close);
  };

  (enabled[0] ?? menu).focus({preventScroll: true});
}
