// Panel slots: three IDE tool windows (left column, bottom row, right
// column), each holding an open flag plus an optional view. Pure state logic,
// no DOM, no dependencies, so panelRegistry / menubar / assistant / main can
// all import it safely.

export const SLOT_VIEWS = ["sessions", "problems", "output", "files"];

export const SLOTS = [
  {id: "left", openKey: "slotLeftOpen", viewKey: "slotLeftView"},
  {id: "center", openKey: "slotCenterOpen", viewKey: "slotCenterView"},
  {id: "right", openKey: "slotRightOpen", viewKey: "slotRightView"},
];

export function slotById(id) {
  return SLOTS.find((slot) => slot.id === id) ?? null;
}

export function slotOfView(state, name) {
  return SLOTS.find((slot) => state[slot.viewKey] === name) ?? null;
}

// A view earns its place in a slot only when it has something to show:
// an island that would render just its placeholder is dropped from the
// dropdown, and an open slot holding one falls back to its empty panel.
export function viewHasContent(state, name) {
  switch (name) {
    case "sessions":
      return state.chatOpen === true
        ? (state.sessions ?? []).length > 0
        : (state.tree ?? []).length > 0;
    case "problems": {
      const doc = state.docs.find((item) => item.id === state.activeId);
      return (doc?.diagnostics ?? []).length > 0;
    }
    case "output":
      return state.log.length > 0;
    case "files":
      return state.files.length > 0;
    default:
      return true;
  }
}

// Views this slot may offer: every view that has content and is not claimed
// by another slot.
export function availableViews(state, selfId) {
  const taken = new Set(
    SLOTS.filter((slot) => slot.id !== selfId)
      .map((slot) => state[slot.viewKey])
      .filter((view) => view != null),
  );
  return SLOT_VIEWS.filter((view) => !taken.has(view) && viewHasContent(state, view));
}

export function showViewPatch(state, name) {
  if (!viewHasContent(state, name)) {
    return {};
  }
  const holder = slotOfView(state, name);
  if (holder) {
    return {[holder.openKey]: true};
  }
  const free = SLOTS.find((slot) => state[slot.viewKey] == null);
  return free ? {[free.viewKey]: name, [free.openKey]: true} : {};
}

export function toggleFilesPatch(state) {
  const holder = slotOfView(state, "files");
  if (holder && state[holder.openKey]) {
    return {[holder.openKey]: false};
  }
  return showViewPatch(state, "files");
}

export function filesVisible(state) {
  const holder = slotOfView(state, "files");
  return Boolean(holder && state[holder.openKey]);
}

export function slotEffectiveOpen(state, slot, floating) {
  if (state[slot.openKey] !== true) {
    return false;
  }

  if (floating || slot.id !== "center") {
    return true;
  }
  return state.autoBottom !== true;
}
