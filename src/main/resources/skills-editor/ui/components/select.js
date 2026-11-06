// A dropdown that keeps the platform select out of the loop: the trigger is
// the same chip the composer pickers use (current value + chevron), and the
// menu is the same popup surface as the rest of the editor.
//
// The menu is portalled to the canvas rather than nested in the trigger, so
// the settings island's overflow can never clip it. It is placed against the
// trigger in viewport coordinates and flips left/up when it would leave the
// canvas.
import {createIcon} from "./icon.js";
import {createListItem} from "./list.js";

export function createSelect({label, options, value, onChange, portal} = {}) {
  const host = portal ?? document.body;

  const trigger = document.createElement("button");
  trigger.type = "button";
  trigger.className = "btn select-trigger";
  trigger.dataset.variant = "ghost";
  trigger.setAttribute("aria-haspopup", "listbox");
  trigger.setAttribute("aria-expanded", "false");
  if (label) {
    trigger.setAttribute("aria-label", label);
  }

  const text = document.createElement("span");
  text.className = "select-trigger-label";
  trigger.append(text, createIcon({name: "chevron-down"}));

  const menu = document.createElement("div");
  menu.className = "select-menu";
  menu.hidden = true;

  const list = document.createElement("div");
  list.className = "select-menu-list";
  list.setAttribute("role", "listbox");
  if (label) {
    list.setAttribute("aria-label", label);
  }
  menu.append(list);
  host.appendChild(menu);

  const rows = new Map();
  for (const option of options) {
    const key = String(option.value);
    const row = createListItem({
      label: option.label,
      active: key === String(value),
      onClick: () => choose(key),
    });
    row.removeAttribute("title");
    row.setAttribute("role", "option");
    row.dataset.value = key;
    rows.set(key, row);
    list.appendChild(row);
  }

  let current = String(value);
  let open = false;

  const applySelection = () => {
    for (const [key, row] of rows) {
      row.setAttribute("aria-selected", key === current ? "true" : "false");
    }
    const option = options.find((entry) => String(entry.value) === current);
    text.textContent = option ? option.label : current;
  };
  applySelection();

  const bounds = () => {
    if (host === document.body) {
      return {
        left: 0,
        top: 0,
        right: window.innerWidth,
        bottom: window.innerHeight,
      };
    }
    const rect = host.getBoundingClientRect();
    const style = getComputedStyle(host);
    return {
      left: rect.left + (parseFloat(style.paddingLeft) || 0),
      top: rect.top + (parseFloat(style.paddingTop) || 0),
      right: rect.right - (parseFloat(style.paddingRight) || 0),
      bottom: rect.bottom - (parseFloat(style.paddingBottom) || 0),
    };
  };

  const gap = () => {
    menu.style.left = "0px";
    menu.style.top = "0px";
    menu.style.transform = "translateY(var(--space-1))";
    const value = Math.max(0, menu.getBoundingClientRect().top);
    menu.style.transform = "";
    return value;
  };

  const place = () => {
    const rect = trigger.getBoundingClientRect();
    const box = bounds();
    const width = menu.offsetWidth;
    const height = menu.offsetHeight;

    let left = rect.left;
    if (left + width > box.right) {
      left = rect.right - width;
    }
    left = Math.max(box.left, Math.min(left, box.right - width));

    const spacing = gap();
    let top = rect.bottom + spacing;
    if (top + height > box.bottom) {
      top = rect.top - spacing - height;
    }
    top = Math.max(box.top, Math.min(top, box.bottom - height));

    menu.style.left = `${left}px`;
    menu.style.top = `${top}px`;
  };

  const openMenu = (fromEnd = false) => {
    if (open) {
      return;
    }
    open = true;
    menu.hidden = false;
    trigger.dataset.open = "true";
    trigger.setAttribute("aria-expanded", "true");
    place();

    const items = [...rows.values()];
    const selected = rows.get(current);
    const target = fromEnd ? items[items.length - 1] : (selected ?? items[0]);
    target?.focus({preventScroll: true});
  };

  const close = (refocus = false) => {
    if (!open) {
      return;
    }
    open = false;
    menu.hidden = true;
    delete trigger.dataset.open;
    trigger.setAttribute("aria-expanded", "false");
    if (refocus) {
      trigger.focus({preventScroll: true});
    }
  };

  const choose = (key) => {
    if (!rows.has(key)) {
      return;
    }
    current = key;
    applySelection();
    close(true);
    onChange?.(key);
  };

  trigger.addEventListener("click", () => {
    if (open) {
      close();
    } else {
      openMenu();
    }
  });

  trigger.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && open) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openMenu(event.key === "ArrowUp");
    }
  });

  list.addEventListener("keydown", (event) => {
    const row = event.target.closest(".list-item");
    if (!row) {
      return;
    }
    const items = [...rows.values()];

    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      const next = items[(items.indexOf(row) + step + items.length) % items.length];
      next.focus();
      return;
    }
    if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      items[event.key === "Home" ? 0 : items.length - 1].focus();
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close(true);
    }
  });

  document.addEventListener("click", (event) => {
    if (!open) {
      return;
    }
    if (menu.contains(event.target) || trigger.contains(event.target)) {
      return;
    }
    close();
  });

  document.addEventListener("scroll", (event) => {
    if (open && !menu.contains(event.target)) {
      close();
    }
  }, true);

  window.addEventListener("resize", () => close());

  return {
    element: trigger,
    setValue(next) {
      const key = String(next);
      if (key !== current) {
        current = key;
        applySelection();
      }
    },
    close,
    isOpen: () => open,
  };
}
