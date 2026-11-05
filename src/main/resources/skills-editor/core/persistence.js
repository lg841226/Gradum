// The editor's layout choices outlive a reload: which view is selected, which
// tool window sits at the bottom, whether the rail + the tree are folded,
// whether the editor is split into two views, whether the minimap strip shows,
// how wide and tall the user dragged the panels, and whether the agent island is
// open. Keeping them is what makes a refreshed page look the way it was left.
// Which file held the focus is a choice like the rest: it rides along here by
// name, so a refresh comes back to the document the user was reading instead of
// falling to the default skill.
//
// Only choices are kept. The size-driven overrides (autoRail / autoBottom) are
// recomputed from the window on every boot, and the documents come back from the
// server, so neither belongs here. The theme has its own key in
// core/storageKeys.js, because it has to be applied before the first paint;
// this one is read while the store is being built.

import {STORAGE_KEYS} from "./storageKeys.js";
import {readJsonStorage, writeStorage} from "./storage.js";

const LAYOUT_KEYS = {
  view: "string",
  panel: "string",
  openFile: "string",
  railWidth: "number",
  collapsed: "boolean",
  bottomHeight: "number",
  treeCollapsed: "boolean",
  railCollapsed: "boolean",
  split: "boolean",
  splitDirection: "string",
  splitRatio: "number",
  minimapHidden: "boolean",
  chatOpen: "boolean",
};

const SPLIT_SHARE_MIN = 0.2;
const SPLIT_SHARE_MAX = 0.8;

const LAYOUT_ENUMS = {
  view: ["explorer", "problems", "output", "editor", "all", "session"],
  panel: ["problems", "output"],
  splitDirection: ["right", "down"],
};

function pickLayout(source) {
  const picked = {};
  for (const key of Object.keys(LAYOUT_KEYS)) {
    const value = source[key];
    if (typeof value !== LAYOUT_KEYS[key]) {
      continue;
    }

    if (LAYOUT_KEYS[key] === "number") {
      if (!Number.isFinite(value)) {
        continue;
      }
      if (key === "splitRatio") {
        picked[key] = Math.min(Math.max(value, SPLIT_SHARE_MIN), SPLIT_SHARE_MAX);
        continue;
      }
      if (value <= 0) {
        continue;
      }
    }

    const allowed = LAYOUT_ENUMS[key];
    if (allowed && !allowed.includes(value)) {
      continue;
    }
    picked[key] = value;
  }
  return picked;
}

export function loadLayout() {
  const stored = readJsonStorage(STORAGE_KEYS.layout, null);
  return stored && typeof stored === "object" ? pickLayout(stored) : {};
}

export function createLayoutWriter() {
  let lastWritten = "";
  let openFile = "";
  return (state) => {
    const focused = state.docs.find((doc) => doc.id === state.activeId);
    if (focused) {
      openFile = focused.name;
    } else if (openFile === "" && typeof state.openFile === "string") {
      openFile = state.openFile;
    }

    const serialized = JSON.stringify(pickLayout({...state, openFile}));
    if (serialized === lastWritten) {
      return;
    }
    lastWritten = serialized;
    writeStorage(STORAGE_KEYS.layout, serialized);
  };
}
