import {clear} from "../../core/dom.js";
import {copy} from "../copy.js";
import {createIcon} from "./icon.js";

// variant: "file" | "panel". File tabs carry a dirty dot and a close control;
// panel tabs are plain tool-window labels.
export function createTab({
                            id,
                            label,
                            icon,
                            variant = "file",
                            active = false,
                            dirty = false,
                            tooltip,
                            onActivate,
                            onClose,
                            onRename,
                          } = {}) {
  const tab = document.createElement("div");
  tab.className = "tab";
  tab.dataset.variant = variant;
  tab.setAttribute("role", "tab");
  tab.setAttribute("tabindex", "0");
  tab.setAttribute("aria-selected", active ? "true" : "false");
  if (id != null) {
    tab.dataset.id = String(id);
  }
  if (variant === "file") {
    tab.dataset.dirty = dirty ? "true" : "false";
  }
  if (tooltip) {
    tab.dataset.tooltip = tooltip;
  }
  if (icon) {
    tab.appendChild(createIcon({name: icon}));
  }

  const labelNode = document.createElement("span");
  labelNode.classList.add("tab-label");
  labelNode.textContent = label;
  tab.appendChild(labelNode);

  if (variant === "file") {
    const mark = document.createElement("span");
    mark.className = "tab-mark";
    const dot = document.createElement("span");
    dot.className = "tab-dot";
    const close = document.createElement("button");
    close.type = "button";
    close.className = "tab-close";
    close.dataset.tooltip = copy.tooltip.closeTab;
    close.setAttribute("aria-label", copy.tooltip.closeTab);
    close.appendChild(createIcon({name: "close", size: 16}));
    close.addEventListener("click", (event) => {
      event.stopPropagation();
      onClose?.(tab);
    });
    mark.append(dot, close);
    tab.appendChild(mark);
  }

  tab.addEventListener("click", () => onActivate?.(tab));
  tab.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onActivate?.(tab);
    }
  });
  if (onRename) {
    tab.addEventListener("dblclick", () => onRename(tab));
  }

  return tab;
}

export function createTabStrip() {
  const strip = document.createElement("div");
  strip.className = "tab-strip";
  strip.setAttribute("role", "tablist");
  return strip;
}

export function renderTabStrip(strip, tabs, handlers = {}) {
  clear(strip);
  for (const tab of tabs) {
    strip.appendChild(createTab({...tab, ...handlers}));
  }
  return strip;
}
