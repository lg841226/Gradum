import {createIcon} from "./icon.js";
import {copy} from "../copy.js";

export function createListItem({label, icon, active = false, muted = false, onClick} = {}) {
  const item = document.createElement("div");
  item.className = "list-item";
  item.setAttribute("role", "treeitem");
  item.setAttribute("tabindex", "0");
  item.setAttribute("aria-selected", active ? "true" : "false");
  item.dataset.muted = muted ? "true" : "false";
  item.title = label;
  if (icon) {
    item.appendChild(createIcon({name: icon}));
  }

  const name = document.createElement("span");
  name.className = "list-item-label";
  name.textContent = label;
  item.appendChild(name);

  item.addEventListener("click", () => onClick?.());
  item.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onClick?.();
    }
  });
  return item;
}

// The tree root: a twisty that folds a branch, and a label. It stands for the
// directory the skills live in rather than a skill, so it is purely
// presentational — no selection, no open callback, only the fold. The branch is
// nested inside the treeitem, as ARIA requires, so a collapsed root still owns
// its group; the group is what hides.
export function createTreeRow({label, expanded = true, onToggle, children} = {}) {
  const item = document.createElement("div");
  item.className = "tree-item";
  item.setAttribute("role", "treeitem");
  item.setAttribute("aria-expanded", expanded ? "true" : "false");

  const row = document.createElement("div");
  row.className = "tree-row";
  row.title = label;

  const twisty = document.createElement("button");
  twisty.type = "button";
  twisty.className = "tree-twisty";
  const tip = expanded ? copy.tooltip.collapseTree : copy.tooltip.expandTree;
  twisty.dataset.tooltip = tip;
  twisty.setAttribute("aria-label", tip);
  twisty.appendChild(createIcon({name: "chevron-down"}));
  twisty.addEventListener("click", () => onToggle?.());

  const name = document.createElement("span");
  name.className = "tree-row-label";
  name.textContent = label;

  row.append(twisty, createIcon({name: "folder"}), name);
  item.appendChild(row);
  if (children) {
    item.appendChild(children);
  }
  return item;
}
