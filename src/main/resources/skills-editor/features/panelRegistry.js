import {STATE_SCHEMA} from "../core/schema.js";
import {copy} from "../ui/copy.js";
import {createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {createPanel, createPanelHeader} from "../ui/components/panel.js";
import {createSelect} from "../ui/components/select.js";
import {createTab} from "../ui/components/tab.js";
import {isFloatLayout, mountFloatPanels, onFloatChange} from "./floatPanels.js";
import {mountFileTree} from "./fileTree.js";
import {mountAssistant} from "./assistant/index.js";
import {mountRail} from "./rail.js";
import {isDragging, mountSplitters} from "./splitter.js";
import {filesVisible, showViewPatch, SLOT_ICONS, SLOTS, viewHasContent, viewOptions} from "./slots.js";

const resolve = (value, context) => (typeof value === "function" ? value(context) : value);
const defaultOf = (key) => STATE_SCHEMA[key].default;

const modeOf = (state) => (state.chatOpen === true ? "agent" : "developer");

function createSize(key, cssVar, min, max, splitterClass) {
  return {key, cssVar, default: defaultOf(key), min, max, splitterClass};
}

function isOpen(descriptor, state, floating) {
  const spec = descriptor.open;
  if (spec.modes && !spec.modes.includes(modeOf(state))) {
    return false;
  }
  const fallback = spec.inverted ? !spec.defaultOpen : spec.defaultOpen;
  const raw = state[spec.key] ?? fallback;
  let open = spec.inverted ? !raw : Boolean(raw);
  if (!floating && spec.autoKey && state[spec.autoKey]) {
    open = false;
  }
  return open;
}

// Closing a panel is just hiding it: the flex dock collapses the space and
// the paired splitter goes with it, so no layout state class is needed.
function applyOpenState(descriptor, element, open) {
  element.hidden = !open;
  if (descriptor.splitter) {
    descriptor.splitter.hidden = !open;
  }
}

function applySize(canvas, descriptor, state) {
  if (isDragging()) {
    return;
  }
  const spec = descriptor.size;
  const value = state[spec.key] ?? spec.default;
  canvas.style.setProperty(spec.cssVar, `calc(${value}px * var(--ui-scale))`);
}

function createAction(spec, state) {
  const icon = resolve(spec.icon, state);
  const node = createIconButton({
    icon,
    tooltip: resolve(spec.tooltip, state),
    disabled: resolve(spec.disabled, state) === true,
    onClick: spec.onClick,
  });
  return {node, spec, icon};
}

function applyAction(handle, state) {
  const icon = resolve(handle.spec.icon, state);
  if (icon !== handle.icon) {
    handle.icon = icon;
    handle.node.replaceChildren(createIcon({name: icon}));
  }
  const tooltip = resolve(handle.spec.tooltip, state);
  if (tooltip && handle.node.dataset.tooltip !== tooltip) {
    handle.node.dataset.tooltip = tooltip;
    handle.node.setAttribute("aria-label", tooltip);
  }
  handle.node.disabled = resolve(handle.spec.disabled, state) === true;
}

function createHeader(descriptor, state) {
  const actionHandles = (descriptor.actions ?? []).map((spec) => createAction(spec, state));
  const actionNodes = actionHandles.map((handle) => handle.node);
  const header = createPanelHeader({
    title: resolve(descriptor.title, state) ?? "",
    actions: actionNodes,
  });
  const tabHandles = (descriptor.tabs ?? []).map((spec) => ({
    spec,
    node: createTab({
      label: spec.label,
      variant: "panel",
      active: resolve(spec.active, state) === true,
      onActivate: spec.onActivate,
    }),
  }));
  const spacer = header.querySelector(".spacer");
  for (const handle of tabHandles) {
    header.insertBefore(handle.node, spacer);
  }
  descriptor.element.appendChild(header);
  return {
    titleNode: header.querySelector(".panel-title"),
    actions: actionHandles,
    tabs: tabHandles,
  };
}

const VIEW_LABELS = {
  explorer: copy.rail.title,
  sessions: copy.rail.sessions,
  problems: copy.control.problems,
  output: copy.control.output,
  files: copy.files.title,
};

// The three slots are IDE tool windows: the left slot is the full-height left
// column, the middle slot the bottom row, the right slot the full-height right
// column. Opening a slot with no view shows an empty panel whose centred
// "Open Tool" dropdown picks the view; once picked, the view's own panel
// (header + body) replaces the slot content outright. Slots remember their
// view: closing a slot only hides it, reopening restores the same view.
// Each view has exactly one host element, moved between slots.
function createSlots(store, {
  listDirectory,
  onPick,
  onOpen,
  onCreateSkill,
  onCreateSession,
  onOpenSession,
}) {
  const createBody = () => {
    const body = document.createElement("div");
    body.className = "panel-body";
    return body;
  };
  const hosts = {
    explorer: createBody(),
    sessions: createBody(),
    problems: createBody(),
    output: createBody(),
    files: createBody(),
  };
  // Explorer and Sessions are separate islands now: each pins one rail
  // variant, so either can be open in any mode and both can coexist.
  mountRail(store, {
    panel: hosts.explorer, onOpen, onCreate: onCreateSkill, onOpenSession, variant: "skills",
  });
  mountRail(store, {
    panel: hosts.sessions, onOpen, onCreate: onCreateSession, onOpenSession, variant: "sessions",
  });
  mountFileTree(store, {panel: hosts.files, listDirectory, onPick, visible: filesVisible});

  let canvas = null;
  const views = SLOTS.map((spec) => ({spec, nodes: null, signature: null, host: null}));

  const controller = {
    problemsBody: hosts.problems,
    outputBody: hosts.output,
    show: (name) => store.setState(showViewPatch(store.getState(), name)),
  };

  const apply = (state) => {
    if (!canvas) {
      return;
    }
    for (const view of views) {
      const {nodes} = view;
      if (!nodes) {
        continue;
      }
      const assigned = state[view.spec.viewKey] ?? null;
      // A view that runs out of content only falls back to the empty panel;
      // the assignment survives, so the view returns when its content does.
      const active = assigned !== null && viewHasContent(state, assigned);
      nodes.empty.hidden = active;
      nodes.viewWrap.hidden = !active;
      nodes.hideEmpty.hidden = active;
      const creatable = active && (assigned === "explorer" || assigned === "sessions");
      nodes.plus.hidden = !creatable;
      if (assigned) {
        const title = VIEW_LABELS[assigned];
        if (nodes.titleNode.textContent !== title) {
          nodes.titleNode.textContent = title;
        }
        if (creatable) {
          const tip = assigned === "sessions" ? copy.tooltip.newChat : copy.tooltip.newSkill;
          if (nodes.plus.dataset.tooltip !== tip) {
            nodes.plus.dataset.tooltip = tip;
            nodes.plus.setAttribute("aria-label", tip);
          }
          nodes.plus.disabled = state.reader === true;
        }
        if (view.host !== hosts[assigned]) {
          view.host = hosts[assigned];
          nodes.viewWrap.replaceChildren(nodes.header);
          nodes.viewWrap.appendChild(hosts[assigned]);
        }
      } else {
        view.host = null;
      }

      const entries = viewOptions(state, view.spec.id);
      const signature = entries.map((e) => `${e.view}${e.enabled ? 1 : 0}`).join(",");
      const options = entries.map((e) => ({
        value: e.view,
        label: VIEW_LABELS[e.view],
        disabled: !e.enabled,
      }));
      if (signature !== view.signature) {
        view.signature = signature;
        nodes.select.setOptions(options, copy.slot.pick);
      } else {
        nodes.select.setValue(copy.slot.pick);
      }
      nodes.select.element.disabled = !entries.some((e) => e.enabled);
    }
  };

  store.subscribe(apply);

  const render = (view, panel, host) => {
    canvas = host;
    const empty = document.createElement("div");
    empty.className = "slot-empty";
    const title = document.createElement("div");
    title.className = "slot-empty-title";
    title.textContent = copy.slot[view.spec.id];
    const select = createSelect({
      label: copy.slot.pick,
      options: [],
      value: copy.slot.pick,
      portal: canvas,
      onChange: (name) => store.setState({[view.spec.viewKey]: name, [view.spec.openKey]: true}),
    });
    const head = document.createElement("div");
    head.className = "slot-empty-head";
    head.append(createIcon({name: SLOT_ICONS[view.spec.id], size: 24}), title);
    empty.append(head, select.element);

    const hideEmpty = createIconButton({
      icon: "hide",
      tooltip: copy.tooltip.hide,
      // Hides the slot; a remembered view (empty right now) stays assigned.
      onClick: () => store.setState({[view.spec.openKey]: false}),
    });
    hideEmpty.classList.add("slot-empty-hide");

    const plus = createIconButton({
      icon: "plus",
      tooltip: copy.tooltip.newSkill,
      onClick: () => {
        const assigned = store.getState()[view.spec.viewKey];
        if (assigned === "sessions") {
          onCreateSession?.();
        } else {
          onCreateSkill?.();
        }
      },
    });
    plus.hidden = true;
    const hide = createIconButton({
      icon: "hide",
      tooltip: copy.tooltip.hide,
      onClick: () => store.setState({[view.spec.viewKey]: null}),
    });
    const header = createPanelHeader({title: "", actions: [plus, hide]});
    const viewWrap = document.createElement("div");
    viewWrap.className = "slot-view";

    panel.append(hideEmpty, empty, viewWrap);
    view.nodes = {
      empty,
      viewWrap,
      header,
      plus,
      hideEmpty,
      select,
      titleNode: header.querySelector(".panel-title"),
    };
    apply(store.getState());
    return controller;
  };

  const sides = {
    left: {
      side: "left",
      size: createSize("slotLeftWidth", "--slot-left-width", 150, 480, "splitter-slot-left"),
    },
    center: {
      side: "bottom",
      size: createSize(
        "bottomHeight",
        "--bottom-height",
        80,
        ({scale}) => (window.innerHeight * 0.7) / scale,
      ),
      autoKey: "autoBottom",
    },
    right: {
      side: "right",
      size: createSize("slotRightWidth", "--slot-right-width", 150, 480, "splitter-slot-right"),
    },
  };

  return views.map((view) => {
    const place = sides[view.spec.id];
    return {
      id: `slot${view.spec.id[0].toUpperCase()}${view.spec.id.slice(1)}`,
      side: place.side,
      className: `slot slot-${view.spec.id}`,
      header: false,
      size: place.size,
      open: {
        key: view.spec.openKey,
        defaultOpen: defaultOf(view.spec.openKey),
        autoKey: place.autoKey,
      },
      floating: true,
      render: ({panel, canvas: host}) => render(view, panel, host),
    };
  });
}

export function mountPanelRegistry(store, {canvas, dock, dockRow, dockMain, panels}) {
  const api = {};
  const entries = [];
  const initialState = store.getState();
  const floating = isFloatLayout();
  let wide = initialState.wideLayout === true;

  mountSplitters(store, {
    canvas,
    dock,
    dockRow,
    dockMain,
    wide,
    panels: panels.filter((descriptor) => descriptor.size),
  });

  // The main island always lives in the main column; the bottom island joins
  // it only in wide layout, otherwise it spans the dock under the row.
  const hostFor = (descriptor) => {
    if (descriptor.id === "assistant") {
      return dockMain;
    }
    if (descriptor.side === "bottom") {
      return wide ? dockMain : dock;
    }
    return dockRow;
  };

  let bottom = null;
  let bottomOpen = isOpen(
    panels.find((descriptor) => descriptor.side === "bottom"), initialState, floating,
  );

  for (const descriptor of panels) {
    const element = createPanel();
    element.classList.add(...descriptor.className.split(/\s+/));
    descriptor.element = element;
    hostFor(descriptor).appendChild(element);
    if (descriptor.side === "bottom") {
      bottom = descriptor;
    }

    applyOpenState(descriptor, element, isOpen(descriptor, initialState, floating));

    const chrome = descriptor.header === false ? null : createHeader(descriptor, initialState);
    api[descriptor.id] = descriptor.render({panel: element, state: initialState, canvas}) ?? {};

    if (descriptor.size) {
      applySize(canvas, descriptor, initialState);
    }
    entries.push({descriptor, chrome});
  }

  dockMain.classList.toggle("has-bottom", wide && bottomOpen && !floating);

  store.subscribe((state) => {
    const floating = isFloatLayout();
    const nextWide = state.wideLayout === true;
    if (nextWide !== wide) {
      wide = nextWide;
      const host = wide ? dockMain : dock;
      host.appendChild(bottom.splitter);
      host.appendChild(bottom.element);
    }
    for (const {descriptor, chrome} of entries) {
      const open = isOpen(descriptor, state, floating);
      if (descriptor.side === "bottom") {
        bottomOpen = open;
      }
      applyOpenState(descriptor, descriptor.element, open);
      if (chrome) {
        const title = resolve(descriptor.title, state) ?? "";
        if (chrome.titleNode.textContent !== title) {
          chrome.titleNode.textContent = title;
        }
        for (const handle of chrome.actions) {
          applyAction(handle, state);
        }
        for (const handle of chrome.tabs) {
          handle.node.setAttribute(
            "aria-selected",
            resolve(handle.spec.active, state) === true ? "true" : "false",
          );
        }
      }
      if (descriptor.size) {
        applySize(canvas, descriptor, state);
      }
    }
    dockMain.classList.toggle("has-bottom", wide && bottomOpen && !floating);
  });

  mountFloatPanels(store, {
    canvas,
    panels: panels.filter((descriptor) => descriptor.floating),
  });

  onFloatChange(() => store.setState({}));

  return {
    panels,
    api,
    menus: panels.flatMap((descriptor) => descriptor.menus ?? []),
  };
}

export function createPanelRegistry({
                                      store,
                                      onOpen,
                                      onCreateSkill,
                                      onCreateSession,
                                      onOpenSession,
                                      listDirectory,
                                      onPick,
                                      onOpenFile,
                                    }) {
  const slots = createSlots(store, {
    listDirectory,
    onPick,
    onOpen,
    onCreateSkill,
    onCreateSession,
    onOpenSession,
  });

  const assistant = {
    id: "assistant",
    title: copy.control.agent,
    side: "center",
    className: "assistant",
    header: false,
    open: {
      key: "chatOpen",
      defaultOpen: defaultOf("chatOpen"),
      modes: ["agent"],
    },
    floating: false,
    render: ({panel}) => mountAssistant(store, {panel, onOpenFile}),
  };

  return [...slots, assistant];
}
