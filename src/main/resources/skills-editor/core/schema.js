// One table for the editor state: what a field starts as, and how a value that
// comes back from the saved layout is checked. main.js builds the initial store
// from it, core/persistence.js validates the saved copy against it, so adding a
// state means one entry here instead of three files.

import {clampShare} from "./layout.js";

const VIEWS = ["explorer", "problems", "output", "editor", "all", "session"];
const PANELS = ["problems", "output"];
const SPLIT_DIRECTIONS = ["right", "down"];

export const STATE_SCHEMA = {
  log: {default: () => []},
  docs: {default: () => []},
  files: {default: () => []},
  sessions: {default: () => []},
  nextId: {default: 1},
  indent: {default: 4},
  uiScale: {default: 1, layout: {type: "number", enum: [0.9, 1, 1.1, 1.25]}},
  uiFontSize: {default: 14, layout: {type: "number", enum: [12, 13, 14, 15, 16]}},
  codeFontSize: {default: 13, layout: {type: "number", enum: [12, 13, 14, 15, 16, 17, 18]}},
  indentGuides: {default: true, layout: {type: "boolean"}},
  tabWidth: {default: 4, layout: {type: "number", enum: [2, 4]}},
  completionAuto: {default: true, layout: {type: "boolean"}},
  settingsX: {default: null, layout: {type: "number", clamp: (value) => Math.max(0, value)}},
  settingsY: {default: null, layout: {type: "number", clamp: (value) => Math.max(0, value)}},
  settingsW: {default: null, layout: {type: "number", clamp: (value) => Math.max(0, value)}},
  settingsH: {default: null, layout: {type: "number", clamp: (value) => Math.max(0, value)}},
  settingsNavCollapsed: {default: false, layout: {type: "boolean"}},
  focusToken: {default: 0},
  cursorCount: {default: 1},
  sourceRevision: {default: 0},
  cursor: {default: () => ({line: 1, column: 1})},
  view: {default: "problems", layout: {type: "string", enum: VIEWS}},
  panel: {default: "problems", layout: {type: "string", enum: PANELS}},
  status: {default: () => ({text: "", tone: "neutral"})},
  busyTask: {default: null},
  activeId: {default: null},
  selection: {default: null},
  activeSession: {default: null},
  busy: {default: false},
  split: {default: false, layout: {type: "boolean"}},
  chatSplit: {default: false, layout: {type: "boolean"}},
  chatOpen: {default: false, layout: {type: "boolean"}},
  chatBusy: {default: false},
  autoRail: {default: false},
  collapsed: {default: false, layout: {type: "boolean"}},
  autoBottom: {default: false},
  minimapHidden: {default: false, layout: {type: "boolean"}},
  railCollapsed: {default: false, layout: {type: "boolean"}},
  treeCollapsed: {default: false, layout: {type: "boolean"}},
  railWidth: {default: 208, layout: {type: "number"}},
  splitRatio: {default: 0.5, layout: {type: "number", clamp: clampShare}},
  chatSplitRatio: {default: 0.5, layout: {type: "number", clamp: clampShare}},
  bottomHeight: {default: 200, layout: {type: "number"}},
  splitDirection: {default: "right", layout: {type: "string", enum: SPLIT_DIRECTIONS}},
  chatSplitDirection: {default: "right", layout: {type: "string", enum: SPLIT_DIRECTIONS}},
  openFile: {default: "", layout: {type: "string"}},
  projectRoot: {default: ""},
  authed: {default: false},
  reader: {default: false},
};

export function createInitialState(overrides = {}) {
  const state = {};
  for (const [key, entry] of Object.entries(STATE_SCHEMA)) {
    state[key] = typeof entry.default === "function" ? entry.default() : entry.default;
  }
  return {...state, ...overrides};
}

export function pickLayout(source) {
  const picked = {};
  for (const [key, entry] of Object.entries(STATE_SCHEMA)) {
    const rule = entry.layout;
    if (!rule) {
      continue;
    }
    const value = source[key];
    if (typeof value !== rule.type) {
      continue;
    }
    if (rule.type === "number") {
      if (!Number.isFinite(value)) {
        continue;
      }
      if (rule.clamp) {
        picked[key] = rule.clamp(value);
        continue;
      }
      if (value <= 0) {
        continue;
      }
    }
    if (rule.enum && !rule.enum.includes(value)) {
      continue;
    }
    picked[key] = value;
  }
  return picked;
}
