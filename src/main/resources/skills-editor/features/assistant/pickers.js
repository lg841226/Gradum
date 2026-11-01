// The two composer pills: the model picker and the permission picker.
//
// Each owns its chip, its popup and the persisted choice. The island reads the
// current value through `value()` when it builds a run, and never reaches into
// the menu DOM.

import {createIcon} from "../../ui/components/icon.js";
import {createListItem} from "../../ui/components/list.js";
import {copy} from "../../ui/copy.js";
import {STORAGE_KEYS} from "../../core/storageKeys.js";
import {readStorage, writeStorage} from "../../core/storage.js";

// The tool-surface modes the server's /events accepts as `toolMode`
// (Routes.kt). Same wire values the plugin's permission selector sends; its
// debug entry is plugin-only, so the web picker leaves it out.
export const PERMISSION_MODES = [
  {value: "read_only", icon: "reader-mode"},
  {value: "edit", icon: "edit"},
  {value: "agent", icon: "build"},
  {value: "yolo", icon: "warning-outline"},
];

const modeInfo = (value) => copy.chat.modes[value] ?? copy.chat.modes.read_only;

function mountMenu({chip, menu, onOpen}) {
  const close = () => {
    menu.hidden = true;
    chip.removeAttribute("data-open");
  };

  const open = () => {
    onOpen?.();
    menu.hidden = false;
    chip.dataset.open = "true";
  };

  chip.addEventListener("click", (event) => {
    event.stopPropagation();
    if (menu.hidden) {
      open();
    } else {
      close();
    }
  });
  document.addEventListener("click", (event) => {
    if (!menu.hidden && !menu.contains(event.target)) {
      close();
    }
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      close();
    }
  });

  return {open, close};
}

function selectRow(list, value) {
  for (const row of list.children) {
    row.setAttribute("aria-selected", row.dataset.value === value ? "true" : "false");
  }
}

export function createModelPicker({onOtherOpen} = {}) {
  let value = "";
  let currentLabel = copy.chat.runtimeDefault;

  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = "btn model-chip";
  chip.dataset.tooltip = copy.chat.model;

  const label = document.createElement("span");
  label.className = "model-chip-label";
  label.textContent = currentLabel;
  chip.append(label, createIcon({name: "chevron-down"}));

  const menu = document.createElement("div");
  menu.className = "model-menu";
  menu.hidden = true;
  const list = document.createElement("div");
  list.className = "model-menu-list";
  menu.append(list);

  const control = mountMenu({chip, menu, onOpen: onOtherOpen});

  const choose = (next, nextLabel) => {
    value = next;
    currentLabel = nextLabel;
    label.textContent = nextLabel;
    writeStorage(STORAGE_KEYS.model, next);
    selectRow(list, value);
    control.close();
  };

  return {
    chip,
    menu,
    close: () => control.close(),
    value: () => value,
    applyModels(models) {
      const usable = models.filter((model) => model.available);
      if (usable.length === 0) {
        chip.disabled = true;
        label.textContent = copy.chat.noModels;
        return;
      }

      const saved = readStorage(STORAGE_KEYS.model, "");
      if (usable.some((model) => model.name === saved)) {
        value = saved;
      }

      const rows = usable.map((model) => {
        const rowLabel = `${model.name} (${model.provider})`;
        const row = createListItem({
          label: rowLabel,
          active: model.name === value,
          onClick: () => choose(model.name, rowLabel),
        });
        row.dataset.value = model.name;
        return row;
      });

      list.replaceChildren(
        createListItem({
          label: copy.chat.runtimeDefault,
          active: value === "",
          onClick: () => choose("", copy.chat.runtimeDefault),
        }),
        ...rows,
      );

      if (value) {
        const current = usable.find((model) => model.name === value);
        if (current) {
          currentLabel = `${current.name} (${current.provider})`;
          label.textContent = currentLabel;
        }
      }
    },
  };
}

export function createPermissionPicker({onOtherOpen} = {}) {
  let value = readStorage(STORAGE_KEYS.permission, "read_only");
  if (!PERMISSION_MODES.some((mode) => mode.value === value)) {
    value = "read_only";
  }

  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = "btn perm-chip";
  chip.dataset.tooltip = copy.chat.permissions;

  let chipIcon = createIcon({name: PERMISSION_MODES.find((mode) => mode.value === value).icon});
  const label = document.createElement("span");
  label.className = "perm-chip-label";
  label.textContent = modeInfo(value).label;
  chip.append(chipIcon, label, createIcon({name: "chevron-down"}));

  const menu = document.createElement("div");
  menu.className = "perm-menu";
  menu.hidden = true;
  const list = document.createElement("div");
  list.className = "model-menu-list";
  menu.append(list);

  const control = mountMenu({chip, menu, onOpen: onOtherOpen});

  const choose = (next) => {
    value = next;
    const mode = PERMISSION_MODES.find((entry) => entry.value === next);
    const nextIcon = createIcon({name: mode.icon});
    chipIcon.replaceWith(nextIcon);
    chipIcon = nextIcon;
    label.textContent = modeInfo(next).label;
    writeStorage(STORAGE_KEYS.permission, next);
    selectRow(list, value);
    control.close();
  };

  list.replaceChildren(
    ...PERMISSION_MODES.map((mode) => {
      // The plugin's menu rows read as two lines: the mode's name over its
      // info sentence, so the picker explains the surface it switches to.
      const row = document.createElement("div");
      row.className = "list-item perm-item";
      row.setAttribute("role", "treeitem");
      row.setAttribute("tabindex", "0");
      row.setAttribute("aria-selected", mode.value === value ? "true" : "false");
      row.dataset.value = mode.value;

      const name = document.createElement("span");
      name.className = "list-item-label";
      name.textContent = modeInfo(mode.value).label;
      const info = document.createElement("span");
      info.className = "perm-item-info";
      info.textContent = modeInfo(mode.value).info;
      row.append(name, info);
      row.addEventListener("click", () => choose(mode.value));
      return row;
    }),
  );

  return {
    chip,
    menu,
    close: () => control.close(),
    value: () => value,
  };
}
