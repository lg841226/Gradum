// The two composer pills: the model picker and the permission picker.
//
// Each owns its chip, its popup and the persisted choice. The island reads the
// current value through `value()` when it builds a run, and never reaches into
// the menu DOM.

import {createIcon} from "../../ui/components/icon.js";
import {createListItem} from "../../ui/components/list.js";
import {copy} from "../../ui/copy.js";
import {beautifyProvider, parseModelName} from "../../ui/modelName.js";
import {createProviderIcon} from "../../ui/providerIcons.js";
import {STORAGE_KEYS} from "../../core/storageKeys.js";
import {readStorage, writeStorage} from "../../core/storage.js";

// Icon per built-in mode id; user-authored modes fall back to a generic one.
const MODE_ICONS = {
  read_only: "reader-mode",
  edit: "edit",
  agent: "build",
  yolo: "warning-outline",
};
const GENERIC_MODE_ICON = "settings";
const FALLBACK_MODE_ORDER = ["read_only", "edit", "agent", "yolo"];

const fallbackModes = () => FALLBACK_MODE_ORDER.map((id) => ({
  value: id,
  icon: MODE_ICONS[id],
  label: copy.chat.modes[id].label,
  info: copy.chat.modes[id].info,
}));

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
  let modes = fallbackModes();
  let value = readStorage(STORAGE_KEYS.permission, "read_only");
  if (!modes.some((mode) => mode.value === value)) {
    value = "read_only";
  }

  const modeInfo = (id) => modes.find((mode) => mode.value === id)
    ?? {value: id, icon: GENERIC_MODE_ICON, label: id, info: ""};

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

  const render = () => {
    list.replaceChildren(
      ...modes.map((mode) => {
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
        name.textContent = mode.label;
        const info = document.createElement("span");
        info.className = "perm-item-info";
        info.textContent = mode.info;
        text.append(name, info);
        row.append(text);
        row.addEventListener("click", () => choose(mode.value));
        return row;
      }),
    );
    label.textContent = modeInfo(value).label;
  };
  render();

  return {
    chip,
    menu,
    close: () => control.close(),
    value: () => value,
    applyModes(entries) {
      if (!Array.isArray(entries) || entries.length === 0) {
        return;
      }

      modes = entries.map((entry) => ({
        value: entry.id,
        icon: MODE_ICONS[entry.id] ?? GENERIC_MODE_ICON,
        label: entry.label || entry.id,
        info: entry.description ?? "",
      }));

      if (!modes.some((mode) => mode.value === value)) {
        value = modes.some((mode) => mode.value === "read_only")
          ? "read_only"
          : modes[0].value;
        writeStorage(STORAGE_KEYS.permission, value);
      }

      render();
    },
  };
}
