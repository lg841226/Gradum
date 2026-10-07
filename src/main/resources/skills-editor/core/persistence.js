// The editor's layout choices outlive a reload: which view is selected, which
// tool window sits at the bottom, whether the rail and the tree are folded,
// whether the editor is split into two views, whether the minimap strip shows,
// and how wide and tall the user dragged the panels. Keeping them is what makes
// a refreshed page look the way it was left. Which file held the focus is a
// choice like the rest: it rides along here by name, so a refresh comes back to
// the document the user was reading instead of falling to the default skill.
//
// Only choices are kept. The size-driven overrides (autoRail / autoBottom) are
// recomputed from the window on every boot, and the documents come back from the
// server, so neither belongs here. The theme has its own key in core/theme.js,
// because it has to be applied before the first paint; this one is read while the
// store is being built.

export const STORAGE_KEY = "gradum.skillsEditor.layout";

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
};

function pickLayout(source) {
  const picked = {};
  for (const key of Object.keys(LAYOUT_KEYS)) {
    const value = source[key];
    if (typeof value !== LAYOUT_KEYS[key]) {
      continue;
    }

    const isNumber = LAYOUT_KEYS[key] === "number";
    const validNumber = Number.isFinite(value) && value > 0;
    if (isNumber && !validNumber) {
      continue;
    }
    picked[key] = value;
  }
  return picked;
}

// The patch to spread over the store's defaults at boot. Anything missing or
// unreadable simply leaves the default in place.
export function loadLayout() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "null");
    return stored && typeof stored === "object" ? pickLayout(stored) : {};
  } catch (storageError) {
    return {};
  }
}

// The store subscriber that writes the layout back. It compares against what it
// last wrote, because the store also reports the caret on every move and there is
// no reason to touch storage for a change the layout did not make. The open file
// is read from the focused document as the state changes; until a document
// exists — the first writes of a fresh boot — the value loaded from storage
// stands in, so those writes cannot blank out what the last session remembered.
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
    try {
      window.localStorage.setItem(STORAGE_KEY, serialized);
    } catch (storageError) {

    }
  };
}
