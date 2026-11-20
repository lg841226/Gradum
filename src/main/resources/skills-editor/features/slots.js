// Panel slots: three IDE tool windows (left column, bottom row, right
// column), each holding an open flag plus an optional view. Pure state logic,
// no DOM, no dependencies, so panelRegistry / menubar / assistant / main can
// all import it safely.

export const SLOT_VIEWS = [
  {name: "explorer", placements: ["left", "right"]},
  {name: "sessions", placements: ["left", "right"]},
  {name: "files", placements: ["left", "right"]},
  {name: "problems", placements: ["center"]},
  {name: "output", placements: ["center"]},
];

export const SLOT_VIEW_NAMES = SLOT_VIEWS.map((view) => view.name);

// editorPreview marks: the pane sits on the edge the slot docks against.
export const SLOT_ICONS = {left: "panelLeft", center: "panelBottom", right: "panelRight"};

export function viewFitsSlot(name, slotId) {
  return SLOT_VIEWS.some(
    (view) => view.name === name && view.placements.includes(slotId),
  );
}

export const SLOTS = [
  {id: "left", openKey: "slotLeftOpen", viewKey: "slotLeftView", floatKey: "slotLeftFloat"},
  {id: "center", openKey: "slotCenterOpen", viewKey: "slotCenterView", floatKey: "slotCenterFloat"},
  {id: "right", openKey: "slotRightOpen", viewKey: "slotRightView", floatKey: "slotRightFloat"},
];

export function slotById(id) {
  return SLOTS.find((slot) => slot.id === id) ?? null;
}

export function slotOfView(state, name) {
  return SLOTS.find((slot) => state[slot.viewKey] === name) ?? null;
}

export function viewHasContent(state, name) {
  switch (name) {
    case "explorer":
      return (state.tree ?? []).length > 0;
    case "sessions":
      return (state.sessions ?? []).length > 0;
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

export function viewOptions(state, selfId) {
  return SLOT_VIEWS.map(({name}) => {
    const holder = slotOfView(state, name);
    const free = holder === null || holder.id === selfId;
    const fits = viewFitsSlot(name, selfId);
    return {view: name, enabled: fits && free && viewHasContent(state, name)};
  });
}

export function showViewPatch(state, name) {
  if (!viewHasContent(state, name)) {
    return {};
  }
  const holder = slotOfView(state, name);
  if (holder) {
    return {[holder.openKey]: true};
  }
  const free = SLOTS.find(
    (slot) => state[slot.viewKey] == null && viewFitsSlot(name, slot.id),
  );
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
