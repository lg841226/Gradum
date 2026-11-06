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
import {pickLayout} from "./schema.js";

export function loadLayout() {
  const stored = readJsonStorage(STORAGE_KEYS.layout, null);
  if (!stored || typeof stored !== "object") {
    return {};
  }
  const picked = pickLayout(stored);
  // Layouts saved before the two surfaces had their own split state carry a
  // single split. The chat starts from that choice once; from then on the two
  // pairs are written separately.
  if (!("chatSplit" in picked)) {
    picked.chatSplit = picked.split === true;
  }
  if (!("chatSplitDirection" in picked)) {
    picked.chatSplitDirection = picked.splitDirection ?? "right";
  }
  if (!("chatSplitRatio" in picked)) {
    picked.chatSplitRatio = picked.splitRatio ?? 0.5;
  }
  return picked;
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
