// The two composer pills: the model picker + the permission picker.
//
// Each owns its chip, its popup + the persisted choice. The island reads the
// current value through `value()` when it builds a run, and never reaches into
// the menu DOM.

import {createIcon} from "../../ui/components/icon.js";
import {createListItem} from "../../ui/components/list.js";
import {copy} from "../../ui/copy.js";
import {beautifyProvider, parseModelName} from "../../ui/modelName.js";
import {createProviderIcon} from "../../ui/providerIcons.js";
import {STORAGE_KEYS} from "../../core/storageKeys.js";
import {readStorage, writeStorage} from "../../core/storage.js";

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
  let hasModels = true;

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
    hasModels: () => hasModels,
    applyModels(models) {
      const usable = models.filter((model) => model.available);
      hasModels = usable.length > 0;
      chip.disabled = !hasModels;
      if (!hasModels) {
        label.textContent = copy.chat.noModels;
        return;
      }

      const saved = readStorage(STORAGE_KEYS.model, "");
      if (usable.some((model) => model.name === saved)) {
        value = saved;
      }

      const rows = usable.map((model) => {
        const parsed = parseModelName(model.name);
        const backend = beautifyProvider(model.provider);
        const row = createListItem({
          label: parsed.displayName,
          detail: backend,
          lead: createProviderIcon({provider: parsed.provider}),
          active: model.name === value,
          onClick: () => choose(model.name, parsed.displayName),
        });
        row.dataset.value = model.name;
        return row;
      });

      list.replaceChildren(
        createListItem({
          label: copy.chat.runtimeDefault,
          active: value === "",
          onClick: () => choose("", copy.chat.runtimeDefault, null),
        }),
        ...rows,
      );

      if (value) {
        const current = usable.find((model) => model.name === value);
        if (current) {
          currentLabel = parseModelName(current.name).displayName;
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

  const label = document.createElement("span");
  label.className = "perm-chip-label";
  label.textContent = modeInfo(value).label;
  chip.append(label, createIcon({name: "chevron-down"}));

  const menu = document.createElement("div");
  menu.className = "perm-menu";
  menu.hidden = true;
  const list = document.createElement("div");
  list.className = "model-menu-list";
  menu.append(list);

  const control = mountMenu({chip, menu, onOpen: onOtherOpen});

  const choose = (next) => {
    value = next;
    label.textContent = modeInfo(next).label;
    writeStorage(STORAGE_KEYS.permission, next);
    selectRow(list, value);
    control.close();
  };

  list.replaceChildren(
    ...PERMISSION_MODES.map((mode) => {
      const row = document.createElement("div");
      row.className = "list-item perm-item";
      row.setAttribute("role", "treeitem");
      row.setAttribute("tabindex", "0");
      row.setAttribute("aria-selected", mode.value === value ? "true" : "false");
      row.dataset.value = mode.value;

      row.append(createIcon({name: mode.icon}));
      const text = document.createElement("div");
      text.className = "perm-item-text";
      const name = document.createElement("span");
      name.className = "list-item-label";
      name.textContent = modeInfo(mode.value).label;
      const info = document.createElement("span");
      info.className = "perm-item-info";
      info.textContent = modeInfo(mode.value).info;
      text.append(name, info);
      row.append(text);
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
